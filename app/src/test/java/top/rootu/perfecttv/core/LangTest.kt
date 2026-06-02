package top.rootu.perfecttv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/**
 * Ресурсы строк: у каждой строки, массива и plurals из `values` (английский) есть русский
 * вариант в `values-ru` и наоборот, с теми же подстановками (%1$s, %2$d…); в коде и разметке
 * не осталось литералов, которые просятся в ресурсы (L.t, android:text="…" без @string).
 */
class LangTest {
    private fun read(dir: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(File("src/main/res/$dir/strings.xml").reader())
        var name: String? = null
        var translatable = true
        val text = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "string", "string-array", "plurals" -> {
                        name = p.name + ":" + p.getAttributeValue(null, "name")
                        translatable = p.getAttributeValue(null, "translatable") != "false"
                        text.clear()
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> if (p.name in listOf("string", "string-array", "plurals")) {
                    if (translatable) out[name!!] = text.toString()
                    name = null
                }
            }
        }
        return out
    }

    private fun args(s: String) = Regex("%(\\d+)\\$[sd]").findAll(s).map { it.value }.toSortedSet()

    @Test fun every_string_has_russian_and_english() {
        val en = read("values")
        val ru = read("values-ru")
        assertTrue("строк не найдено", en.size > 200)
        assertEquals("нет в values-ru", emptySet<String>(), en.keys - ru.keys)
        assertEquals("нет в values", emptySet<String>(), ru.keys - en.keys)
        for ((k, v) in en) assertEquals("подстановки в $k", args(v), args(ru.getValue(k)))
    }

    @Test fun no_inline_strings_left() {
        val bad = ArrayList<String>()
        File("src/main/java").walk().filter { it.extension == "kt" }.forEach { f ->
            if (Regex("\\bL\\.[tf]\\(").containsMatchIn(f.readText())) bad.add(f.name)
        }
        File("src/main/res").walk().filter { it.extension == "xml" && it.parentFile.name.startsWith("layout") }.forEach { f ->
            Regex("android:(text|hint|contentDescription)=\"([^@\"][^\"]*)\"").findAll(f.readText()).forEach {
                if (it.groupValues[2].any { c -> c.isLetter() }) bad.add(f.name + ": " + it.value)
            }
        }
        assertTrue("строки не в ресурсах: $bad", bad.isEmpty())
    }
}
