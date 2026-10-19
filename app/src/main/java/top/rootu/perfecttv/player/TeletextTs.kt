package top.rootu.perfecttv.player

import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.teletext.TTX_COLORS
import top.rootu.perfecttv.teletext.Ttx
import top.rootu.perfecttv.teletext.TtxDecoder
import top.rootu.perfecttv.teletext.TtxPage
import java.io.EOFException
import java.io.IOException

/**
 * Телетекст в HLS из MPEG-TS. ExoPlayer дорожку телетекста (PMT: тип 0x06, дескриптор 0x56)
 * не читает, поэтому TS-разборщик собирается со своей фабрикой читателей: дорожка идёт в
 * [TtxDecoder] (страницы для экрана телетекста), а страница субтитров — ещё и текстовой
 * дорожкой плеера (`application/x-media3-cues`), её время — по PTS пакета.
 */
@OptIn(UnstableApi::class)
class TeletextHlsExtractorFactory(
    private val payloadFlags: Int,
    private val decoder: TtxDecoder
) : HlsExtractorFactory {

    private val base = DefaultHlsExtractorFactory(payloadFlags, false)

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId
    ): HlsMediaChunkExtractor {
        val ts = tsExtractor(format, muxedCaptionFormats, timestampAdjuster)
        sniffingExtractorInput.resetPeekPosition()
        if (sniffQuietly(ts, sniffingExtractorInput)) {
            return BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
        }
        return base.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        base.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        base.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory {
        base.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = base.getOutputTextFormat(sourceFormat)

    // то же, что DefaultHlsExtractorFactory делает для TS (без разбора субтитров на лету), но с нашей фабрикой читателей
    private fun tsExtractor(format: Format, muxedCaptionFormats: List<Format>?, adjuster: TimestampAdjuster): TsExtractor {
        var flags = DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or payloadFlags
        val captions: List<Format>
        if (muxedCaptionFormats != null) {
            flags = flags or DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
            captions = muxedCaptionFormats
        } else captions = emptyList()
        val codecs = format.codecs
        if (!codecs.isNullOrEmpty()) {
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(codecs, MimeTypes.AUDIO_AAC)) flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(codecs, MimeTypes.VIDEO_H264)) flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
        }
        return TsExtractor(
            TsExtractor.MODE_HLS,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            adjuster,
            TeletextPayloadFactory(DefaultTsPayloadReaderFactory(flags, captions), decoder),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
        )
    }

    private fun sniffQuietly(e: Extractor, input: ExtractorInput): Boolean = try {
        e.sniff(input)
    } catch (_: EOFException) {
        false
    } catch (_: IOException) {
        false
    } finally {
        input.resetPeekPosition()
    }
}

@OptIn(UnstableApi::class)
private class TeletextPayloadFactory(
    private val inner: TsPayloadReader.Factory,
    private val decoder: TtxDecoder
) : TsPayloadReader.Factory {

    override fun createInitialPayloadReaders() = inner.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        // тип 6 (private data); у неизвестных дескрипторов TsExtractor подставляет -1
        if (streamType == TS_PRIVATE_DATA || streamType == TS_UNKNOWN) {
            val entries = descriptorEntries(esInfo.descriptorBytes)
            if (entries != null) {
                android.util.Log.i("IPTV", "teletext: дорожка в потоке, страницы ${entries.joinToString { "%X/%d".format(it.page, it.type) }}")
                return PesReader(TeletextReader(decoder, entries))
            }
        }
        return inner.createPayloadReader(streamType, esInfo)
    }

    /** Запись дескриптора телетекста: язык, тип (1 — начальная, 2 — субтитры, 5 — субтитры для слабослышащих), страница BCD */
    class Entry(val lang: String, val type: Int, val page: Int)

    companion object {
        private const val TS_PRIVATE_DATA = 6
        private const val TS_UNKNOWN = -1

        /** Записи дескриптора 0x56 (телетекст) из петли дескрипторов PMT; null — дорожка не телетекст */
        fun descriptorEntries(d: ByteArray): List<Entry>? {
            var i = 0
            while (i + 2 <= d.size) {
                val tag = d[i].toInt() and 0xFF
                val len = d[i + 1].toInt() and 0xFF
                if (tag == 0x56) {
                    val out = ArrayList<Entry>()
                    var j = i + 2
                    while (j + 5 <= i + 2 + len && j + 5 <= d.size) {
                        val lang = String(d, j, 3, Charsets.ISO_8859_1)
                        val tm = d[j + 3].toInt() and 0xFF
                        val mag = (tm and 7).let { if (it == 0) 8 else it }
                        out.add(Entry(lang, tm shr 3, (mag shl 8) or (d[j + 4].toInt() and 0xFF)))
                        j += 5
                    }
                    return out
                }
                i += 2 + len
            }
            return null
        }
    }
}

/**
 * Читатель дорожки телетекста: копит PES-пакет целиком, отдаёт его [TtxDecoder] и, если
 * готова страница субтитров, пишет её в текстовую дорожку.
 */
@OptIn(UnstableApi::class)
private class TeletextReader(
    private val decoder: TtxDecoder,
    entries: List<TeletextPayloadFactory.Entry>
) : ElementaryStreamReader {

    private var out: TrackOutput? = null
    private var buf = ByteArray(512)
    private var size = 0
    private var timeUs = C.TIME_UNSET
    private val encoder = CueEncoder()
    private val lang = entries.firstOrNull()?.lang?.takeIf { it.isNotBlank() } ?: C.LANGUAGE_UNDETERMINED

    init {
        decoder.declare(lang, entries.filter { it.type == 2 || it.type == 5 }.map { it.page })
    }

    override fun seek() {
        size = 0
        timeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        val o = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        o.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                // страница сменяет страницу, длительности у субтитров нет — показываются до следующей
                .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
                .setLanguage(lang)
                .setLabel(str(R.string.teletext))
                .build()
        )
        out = o
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        flush()
        timeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        val n = data.bytesLeft()
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        data.readBytes(buf, size, n)
        size += n
    }

    override fun packetFinished(isEndOfInput: Boolean) = flush()

    private fun flush() {
        if (size == 0) return
        val n = size
        size = 0
        decoder.feed(buf, 0, n, timeUs) { page, t -> subtitle(page, if (t == C.TIME_UNSET) timeUs else t) }
    }

    private fun subtitle(page: TtxPage, t: Long) {
        val o = out ?: return
        if (t == C.TIME_UNSET || !decoder.acceptSubtitle(page)) return
        decoder.recordSubtitle(t, page)
        var lines = Ttx.subtitleLines(Ttx.layout(page))
        // заглушка «ПРОГРАММА ТРАНСЛИРУЕТСЯ БЕЗ СУБТИТРОВ»: каналы шлют её, пока субтитров нет — не показывать
        if (lines.any { l -> l.joinToString("") { it.text }.contains("БЕЗ СУБТИТРОВ") }) lines = emptyList()
        if (lines.isEmpty() && decoder.subtitleEmpty) return  // уже пусто — второй раз не гасим
        decoder.subtitleEmpty = lines.isEmpty()
        val cues = if (lines.isEmpty()) emptyList() else listOf(cue(lines))
        val bytes = encoder.encode(cues, C.TIME_UNSET)
        o.sampleData(ParsableByteArray(bytes), bytes.size)
        o.sampleMetadata(t, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    private fun cue(lines: List<List<Ttx.Run>>): Cue {
        val sb = SpannableStringBuilder()
        for ((li, runs) in lines.withIndex()) {
            if (li > 0) sb.append('\n')
            for (r in runs) {
                val s = sb.length
                sb.append(r.text)
                sb.setSpan(ForegroundColorSpan(TTX_COLORS[r.fg and 7]), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // чёрный фон телетекста — полупрозрачная плашка, остальные цвета как есть
                sb.setSpan(BackgroundColorSpan(if (r.bg == 0) 0xCC000000.toInt() else TTX_COLORS[r.bg and 7]), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return Cue.Builder().setText(sb).build()
    }
}
