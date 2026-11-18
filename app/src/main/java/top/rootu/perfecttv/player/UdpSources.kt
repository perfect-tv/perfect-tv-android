package top.rootu.perfecttv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.UdpDataSource

/**
 * Источники данных для каналов `udp://` и `rtp://` (мультикаст или юникаст, обычно MPEG-TS):
 * `UdpDataSource` Media3 сам вступает в группу мультикаста, если адрес групповой. Обычная фабрика OkHttp
 * этих схем не открывает, поэтому [Factory] выбирает источник по схеме ссылки.
 */
object UdpSources {
    fun isUdp(url: String) = url.startsWith("udp://", true) || url.startsWith("rtp://", true)

    /** Схема udp / rtp — свои источники, остальное — [http] */
    class Factory(private val http: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Switch(http.createDataSource())
    }

    private class Switch(private val http: DataSource) : DataSource {
        private var cur: DataSource = http
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(l: TransferListener) { listeners.add(l); http.addTransferListener(l) }

        override fun open(dataSpec: DataSpec): Long {
            cur = when (dataSpec.uri.scheme?.lowercase()) {
                "udp" -> newUdp()
                "rtp" -> RtpDataSource(newUdp())
                else -> http
            }
            if (cur !== http) listeners.forEach { cur.addTransferListener(it) }
            return cur.open(dataSpec)
        }

        private fun newUdp() = UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, 8000)
        override fun read(buffer: ByteArray, offset: Int, length: Int) = cur.read(buffer, offset, length)
        override fun getUri(): Uri? = cur.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur.responseHeaders
        override fun close() { cur.close() }
    }

    /** Полезная нагрузка RTP-пакета: (смещение, длина) или null, если это не RTP версии 2 или пакет пуст */
    fun rtpPayload(p: ByteArray, len: Int): IntArray? {
        if (len < 12 || (p[0].toInt() and 0xC0) != 0x80) return null
        val cc = p[0].toInt() and 0x0F
        var off = 12 + cc * 4
        if ((p[0].toInt() and 0x10) != 0) {     // расширение: 2 байта профиль, 2 байта длина в словах
            if (off + 4 > len) return null
            off += 4 + (((p[off + 2].toInt() and 0xFF) shl 8) or (p[off + 3].toInt() and 0xFF)) * 4
        }
        var end = len
        if ((p[0].toInt() and 0x20) != 0 && len > 0) end -= p[len - 1].toInt() and 0xFF   // добивка
        return if (off < end) intArrayOf(off, end - off) else null
    }

    /** 16-битные порядковые номера RTP: пакет [b] новее [a] (с переходом через 65535) */
    fun seqNewer(b: Int, a: Int) = ((b - a) and 0xFFFF).let { it != 0 && it < 0x8000 }

    /**
     * RTP поверх UDP: снимает заголовок, отбрасывает дубли и опоздавшие пакеты (порядок не восстанавливает —
     * потери и перестановки в домашней сети редки, MPEG-TS их переживёт). Берёт и RTCP-подобные пакеты (не RTP
     * версии 2) — пропускает.
     */
    class RtpDataSource(private val udp: UdpDataSource) : DataSource {
        private val packet = ByteArray(UdpDataSource.DEFAULT_MAX_PACKET_SIZE)
        private var pos = 0
        private var end = 0
        private var lastSeq = -1

        override fun addTransferListener(l: TransferListener) = udp.addTransferListener(l)
        override fun open(dataSpec: DataSpec): Long {
            pos = 0; end = 0; lastSeq = -1
            udp.open(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (pos >= end) {
                val n = udp.read(packet, 0, packet.size)
                if (n == C.RESULT_END_OF_INPUT) return n
                val pl = rtpPayload(packet, n) ?: continue
                val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
                if (lastSeq >= 0 && !seqNewer(seq, lastSeq)) continue
                lastSeq = seq
                pos = pl[0]; end = pl[0] + pl[1]
            }
            val c = minOf(length, end - pos)
            System.arraycopy(packet, pos, buffer, offset, c)
            pos += c
            return c
        }

        override fun getUri(): Uri? = udp.uri
        override fun close() { udp.close() }
    }
}
