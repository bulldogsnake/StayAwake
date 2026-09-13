package ph.tigil.blocker

/*
 * Core-logic tests. Everything here is pure JVM — no emulator, no Android
 * framework — which is deliberate: the hash parity, the packet checksums and
 * the DNS parser are the places a silent bug would make the whole product stop
 * blocking while still looking like it works.
 *
 * The blocklist under test is the real compiled artifact. Gradle's
 * syncBlocklist task copies blocklist/dist into the module assets before every
 * build, so these run against exactly what ships in the APK.
 */

import ph.tigil.blocker.data.Blocklist
import ph.tigil.blocker.data.Hashing
import ph.tigil.blocker.data.RuleEngine
import ph.tigil.blocker.data.Verdict
import ph.tigil.blocker.data.isBlocked
import ph.tigil.blocker.vpn.DnsMessage
import ph.tigil.blocker.vpn.UdpDatagram
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

private val DIST: File = listOf(
    File("src/main/assets"),
    File("../../blocklist/dist"),
).firstOrNull { File(it, "tigil-blocklist.bin").isFile }
    ?: error("Run blocklist/build_blocklist.py before the tests.")

class HashingTest {
    /**
     * Vectors produced by build_blocklist.py's fnv1a64(). If this test fails the
     * Kotlin and Python hashers have drifted, which would make the shipped
     * blocklist match nothing at all — silently.
     */
    @Test fun matchesThePythonBuilder() {
        assertEquals(-6182199674087820514L, Hashing.fnv1a64("bingoplus.ph"))
        assertEquals(-1820755384511809574L, Hashing.fnv1a64("jiliko.com"))
        assertEquals(6298361471204529350L, Hashing.fnv1a64("example.com"))
        assertEquals(-3750763034362895579L, Hashing.fnv1a64(""))
        assertEquals(-5808556873153909620L, Hashing.fnv1a64("a"))
        assertEquals(-2270099988724484805L, Hashing.fnv1a64("sw418.com"))
    }
}

class BlocklistTest {
    private val list = Blocklist.read(File(DIST, "tigil-blocklist.bin").inputStream())

    @Test fun loadsTheShippedList() {
        assertTrue("expected a real list, got ${list.size}", list.size > 300_000)
    }

    @Test fun blocksCuratedPhilippineOperators() {
        listOf("bingoplus.ph", "arenaplus.ph", "sw418.com", "jiliko.com",
               "1xbet.com", "wpit18.com").forEach {
            assertTrue("$it should be blocked", list.blocks(it))
        }
    }

    @Test fun blocksSubdomainsWithoutListingThem() {
        assertTrue(list.blocks("promo.cdn.bingoplus.ph"))
        assertTrue(list.blocks("m.sw418.com"))
    }

    @Test fun leavesOrdinarySitesAlone() {
        listOf("example.com", "github.com", "gcash.com", "doh.gov.ph",
               "wikipedia.org").forEach {
            assertFalse("$it should not be blocked", list.blocks(it))
        }
    }

    @Test fun neverTreatsABarePublicSuffixAsADomain() {
        // If "ph" or "com" were ever hashed as a candidate, one collision would
        // take down an entire TLD.
        assertFalse(list.blocks("ph"))
        assertFalse(list.blocks("com"))
    }
}

class RuleEngineTest {
    private val engine = RuleEngine.fromJson(
        File(DIST, "rules.json").readText(),
        Blocklist.read(File(DIST, "tigil-blocklist.bin").inputStream()),
    )

    @Test fun registrableDomainHandlesMultilabelSuffixes() {
        assertEquals("bet88.com.ph", RuleEngine.registrableDomain("www.bet88.com.ph"))
        assertEquals("jiliko.com", RuleEngine.registrableDomain("a.b.jiliko.com"))
        assertEquals("sw418.com", RuleEngine.registrableDomain("sw418.com"))
    }

    /**
     * The point of the whole product: domains that do NOT exist on any list,
     * shaped like the mirrors PH operators register every week.
     */
    @Test fun catchesUnlistedMirrorDomainsByShape() {
        listOf(
            "wpc2031.live",        // next year's World Pitmasters Cup mirror
            "jiliko99.net",        // JILI skin with a fresh number
            "sabong-tayo.xyz",
            "77win.co",
            "ph888.net",
            "slotgacor-maxwin.info",
            "betso99.org",
            "luckyspin.casino",    // .casino TLD rule
        ).forEach {
            assertEquals(
                "$it should be caught by a heuristic",
                Verdict.BLOCKED_KEYWORD, engine.evaluate(it))
        }
    }

    @Test fun doesNotOverblockLookalikeWords() {
        listOf(
            "diabetes.org", "alphabet.com", "tibet.net", "bethesda.net",
            "betterhelp.com", "github.com", "example.com", "wikipedia.org",
        ).forEach {
            assertEquals("$it must stay reachable", Verdict.ALLOWED, engine.evaluate(it))
        }
    }

    @Test fun allowlistBeatsEveryBlockRule() {
        // pagcor.ph matches the "pagcor" substring rule but is allowlisted so
        // users can reach the regulator's self-exclusion registry.
        assertEquals(Verdict.ALLOWED, engine.evaluate("pagcor.ph"))
        assertEquals(Verdict.ALLOWED, engine.evaluate("www.pagcor.ph"))
        // Help lines must never be collateral damage.
        assertEquals(Verdict.ALLOWED, engine.evaluate("gamblersanonymous.org"))
    }

    @Test fun userBlocklistIsHonoured() {
        engine.setUserLists(allow = emptySet(), block = setOf("somesite.test"))
        assertEquals(Verdict.BLOCKED_USER, engine.evaluate("shop.somesite.test"))
        assertTrue(engine.evaluate("shop.somesite.test").isBlocked)
    }

    @Test fun userAllowlistOverridesAListedDomain() {
        assertTrue(engine.evaluate("bingoplus.ph").isBlocked)
        engine.setUserLists(allow = setOf("bingoplus.ph"), block = emptySet())
        assertEquals(Verdict.ALLOWED, engine.evaluate("bingoplus.ph"))
    }
}

class DnsMessageTest {

    private fun query(name: String, id: Int = 0x1234, rd: Boolean = true): ByteArray {
        val labels = name.split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val packet = ByteArray(size)
        packet[0] = (id ushr 8).toByte(); packet[1] = id.toByte()
        val flags = if (rd) 0x0100 else 0x0000
        packet[2] = (flags ushr 8).toByte(); packet[3] = flags.toByte()
        packet[5] = 1                                     // QDCOUNT = 1
        var offset = 12
        labels.forEach { label ->
            packet[offset++] = label.length.toByte()
            label.forEach { packet[offset++] = it.code.toByte() }
        }
        packet[offset++] = 0
        packet[offset++] = 0; packet[offset++] = 1        // QTYPE  = A
        packet[offset++] = 0; packet[offset] = 1          // QCLASS = IN
        return packet
    }

    @Test fun readsTheQuestionName() {
        assertEquals("bingoplus.ph", DnsMessage.questionName(query("bingoplus.ph")))
        assertEquals("www.sw418.com", DnsMessage.questionName(query("WWW.SW418.COM")))
    }

    @Test fun rejectsMalformedAndNonqueryMessages() {
        assertNull(DnsMessage.questionName(ByteArray(4)))
        val response = query("example.com").also { it[2] = 0x80.toByte() }
        assertNull(DnsMessage.questionName(response))
        // A compression pointer in the question section is illegal; refusing to
        // follow it is what stops a crafted packet spinning the parser.
        val pointer = query("example.com").also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.questionName(pointer))
    }

    @Test fun nxdomainReplyKeepsIdAndQuestionSetsRcode3() {
        val request = query("jiliko.com", id = 0xBEEF)
        val reply = DnsMessage.nameError(request)

        assertEquals("question section must be echoed", request.size, reply.size)
        assertEquals(0xBE.toByte(), reply[0]); assertEquals(0xEF.toByte(), reply[1])

        val flags = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        assertTrue("QR must say 'response'", flags and 0x8000 != 0)
        assertTrue("RD must be echoed", flags and 0x0100 != 0)
        assertTrue("RA should be set", flags and 0x0080 != 0)
        assertEquals("RCODE must be NXDOMAIN", 3, flags and 0x000F)

        fun count(at: Int) = ((reply[at].toInt() and 0xFF) shl 8) or (reply[at + 1].toInt() and 0xFF)
        assertEquals("QDCOUNT", 1, count(4))
        assertEquals(0, count(6)); assertEquals(0, count(8)); assertEquals(0, count(10))
        // The question bytes themselves must survive untouched.
        assertTrue(request.copyOfRange(12, request.size)
            .contentEquals(reply.copyOfRange(12, reply.size)))
    }
}

class UdpDatagramTest {

    private fun ip(vararg octets: Int) = ByteArray(octets.size) { octets[it].toByte() }

    /** Ones-complement sum of a byte range; 0xFFFF means "checksum verifies". */
    private fun sum(bytes: ByteArray, from: Int, to: Int, seed: Long = 0): Long {
        var total = seed
        var index = from
        while (index + 1 < to) {
            total += ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < to) total += (bytes[index].toInt() and 0xFF) shl 8
        while (total shr 16 != 0L) total = (total and 0xFFFF) + (total shr 16)
        return total
    }

    private fun packet(payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val bytes = ByteArray(total)
        bytes[0] = 0x45; bytes[2] = (total ushr 8).toByte(); bytes[3] = total.toByte()
        bytes[8] = 64; bytes[9] = 17
        ip(10, 111, 222, 1).copyInto(bytes, 12)     // src 10.111.222.1
        ip(10, 111, 222, 2).copyInto(bytes, 16)     // dst 10.111.222.2
        bytes[20] = 0xC0.toByte(); bytes[21] = 0x01          // sport 49153
        bytes[22] = 0; bytes[23] = 53                        // dport 53
        val udpLen = 8 + payload.size
        bytes[24] = (udpLen ushr 8).toByte(); bytes[25] = udpLen.toByte()
        payload.copyInto(bytes, 28)
        return bytes
    }

    @Test fun parsesAnIpv4UdpDatagram() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val datagram = UdpDatagram.parse(packet(payload), 33)
        assertNotNull("a well-formed DNS packet must parse", datagram)
        datagram!!
        assertEquals(53, datagram.destinationPort)
        assertEquals(49153, datagram.sourcePort)
        assertTrue(payload.contentEquals(datagram.payload))
        assertTrue(ip(10, 111, 222, 2).contentEquals(datagram.destinationAddress))
    }

    @Test fun rejectsNonipv4NonudpAndFragmentedPackets() {
        assertNull(UdpDatagram.parse(ByteArray(8), 8))
        assertNull(UdpDatagram.parse(packet(ByteArray(4)).also { it[0] = 0x60 }, 32))
        assertNull(UdpDatagram.parse(packet(ByteArray(4)).also { it[9] = 6 }, 32))
        // More-fragments bit set
        assertNull(UdpDatagram.parse(packet(ByteArray(4)).also { it[6] = 0x20 }, 32))
    }

    @Test fun replySwapsEndpointsAndChecksumsVerify() {
        val request = UdpDatagram.parse(packet(byteArrayOf(9, 9, 9)), 31)!!
        val replyPayload = ByteArray(17) { it.toByte() }
        val reply = request.buildReply(replyPayload)

        assertEquals(20 + 8 + replyPayload.size, reply.size)
        // Endpoints reversed.
        assertTrue(ip(10, 111, 222, 2).contentEquals(reply.copyOfRange(12, 16)))
        assertTrue(ip(10, 111, 222, 1).contentEquals(reply.copyOfRange(16, 20)))
        assertEquals(53, ((reply[20].toInt() and 0xFF) shl 8) or (reply[21].toInt() and 0xFF))
        assertEquals(49153, ((reply[22].toInt() and 0xFF) shl 8) or (reply[23].toInt() and 0xFF))

        // A correct IPv4 header checksums to 0xFFFF over the whole header.
        assertEquals(0xFFFFL, sum(reply, 0, 20))

        // UDP: pseudo-header (addresses, protocol, length) + header + payload.
        var seed = 0L
        for (i in 12 until 20 step 2) {
            seed += ((reply[i].toInt() and 0xFF) shl 8) or (reply[i + 1].toInt() and 0xFF)
        }
        seed += 17L + (reply.size - 20)
        assertEquals(0xFFFFL, sum(reply, 20, reply.size, seed))

        assertTrue(replyPayload.contentEquals(reply.copyOfRange(28, reply.size)))
    }
}
