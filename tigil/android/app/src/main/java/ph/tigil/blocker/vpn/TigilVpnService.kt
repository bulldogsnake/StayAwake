package ph.tigil.blocker.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import ph.tigil.blocker.R
import ph.tigil.blocker.TigilApp
import ph.tigil.blocker.data.BlocklistRepository
import ph.tigil.blocker.data.Prefs
import ph.tigil.blocker.data.RuleEngine
import ph.tigil.blocker.data.isBlocked
import ph.tigil.blocker.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The blocker.
 *
 * ## Why a VPN at all
 * Android gives an unprivileged app exactly one way to see other apps' network
 * traffic: [VpnService]. No root, no accessibility-service abuse, no per-browser
 * extension. That is why every credible on-device content blocker on Android is
 * built this way.
 *
 * ## Why it does not slow the phone down
 * We do **not** tunnel the device's traffic. The VPN routes a single fake DNS
 * address (plus a handful of hardcoded public resolvers) into the tunnel and
 * leaves every other route alone. Photos, video calls and downloads never enter
 * this process. What we intercept is DNS — a few hundred small packets an hour —
 * so battery and throughput cost is close to nothing, and there is no server to
 * run, nothing to pay for, and no user traffic we could log even if we wanted to.
 *
 * ## What it cannot do
 * DNS blocking stops a name from resolving. It does not stop a hardcoded IP
 * address, a VPN the user installs on top, or a site reached through someone
 * else's proxy. See docs/LIMITS.md — we tell users this plainly rather than
 * selling an illusion.
 */
class TigilVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private lateinit var prefs: Prefs
    private lateinit var rules: RuleEngine

    /** Guards writes back into the tunnel; the forwarding pool is concurrent. */
    private val writeLock = Any()
    private var tunnelOut: FileOutputStream? = null

    /**
     * Bounded pool for upstream lookups. Bounded on purpose: if the network
     * stalls, we want new queries dropped (the client will retry) rather than
     * an unbounded queue of stale lookups eating memory.
     */
    private val forwarders = ThreadPoolExecutor(
        2, 8, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        rules = BlocklistRepository.engine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Unconditionally, and first: we are always launched with
        // startForegroundService(), which gives us ~5 seconds to post a
        // notification or the system kills the process. That deadline applies
        // to the stop path too, so it cannot live inside start().
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                // The commitment lock lives here, not only in the UI: a
                // notification action or a shell `am startservice` must not be
                // an escape hatch the settings screen refuses to offer.
                if (prefs.isLocked) {
                    Log.i(TAG, "stop refused: commitment lock active")
                    return START_STICKY
                }
                prefs.enabled = false
                stop()
                return START_NOT_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        if (running) return

        val descriptor = establish() ?: run {
            Log.e(TAG, "VpnService.Builder.establish() returned null")
            prefs.enabled = false
            stopSelf()
            return
        }

        tunnel = descriptor
        tunnelOut = FileOutputStream(descriptor.fileDescriptor)
        running = true
        prefs.enabled = true
        if (prefs.protectingSinceEpochMs == 0L) {
            prefs.protectingSinceEpochMs = System.currentTimeMillis()
        }

        worker = Thread({ pump(descriptor) }, "tigil-dns").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.i(TAG, "protection on")
    }

    private fun establish(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(TUN_DNS)
            .addRoute(TUN_DNS, 32)

        // Apps that hardcode a public resolver would otherwise never consult the
        // DNS server we just advertised. Pulling those addresses into the tunnel
        // closes the most common accidental bypass.
        PUBLIC_RESOLVERS.forEach { builder.addRoute(it, 32) }

        // Never filter ourselves: if the blocklist somehow matched our own
        // update host we would be unable to fetch a fix.
        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        return builder.establish()
    }

    private fun pump(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)
        try {
            while (running) {
                val length = input.read(buffer)
                if (length <= 0) continue
                val datagram = UdpDatagram.parse(buffer, length) ?: continue
                when {
                    datagram.destinationPort == DNS_PORT -> handleQuery(datagram)

                    // Strict mode: swallow DoT/DoH aimed at the public resolvers
                    // we routed in. Those connections carry DNS we cannot read,
                    // so letting them through would quietly defeat the blocker.
                    // Dropping makes the client fall back to plain DNS, which we
                    // do filter.
                    prefs.strictMode && datagram.destinationPort in ENCRYPTED_DNS_PORTS ->
                        Unit

                    // Anything else addressed to a routed resolver is not ours
                    // to carry; dropping is the honest outcome.
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            // A closed tunnel surfaces as an IOException on read; that is the
            // normal shutdown path, not an error worth reporting.
        } finally {
            prefs.flushStats()
            Log.i(TAG, "pump stopped")
        }
    }

    private fun handleQuery(datagram: UdpDatagram) {
        val host = DnsMessage.questionName(datagram.payload)
        if (host == null) {
            forward(datagram)                       // not a query we parse; pass through
            return
        }

        if (rules.evaluate(host).isBlocked) {
            prefs.recordBlock(host)
            val refusal = DnsMessage.nameError(datagram.payload)
            if (refusal.isNotEmpty()) writeToTunnel(datagram.buildReply(refusal))
            return
        }
        forward(datagram)
    }

    private fun forward(datagram: UdpDatagram) {
        forwarders.execute {
            runCatching {
                DatagramSocket().use { socket ->
                    // protect() keeps this socket off our own tunnel. Without
                    // it the lookup would route back into this service and
                    // deadlock on itself.
                    if (!protect(socket)) return@runCatching
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS

                    val upstream = InetAddress.getByName(prefs.upstreamDns)
                    socket.send(
                        DatagramPacket(
                            datagram.payload, datagram.payload.size, upstream, DNS_PORT,
                        )
                    )

                    val reply = ByteArray(MAX_DNS_RESPONSE)
                    val packet = DatagramPacket(reply, reply.size)
                    socket.receive(packet)
                    writeToTunnel(
                        datagram.buildReply(reply.copyOf(packet.length))
                    )
                }
            }
            // A timed-out or failed lookup is simply not answered. The client's
            // own resolver retries, which is the behaviour it already handles.
        }
    }

    private fun writeToTunnel(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching { tunnelOut?.write(packet) }
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, TigilApp.CHANNEL_PROTECTION)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (prefs.isLocked) getString(R.string.notification_text_locked)
                else getString(R.string.notification_text)
            )
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        runCatching { tunnel?.close() }
        tunnel = null
        tunnelOut = null
        prefs.protectingSinceEpochMs = 0L
        prefs.flushStats()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // The system revokes us when another VPN takes over, or when the user
        // revokes the grant. Honour it — but remember the user's intent so
        // MainActivity can offer to re-arm.
        Log.w(TAG, "VPN permission revoked")
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        forwarders.shutdownNow()
        runCatching { tunnel?.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TigilVpn"
        const val ACTION_STOP = "ph.tigil.blocker.STOP"

        private const val NOTIFICATION_ID = 1001
        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val MAX_DNS_RESPONSE = 4096

        /** Link-local addresses inside the tunnel; never reachable off-device. */
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_DNS = "10.111.222.2"

        private val ENCRYPTED_DNS_PORTS = setOf(443, 853)

        /** Resolvers apps commonly hardcode, pulled in so they cannot bypass us. */
        private val PUBLIC_RESOLVERS = listOf(
            "8.8.8.8", "8.8.4.4",          // Google
            "1.1.1.1", "1.0.0.1",          // Cloudflare
            "9.9.9.9", "149.112.112.112",  // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "94.140.14.14", "94.140.15.15",     // AdGuard
        )

        fun start(context: Context) {
            val intent = Intent(context, TigilVpnService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // startForegroundService, not startService: stopping is often
            // triggered from a Quick Settings tile with no visible activity,
            // and a plain startService() from the background throws on O+.
            val intent = Intent(context, TigilVpnService::class.java)
                .setAction(ACTION_STOP)
            context.startForegroundService(intent)
        }
    }
}
