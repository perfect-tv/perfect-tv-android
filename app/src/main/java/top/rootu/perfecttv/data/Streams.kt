package top.rootu.perfecttv.data

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Распаковка по сигнатуре: сервер не всегда честно говорит Content-Type/Encoding */
object Streams {

    class UnsupportedFormat(message: String) : IOException(message)

    /**
     * Обернуть поток распаковщиком: 1F 8B — gzip, PK — zip (первый файл, кроме каталогов),
     * FD 37 7A 58 5A — xz (не поддерживается). Иначе — как есть.
     * @param pick для zip — подходит ли имя файла (по умолчанию — любой)
     */
    fun open(raw: InputStream, pick: (String) -> Boolean = { true }): InputStream {
        val input = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 64 * 1024)
        input.mark(8)
        val sig = ByteArray(6)
        val n = readFully(input, sig)
        input.reset()
        if (n >= 2 && sig[0] == 0x1F.toByte() && sig[1] == 0x8B.toByte()) {
            // gzip бывает из нескольких членов подряд — GZIPInputStream читает их все
            return BufferedInputStream(GZIPInputStream(input, 64 * 1024), 64 * 1024)
        }
        if (n >= 4 && sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte() && sig[2] == 3.toByte() && sig[3] == 4.toByte()) {
            val zip = ZipInputStream(input)
            while (true) {
                val e = zip.nextEntry ?: throw UnsupportedFormat("zip: нет подходящего файла")
                if (!e.isDirectory && pick(e.name)) return BufferedInputStream(zip, 64 * 1024)
            }
        }
        if (n >= 6 && sig[0] == 0xFD.toByte() && sig[1] == '7'.code.toByte() && sig[2] == 'z'.code.toByte()) {
            throw UnsupportedFormat("xz не поддерживается")
        }
        return input
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /** Счётчик прочитанных байт сырого (сжатого) потока — для прогресса по Content-Length */
    class Counting(input: InputStream) : FilterInputStream(input) {
        @Volatile var bytes = 0L
            private set

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) bytes++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) bytes += r
            return r
        }

        override fun skip(n: Long): Long {
            val r = super.skip(n)
            bytes += r
            return r
        }
    }
}
