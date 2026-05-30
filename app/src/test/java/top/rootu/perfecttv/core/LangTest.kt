package top.rootu.perfecttv.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** У каждой строки интерфейса (L.t / L.f в коде) есть английский перевод */
class LangTest {
    @Test fun every_string_has_english() {
        val root = File("src/main/java")
        val re = Regex("L\\.[tf]\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val missing = HashSet<String>()
        var found = 0
        root.walk().filter { it.extension == "kt" }.forEach { f ->
            for (m in re.findAll(f.readText())) {
                found++
                val key = m.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                if (key !in LangEn.MAP) missing.add(key)
            }
        }
        assertTrue("строк не найдено (не тот каталог?)", found > 200)
        assertTrue("нет перевода: $missing", missing.isEmpty())
    }
}
