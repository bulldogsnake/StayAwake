package com.bulldogsnake.stayawake.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.SurfaceContainer
import com.bulldogsnake.stayawake.Prefs
import com.bulldogsnake.stayawake.Site
import com.bulldogsnake.stayawake.browser.StayAwakeWebView
import com.bulldogsnake.stayawake.safety.ParkedMonitor

/**
 * Renders a WebView onto the car's surface.
 *
 * The Car App Library hands us a Surface; we wrap it in a VirtualDisplay, show a
 * Presentation on that display, and put the WebView inside it. Touch events from the
 * car screen are injected through [TouchInjector]. A full-screen overlay blocks the page
 * (and pauses any video) whenever [ParkedMonitor] says the car is not parked.
 */
class CarBrowser(context: Context, private val prefs: Prefs) : StayAwakeWebView.Host {

    private val appContext: Context = context.applicationContext
    private val touch = TouchInjector { presentation }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var webView: StayAwakeWebView? = null
    private var overlay: LinearLayout? = null
    private var overlayTitle: TextView? = null
    private var overlayDetail: TextView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var status: ParkedMonitor.Status = ParkedMonitor.Status(ParkedMonitor.State.UNKNOWN, "Starting…")
    private var currentUrl: String? = prefs.lastUrl

    var width = 0
        private set
    var height = 0
        private set

    /** Invoked when the user taps the overlay to confirm they are parked (if allowed). */
    var onManualParkConfirm: (() -> Unit)? = null

    val isAttached: Boolean get() = presentation != null

    fun attach(container: SurfaceContainer) {
        detach()
        val surface = container.surface ?: return
        if (container.width <= 0 || container.height <= 0) return
        width = container.width
        height = container.height

        val dm = appContext.getSystemService(DisplayManager::class.java) ?: return
        val display = try {
            dm.createVirtualDisplay(
                DISPLAY_NAME, width, height, container.dpi.coerceAtLeast(120), surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            return
        }
        virtualDisplay = display

        val p = Presentation(appContext, display.display)
        p.window?.let { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        val ctx = p.context
        val rootLayout = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val wv = StayAwakeWebView(ctx, prefs).apply { host = this@CarBrowser }
        rootLayout.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
        val ov = buildOverlay(ctx)
        rootLayout.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        p.setContentView(rootLayout)
        try {
            p.show()
        } catch (t: Throwable) {
            Log.e(TAG, "Presentation could not be shown", t)
            wv.destroy()
            display.release()
            virtualDisplay = null
            return
        }

        presentation = p
        root = rootLayout
        webView = wv
        overlay = ov
        acquireWakeLock()
        wv.openUrl(currentUrl ?: StayAwakeWebView.START_URL)
        applyStatus()
    }

    fun detach() {
        releaseWakeLock()
        hideCustomViewInternal()
        webView?.let { wv ->
            root?.removeView(wv)
            wv.destroy()
        }
        webView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        root = null
        overlay = null
        overlayTitle = null
        overlayDetail = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }

    // ---- Navigation -------------------------------------------------------------------

    fun loadStartPage() {
        webView?.loadStartPage()
    }

    fun openSite(site: Site) {
        webView?.openSite(site)
    }

    fun goBack() {
        val wv = webView ?: return
        if (customView != null) {
            hideCustomViewInternal()
            return
        }
        if (wv.canGoBack()) wv.goBack() else wv.loadStartPage()
    }

    fun reload() {
        webView?.reload()
    }

    fun search(query: String) {
        webView?.search(query)
    }

    fun typeText(text: String) {
        webView?.typeText(text)
    }

    // ---- Touch from the car screen ----------------------------------------------------

    fun onClick(x: Float, y: Float) = touch.tap(x, y)

    fun onScroll(distanceX: Float, distanceY: Float) =
        touch.scroll(distanceX, distanceY, width / 2f, height / 2f)

    fun onFling() = touch.endDrag()

    // ---- Parked gate ------------------------------------------------------------------

    fun setStatus(newStatus: ParkedMonitor.Status) {
        status = newStatus
        applyStatus()
    }

    private fun applyStatus() {
        val ov = overlay ?: return
        when (status.state) {
            ParkedMonitor.State.PARKED -> ov.visibility = View.GONE
            ParkedMonitor.State.MOVING -> {
                overlayTitle?.text = "Video paused while driving"
                overlayDetail?.text = status.detail
                ov.visibility = View.VISIBLE
                webView?.pauseAllVideo()
            }
            ParkedMonitor.State.UNKNOWN -> {
                overlayTitle?.text = "Waiting for vehicle speed"
                overlayDetail?.text = status.detail
                ov.visibility = View.VISIBLE
                webView?.pauseAllVideo()
            }
        }
    }

    private fun buildOverlay(ctx: Context): LinearLayout {
        val title = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
        }
        val detail = TextView(ctx).apply {
            setTextColor(0xFFCCCCCC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 16), 0, 0)
        }
        overlayTitle = title
        overlayDetail = detail
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2000000.toInt())
            val pad = dp(ctx, 32)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(detail, LinearLayout.LayoutParams(MATCH, WRAP))
            setOnClickListener {
                if (status.state == ParkedMonitor.State.UNKNOWN) onManualParkConfirm?.invoke()
            }
        }
    }

    // ---- StayAwakeWebView.Host --------------------------------------------------------

    override fun onOpenSite(site: Site) {
        openSite(site)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        val r = root ?: run { callback.onCustomViewHidden(); return }
        hideCustomViewInternal()
        customView = view
        customViewCallback = callback
        val index = overlay?.let { r.indexOfChild(it) }?.takeIf { it >= 0 } ?: r.childCount
        r.addView(view, index, FrameLayout.LayoutParams(MATCH, MATCH))
        webView?.visibility = View.INVISIBLE
    }

    override fun onHideCustomView() {
        hideCustomViewInternal()
    }

    private fun hideCustomViewInternal() {
        val v = customView ?: return
        root?.removeView(v)
        customView = null
        webView?.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onRenderProcessGone() {
        val r = root ?: return
        val old = webView
        if (old != null) {
            r.removeView(old)
            old.destroy()
        }
        val ctx = presentation?.context ?: return
        val wv = StayAwakeWebView(ctx, prefs).apply { host = this@CarBrowser }
        r.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        webView = wv
        wv.openUrl(currentUrl ?: StayAwakeWebView.START_URL)
    }

    override fun onUrlChanged(url: String) {
        currentUrl = url
        if (url != StayAwakeWebView.START_URL) prefs.lastUrl = url
    }

    // ---- Wake lock --------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (!prefs.keepPhoneScreenOn || wakeLock != null) return
        val pm = appContext.getSystemService(PowerManager::class.java) ?: return
        try {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "StayAwake:carScreen",
            )
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = wl
        } catch (t: Throwable) {
            Log.w(TAG, "Wake lock not acquired", t)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "CarBrowser"
        private const val DISPLAY_NAME = "StayAwakeAuto"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
