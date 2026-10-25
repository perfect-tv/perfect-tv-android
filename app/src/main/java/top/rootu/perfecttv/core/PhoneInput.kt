package top.rootu.perfecttv.core

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Ввод с телефона: телевизор поднимает на время открытого окна маленький HTTP-сервер в локальной сети и
 * показывает QR-код с его адресом. Телефон открывает страницу с полями (подписи и текущие значения),
 * нажатие «Отправить» присылает значения обратно ([onSubmit], в главном потоке). Адрес содержит случайный
 * ключ (в пути), остальные запросы отвечают 404; сервер закрывается через [stop] или через 10 минут.
 * Данные идут по Wi-Fi открытым текстом (HTTP) — в пределах домашней сети.
 */
class PhoneInput(
    private val title: String,
    private val fields: List<Field>,
    private val send: String,
    private val done: String,
    private val onSubmit: (Map<String, String>) -> Unit
) {
    /** Поле: [key] — имя, [label] — подпись, [value] — текущее значение, [url] — подсказать клавиатуре адрес */
    class Field(val key: String, val label: String, val value: String, val url: Boolean = false)

    private val token = ByteArray(9).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.take(12)
    private var server: ServerSocket? = null
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-input").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val closer = Runnable { stop() }

    /** Адрес страницы для телефона; null — сети нет (адрес телевизора не найден) */
    fun start(): String? {
        val ip = localAddress() ?: return null
        val s = try { ServerSocket(0) } catch (e: Exception) { return null }
        server = s
        pool.execute { serve(s) }
        main.postDelayed(closer, 10 * 60_000L)
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() {
        main.removeCallbacks(closer)
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdown()
    }

    private fun serve(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                s.accept().use { handle(it) }
            } catch (e: Exception) {
                if (s.isClosed) return
            }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 5000
        val inp = c.getInputStream().buffered()
        val line = readLine(inp) ?: return
        val parts = line.split(' ')
        if (parts.size < 2) return
        var length = 0
        while (true) {
            val h = readLine(inp) ?: return
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substring(15).trim().toIntOrNull() ?: 0
        }
        val path = parts[1].substringBefore('?')
        val out = c.getOutputStream()
        if (path != "/$token") return reply(out, 404, "text/plain", "Not found")
        when (parts[0]) {
            "GET" -> reply(out, 200, "text/html", page(false))
            "POST" -> {
                if (length !in 0..MAX_BODY) return reply(out, 413, "text/plain", "Too large")
                val body = ByteArray(length)
                var n = 0
                while (n < length) { val r = inp.read(body, n, length - n); if (r < 0) break; n += r }
                val values = HashMap<String, String>()
                for (pair in String(body, 0, n, Charsets.UTF_8).split('&')) {
                    val i = pair.indexOf('=')
                    if (i > 0) values[URLDecoder.decode(pair.substring(0, i), "UTF-8")] = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                }
                main.post { onSubmit(values) }
                reply(out, 200, "text/html", page(true))
            }
            else -> reply(out, 405, "text/plain", "Method not allowed")
        }
    }

    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (b.size() < 8192) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
        }
        return null
    }

    private fun reply(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val data = body.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $code OK\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(data)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(sent: Boolean): String {
        val sb = StringBuilder("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(title)).append("</title><style>")
        sb.append("body{font-family:sans-serif;margin:0;padding:16px;background:#111;color:#eee}h1{font-size:20px}label{display:block;margin-top:14px;font-size:14px;color:#aaa}")
        sb.append("input{width:100%;box-sizing:border-box;margin-top:4px;padding:12px;font-size:16px;border:0;border-radius:8px;background:#2a2c2f;color:#fff}")
        sb.append("button{margin-top:20px;width:100%;padding:14px;font-size:17px;border:0;border-radius:8px;background:#eee;color:#111}.ok{color:#7be07b;margin-top:16px}</style></head><body>")
        sb.append("<h1>").append(esc(title)).append("</h1>")
        if (sent) sb.append("<p class=ok>").append(esc(done)).append("</p>")
        sb.append("<form method=post>")
        for (f in fields) {
            sb.append("<label>").append(esc(f.label)).append("<input name=\"").append(esc(f.key)).append("\" value=\"").append(esc(f.value)).append("\"")
            sb.append(if (f.url) " type=url autocapitalize=off autocorrect=off" else " autocapitalize=off autocorrect=off").append("></label>")
        }
        sb.append("<button type=submit>").append(esc(send)).append("</button></form></body></html>")
        return sb.toString()
    }

    companion object {
        private const val MAX_BODY = 64 * 1024

        /** Адрес устройства в локальной сети (Wi-Fi / Ethernet), null — не найден */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }

        /** QR-код [text] как битмап: чёрные модули на белом, с тихой зоной, целое число пикселей на модуль, ≈[size] px */
        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
            val k = maxOf(1, size / m.width)
            val bm = Bitmap.createBitmap(m.width * k, m.height * k, Bitmap.Config.ARGB_8888)
            val px = IntArray(bm.width * bm.height)
            for (y in 0 until bm.height) for (x in 0 until bm.width) px[y * bm.width + x] = if (m.get(x / k, y / k)) Color.BLACK else Color.WHITE
            bm.setPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
            return bm
        }
    }
}
