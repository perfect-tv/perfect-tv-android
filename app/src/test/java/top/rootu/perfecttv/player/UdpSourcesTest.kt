package top.rootu.perfecttv.player

import org.junit.Assert.*
import org.junit.Test

class UdpSourcesTest {
    private fun rtp(flags: Int, seq: Int, payload: ByteArray, cc: Int = 0, ext: ByteArray? = null, pad: Int = 0): ByteArray {
        val h = ArrayList<Byte>()
        h.add((0x80 or flags or cc).toByte()); h.add(33); h.add((seq shr 8).toByte()); h.add(seq.toByte())
        repeat(8 + cc * 4) { h.add(0) }
        ext?.let { e -> h.add(0xBE.toByte()); h.add(0xDE.toByte()); h.add(0); h.add((e.size / 4).toByte()); e.forEach { h.add(it) } }
        payload.forEach { h.add(it) }
        if (pad > 0) { repeat(pad - 1) { h.add(0) }; h.add(pad.toByte()) }
        return h.toByteArray()
    }

    @Test fun payload() {
        val ts = ByteArray(188) { if (it == 0) 0x47 else 1 }
        val p = rtp(0, 1, ts)
        assertArrayEquals(intArrayOf(12, 188), UdpSources.rtpPayload(p, p.size))
        val c = rtp(0, 1, ts, cc = 2)
        assertArrayEquals(intArrayOf(20, 188), UdpSources.rtpPayload(c, c.size))
        val e = rtp(0x10, 1, ts, ext = ByteArray(8))
        assertArrayEquals(intArrayOf(24, 188), UdpSources.rtpPayload(e, e.size))
        val d = rtp(0x20, 1, ts, pad = 4)
        assertArrayEquals(intArrayOf(12, 188), UdpSources.rtpPayload(d, d.size))
    }

    @Test fun notRtp() {
        assertNull(UdpSources.rtpPayload(ByteArray(188) { 0x47 }, 188))   // голый TS
        assertNull(UdpSources.rtpPayload(ByteArray(8), 8))
        val empty = rtp(0, 1, ByteArray(0))
        assertNull(UdpSources.rtpPayload(empty, empty.size))
    }

    @Test fun sequence() {
        assertTrue(UdpSources.seqNewer(5, 4))
        assertFalse(UdpSources.seqNewer(4, 4))
        assertFalse(UdpSources.seqNewer(3, 4))
        assertTrue(UdpSources.seqNewer(0, 65535))
        assertFalse(UdpSources.seqNewer(65535, 0))
    }

    @Test fun schemes() {
        assertTrue(UdpSources.isUdp("udp://@239.1.1.1:1234"))
        assertTrue(UdpSources.isUdp("RTP://239.1.1.1:5004"))
        assertFalse(UdpSources.isUdp("http://x/udp://y"))
    }
}
