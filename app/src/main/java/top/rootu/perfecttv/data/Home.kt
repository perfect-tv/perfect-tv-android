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
 * Главный экран Android TV: ряд «Избранные» (канал-превью с избранными каналами) и «Продолжить
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

    /** Обновить ряды; звать из фонового потока. [last] — канал для «Продолжить просмотр» */
    fun update(context: Context, repo: Repo, favorites: List<Channel>, last: Channel?) {
        if (!Prefs.homeScreen) return remove(context)
        try {
            val helper = PreviewChannelHelper(context)
            publishFavorites(context, helper, repo, favorites)
            publishContinue(context, helper, repo, last)
            Log.i(TAG, "home screen: избранных ${favorites.size}, продолжить: ${last?.name}")
        } catch (e: Exception) {
            // нет провайдера ТВ или нет права — не ТВ-устройство; на главный экран не публикуем
            Log.w(TAG, "home screen: $e")
        }
    }

    /** Убрать свои карточки (настройка выключена) */
    fun remove(context: Context) {
        try {
            val helper = PreviewChannelHelper(context)
            val id = Prefs.homeChannelId
            if (id > 0) helper.deletePreviewChannel(id)
            Prefs.homeChannelId = 0
            Prefs.homeWatchNextId = 0
            context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "home screen remove: $e")
        }
    }

    private fun poster(context: Context, ch: Channel): Uri {
        val logo = ch.logo.ifEmpty { ch.epgIcon }
        return if (logo.startsWith("http")) Uri.parse(logo) else Uri.parse("android.resource://" + context.packageName + "/" + R.drawable.banner)
    }

    private fun nowTitle(repo: Repo, ch: Channel) = repo.at(ch, Clock.now())?.title ?: ch.group

    private fun publishFavorites(context: Context, helper: PreviewChannelHelper, repo: Repo, favorites: List<Channel>) {
        var id = Prefs.homeChannelId
        if (favorites.isEmpty()) {
            if (id > 0) helper.deletePreviewChannel(id)
            Prefs.homeChannelId = 0
            return
        }
        if (id <= 0) {
            val channel = PreviewChannel.Builder()
                .setDisplayName(context.getString(R.string.home_favorites))
                .setAppLinkIntent(Intent(Intent.ACTION_MAIN).setClassName(context, ACTIVITY))
                .setLogo(context.getDrawable(R.drawable.icon)!!.toBitmap(160, 160))
                .build()
            id = helper.publishDefaultChannel(channel)
            Prefs.homeChannelId = id
        }
        // карточки пересобираются целиком: набор избранного и текущие передачи меняются
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
        for (ch in favorites.take(MAX_FAVORITES)) {
            val p = PreviewProgram.Builder()
                .setChannelId(id)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setLive(true)
                .setTitle(ch.name)
                .setDescription(nowTitle(repo, ch))
                .setPosterArtUri(poster(context, ch))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntent(link(context, ch))
                .build()
            helper.publishPreviewProgram(p)
        }
    }

    private fun publishContinue(context: Context, helper: PreviewChannelHelper, repo: Repo, last: Channel?) {
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
            .setTitle(last.name)
            .setDescription(nowTitle(repo, last))
            .setPosterArtUri(poster(context, last))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntent(link(context, last))
            .build()
        Prefs.homeWatchNextId = helper.publishWatchNextProgram(p)
    }
}
