package top.rootu.perfecttv.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Замер на настоящем файле программы: путь — в переменной окружения XMLTV_FILE (.xml или .gz).
 * Без неё тест пропускается. Нужные каналы — каждый 14-й (как ~300 из 4300 у iptvx.one).
 */
class XmltvRealTest {
    @Test fun realFile() {
        val path = System.getenv("XMLTV_FILE")
        assumeTrue(path != null && File(path).exists())
        val f = File(path!!)
        // распаковка отдельно
        var t0 = System.nanoTime()
        var raw = 0L
        Streams.open(FileInputStream(f)).use { s ->
            val buf = ByteArray(256 * 1024)
            while (true) { val r = s.read(buf); if (r < 0) break; raw += r }
        }
        val unzip = (System.nanoTime() - t0) / 1e9
        val ids = ArrayList<String>()
        var n = 0
        val wanted = HashSet<String>()
        t0 = System.nanoTime()
        val p = XmltvParser(listOf("ru"), { ids.add(it.id) }, { ids.forEachIndexed { i, id -> if (i % 14 == 0) wanted.add(id) } }, { it in wanted }, { n++ })
        p.parse(Streams.open(FileInputStream(f)))
        val all = (System.nanoTime() - t0) / 1e9
        println("REAL ${raw / 1048576} МБ XML: распаковка ${"%.1f".format(unzip)} с, распаковка+разбор ${"%.1f".format(all)} с " +
            "(${"%.0f".format(raw / 1048576 / all)} МБ/с), каналов ${p.channels}, нужных ${wanted.size}, передач $n, пропущено ${p.skipped}")
    }
}
