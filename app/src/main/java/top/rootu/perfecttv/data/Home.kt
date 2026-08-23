package top.rootu.perfecttv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs

/**
 * Главный экран Android TV: ряды «Популярные» (по умолчанию) и «Избранные» (каналы-превью) и «Продолжить
 * просмотр» (последний канал). Карточка запускает приложение глубокой ссылкой
 * `perfecttv://channel?p=<плейлист>&k=<ключ канала>` ([link], [parse]). Обновляется, когда
 * приложение уходит с экрана. На устройствах без провайдера ТВ (телефон) ничего не делает.
 */
object Home {
    private const val TAG = "IPTV"
    private const val SCHEME = "perfecttv"
    private const val MAX_FAVORITES = 12
    const val ACTIVITY = "top.rootu.perfecttv.ui.TvActivity"

    fun link(context: Context, ch: Channel): Intent {
        val uri = Uri.Builder().scheme(SCHEME).authority("channel")
            .appendQueryParameter("p", ch.playlistId.toString()).appendQueryParameter("k", ch.key).build()
        return Intent(Intent.ACTION_VIEW, uri).setClassName(context, ACTIVITY)
    }

    /** Канал из глубокой ссылки: (плейлист, ключ) или null, если это не наша ссылка */
    fun parse(uri: Uri?): Pair<Long, String>? {
        if (uri == null || uri.scheme != SCHEME || uri.host != "channel") return null
        val key = uri.getQueryParameter("k") ?: return null
        return (uri.getQueryParameter("p")?.toLongOrNull() ?: 0L) to key
    }

    /** Канал и его передача сейчас (определяется в главном потоке: кэш программы не потокобезопасен) */
    class Row(val channel: Channel, val programme: Programme?)

    /** Обновить ряды; звать из фонового потока. [last] — канал для «Продолжить просмотр» */
    fun update(context: Context, favorites: List<Row>, popular: List<Row>, last: Row?) {
        if (!Prefs.homeScreen) return remove(context)
        try {
            val helper = PreviewChannelHelper(context)
            tmdbUntil = System.currentTimeMillis() + TMDB_TOTAL
            withArt = 0
            Posters.artFailures = 0
            // раньше по умолчанию был ряд «Избранные»: канал по умолчанию не переназначить — пересоздаём оба
            if (Prefs.homeLayout < 2) {
                for (id in listOf(Prefs.homeChannelId, Prefs.homePopularId)) if (id > 0) helper.deletePreviewChannel(id)
                Prefs.homeChannelId = 0
                Prefs.homePopularId = 0
                Prefs.homeLayout = 2
            }
            // «Популярные» — канал по умолчанию (виден на главном экране сразу, работает без настройки);
            // «Избранные» — второй, его пользователь включает в «Настроить каналы» главного экрана
            publishRow(context, helper, R.string.home_popular, popular, true, { Prefs.homePopularId }, { Prefs.homePopularId = it })
            publishRow(context, helper, R.string.home_favorites, favorites, false, { Prefs.homeChannelId }, { Prefs.homeChannelId = it })
            publishContinue(context, helper, last)
            Posters.cleanup(context, posters)
            posters.clear()
            Log.i(TAG, "home screen: избранных ${favorites.size}, популярных ${popular.size}, продолжить: ${last?.channel?.name}, баннеров с картинкой передачи: $withArt")
            withArt = 0
        } catch (e: Exception) {
            // нет провайдера ТВ или нет права — не ТВ-устройство; на главный экран не публикуем
            Log.w(TAG, "home screen: $e")
        }
    }

    /** Убрать свои карточки (настройка выключена) */
    fun remove(context: Context) {
        try {
            val helper = PreviewChannelHelper(context)
            for (id in listOf(Prefs.homeChannelId, Prefs.homePopularId)) if (id > 0) helper.deletePreviewChannel(id)
            Prefs.homeChannelId = 0
            Prefs.homePopularId = 0
            Prefs.homeWatchNextId = 0
            context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
            Posters.clear(context)   // карточек нет — баннеры не нужны
        } catch (e: Exception) {
            Log.w(TAG, "home screen remove: $e")
        }
    }

    /** Имена баннеров, использованных в этом обновлении (остальные файлы удаляются) */
    private val posters = HashSet<String>()

    /** Сколько ждать TMDB на одну передачу и на всё обновление, мс (картинка — по возможности) */
    private const val TMDB_WAIT = 4000L
    private const val TMDB_TOTAL = 30_000L
    private var tmdbUntil = 0L

    /** Картинка передачи: TMDB (если включён и успел ответить), иначе EPG; пусто — рисуем без неё */
    private fun artUrl(p: Programme?): String {
        if (p == null) return ""
        var url = p.icon
        if (Prefs.tmdbKey.isNotBlank() && System.currentTimeMillis() < tmdbUntil) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var found = ""
            top.rootu.perfecttv.core.Tmdb.find(p.title, p.desc) { r ->
                if (r != null) found = r.backdrop.ifEmpty { r.poster }
                latch.countDown()
            }
            latch.await(TMDB_WAIT, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (found.isNotEmpty()) url = found
        }
        if (url.isNotEmpty()) withArt++
        return url
    }

    private var withArt = 0

    /** Баннер карточки: свой; не вышло — баннер приложения */
    private fun poster(context: Context, row: Row): Uri {
        val made = Posters.make(context, row.channel, row.programme, artUrl(row.programme)) ?: Posters.appBanner(context)
        made?.let { posters.add(it.name); return it.uri }
        return Uri.parse("android.resource://" + context.packageName + "/" + R.drawable.banner)
    }

    private fun nowTitle(row: Row) = row.programme?.title ?: row.channel.group

    private fun publishRow(context: Context, helper: PreviewChannelHelper, title: Int, list: List<Row>, default: Boolean,
                           getId: () -> Long, setId: (Long) -> Unit) {
        var id = getId()
        if (list.isEmpty()) {
            if (id > 0) helper.deletePreviewChannel(id)
            setId(0)
            return
        }
        if (id <= 0) {
            val channel = PreviewChannel.Builder()
                .setDisplayName(context.getString(title))
                .setAppLinkIntent(Intent(Intent.ACTION_MAIN).setClassName(context, ACTIVITY))
                .setLogo(context.getDrawable(R.drawable.icon)!!.toBitmap(160, 160))
                .build()
            id = if (default) helper.publishDefaultChannel(channel) else helper.publishChannel(channel)
            setId(id)
        }
        // карточки пересобираются целиком: набор каналов и текущие передачи меняются
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
        for (row in list.take(MAX_FAVORITES)) {
            val ch = row.channel
            val p = PreviewProgram.Builder()
                .setChannelId(id)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setLive(true)
                .setTitle(ch.name)
                .setDescription(nowTitle(row))
                .setPosterArtUri(poster(context, row))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntent(link(context, ch))
                .build()
            helper.publishPreviewProgram(p)
        }
    }

    private fun publishContinue(context: Context, helper: PreviewChannelHelper, last: Row?) {
        val old = Prefs.homeWatchNextId
        if (old > 0) {
            try { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(old), null, null) } catch (e: Exception) { Log.w(TAG, "watch next delete: $e") }
            Prefs.homeWatchNextId = 0
        }
        if (last == null) return
        val p = WatchNextProgram.Builder()
            .setType(TvContractCompat.WatchNextPrograms.TYPE_CLIP)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(Clock.now() * 1000)
            .setLive(true)
            .setTitle(last.channel.name)
            .setDescription(nowTitle(last))
            .setPosterArtUri(poster(context, last))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntent(link(context, last.channel))
            .build()
        Prefs.homeWatchNextId = helper.publishWatchNextProgram(p)
    }
}
