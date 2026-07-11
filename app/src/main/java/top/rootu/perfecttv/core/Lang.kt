package top.rootu.perfecttv.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * Язык интерфейса и масштаб экрана. Строки — обычные ресурсы: `res/values` (английский,
 * по умолчанию) и `res/values-ru`. Язык — настройка «Язык» или системный (ru/uk/be/kk —
 * русский, иначе английский); на Android 13+ выбор передаётся и в системный язык приложения.
 *
 * Плотность подбирается так, чтобы высота экрана была 540 dp (1 em = 10 dp; настройка
 * «Размер интерфейса: крупный» — 480 dp, [Prefs.screenDp]):
 * разметка в XML и рисование на Canvas ([top.rootu.perfecttv.ui.U.em]) совпадают
 * на любом экране — ТВ 720p/1080p/4K, приставке с нестандартной плотностью, телефоне.
 */
object Lang {
    /** Ресурсы приложения с выбранным языком — для [str] вне Activity (уведомления, форматирование) */
    lateinit var res: Resources
        private set

    val locale: Locale get() = localeOf(res)

    /** Язык ресурсов (Configuration.locales — только с API 24) */
    fun localeOf(r: Resources): Locale = ConfigurationCompat.getLocales(r.configuration)[0] ?: Locale.ENGLISH
    val isEn get() = locale.language != "ru"

    private fun pick(base: Context): Locale = when (Prefs.language) {
        "ru" -> Locale("ru")
        "en" -> Locale.ENGLISH
        else -> {
            val sys = localeOf(base.resources)
            if (sys.language in listOf("ru", "uk", "be", "kk")) Locale("ru") else Locale.ENGLISH
        }
    }

    /** Контекст с выбранным языком и плотностью 540 dp по высоте (attachBaseContext Activity) */
    fun wrap(base: Context): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(pick(base))
        val dm = base.resources.displayMetrics
        val h = minOf(dm.heightPixels, dm.widthPixels * 9 / 16)
        if (h > 0) {
            cfg.densityDpi = maxOf(80, h * 160 / Prefs.screenDp)
            // размеры текста — от той же плотности (системный масштаб шрифта сохраняется)
        }
        return base.createConfigurationContext(cfg)
    }

    /** Применить язык (запуск приложения, смена в настройках) */
    fun init(app: Context) {
        res = wrap(app).resources
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = app.getSystemService(android.app.LocaleManager::class.java)
            val want = when (Prefs.language) {
                "" -> android.os.LocaleList.getEmptyLocaleList()
                else -> android.os.LocaleList.forLanguageTags(Prefs.language)
            }
            if (lm.applicationLocales != want) lm.applicationLocales = want
        }
    }
}

/** Строка из ресурсов на языке интерфейса (вне Activity; в View — то же, что context.getString) */
fun str(@StringRes id: Int): String = Lang.res.getString(id)

fun str(@StringRes id: Int, vararg args: Any?): String = Lang.res.getString(id, *args)

fun plural(@PluralsRes id: Int, n: Int, vararg args: Any?): String = Lang.res.getQuantityString(id, n, *args)
