package io.github.felixchang67.diskandroid.core.video

import io.github.felixchang67.diskandroid.core.fs.ContentKind
import io.github.felixchang67.diskandroid.core.fs.DiscContent
import io.github.felixchang67.diskandroid.core.fs.DiscFileSystem

enum class VideoFormat(val label: String) {
    DVD_VIDEO("DVD-Video"),
    BDMV("BD-Video"),
    AVCHD("AVCHD"),
}

/** 给界面看的一个标题（DVD 的标题 / BD 的播放列表）。 */
class VideoTitle(
    /** "标题 3" / "00800.mpls"。 */
    val id: String,
    val durationMs: Long,
    val chapters: Int,
    val angles: Int,
    val video: String,
    val audio: List<StreamInfo>,
    val subtitles: List<StreamInfo>,
    val sizeBytes: Long?,
    val notes: List<String>,
    val dvd: DvdTitle? = null,
    val bd: BdPlaylist? = null,
)

class VideoDiscInfo(
    val format: VideoFormat,
    /** 按时长从长到短（主片在最前）。 */
    val titles: List<VideoTitle>,
    val main: VideoTitle?,
    /** 被当作重复 / 循环播放列表过滤掉的数量（BD）。 */
    val filtered: Int,
    val warnings: List<String>,
    val aacs: Boolean,
    val dvd: DvdVideoDisc? = null,
    val bd: BdDisc? = null,
) {
    /** 很短（不到 [SHORT_MS]）的标题：DVD 的版权警告、菜单背景之类。 */
    val shortTitles: Int get() = titles.count { it.durationMs < SHORT_MS }

    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        out += "视频结构：${format.label}，${titles.size} 个标题" + (if (filtered > 0) "（另有 $filtered 个重复 / 循环播放列表已过滤）" else "") +
            (main?.let { "，主片 ${it.id}（${formatDuration(it.durationMs)}）" } ?: "")
        bd?.indexVersion?.let { v ->
            out += "  index.bdmv 版本 %04d".format(v) + if (bd.uhd) "：Ultra HD Blu-ray（4K）" + (if (aacs) "，AACS 2.0 加密（不支持）" else "") else ""
        }
        for (t in titles.take(MAX_LOG_TITLES)) {
            out += "  ${if (t === main) "★" else " "} ${t.id}：${formatDuration(t.durationMs)}，${t.chapters} 章" +
                (if (t.angles > 1) "，${t.angles} 个角度" else "") + "，${t.video}" +
                (if (t.audio.isNotEmpty()) "；音轨 " + t.audio.joinToString("、") else "") +
                (if (t.subtitles.isNotEmpty()) "；字幕 " + t.subtitles.joinToString("、") { it.languageName + (it.note?.let { n -> "（$n）" } ?: "") } else "") +
                (if (t.notes.isNotEmpty()) "；" + t.notes.joinToString("；") else "")
        }
        if (titles.size > MAX_LOG_TITLES) out += "  ……另有 ${titles.size - MAX_LOG_TITLES} 个标题"
        warnings.forEach { out += "  ⚠ $it" }
        return out
    }

    companion object {
        const val SHORT_MS = 10_000L
        private const val MAX_LOG_TITLES = 30
    }
}

/** 读 DVD-Video 的 IFO 或 BD / AVCHD 的播放列表，整理成标题列表并挑出主片。 */
object VideoAnalyzer {
    suspend fun analyze(fs: DiscFileSystem, content: DiscContent): VideoDiscInfo? = when {
        ContentKind.BDMV in content.kinds -> bd(fs, "/BDMV", VideoFormat.BDMV, content.aacs)
        ContentKind.AVCHD in content.kinds -> bd(fs, "/PRIVATE/AVCHD/BDMV", VideoFormat.AVCHD, content.aacs)
        ContentKind.DVD_VIDEO in content.kinds -> dvd(fs)
        else -> null
    }

    suspend fun dvd(fs: DiscFileSystem): VideoDiscInfo {
        val disc = DvdIfo.read(fs)
        val titles = disc.titles.map { t ->
            val notes = mutableListOf<String>()
            if (!t.sequential) notes += "单元顺序被打乱（可能是结构保护用的假标题）"
            if (t.pgcs.size > 1) notes += "由 ${t.pgcs.size} 个节目链组成"
            VideoTitle(
                id = "标题 ${t.number}",
                durationMs = t.durationMs,
                chapters = t.chapters.size,
                angles = t.angles,
                video = t.video.describe() + (t.fps?.let { " %.2f".format(it) } ?: ""),
                audio = t.audio,
                subtitles = t.subtitles,
                sizeBytes = t.sectors * 2048,
                notes = notes,
                dvd = t,
            )
        }
        val sorted = titles.sortedWith(compareByDescending<VideoTitle> { it.dvd === disc.main }.thenByDescending { it.durationMs }.thenBy { it.dvd?.number })
        return VideoDiscInfo(VideoFormat.DVD_VIDEO, sorted, sorted.firstOrNull { it.dvd === disc.main }, 0, disc.warnings, false, dvd = disc)
    }

    suspend fun bd(fs: DiscFileSystem, root: String, format: VideoFormat, aacs: Boolean): VideoDiscInfo {
        val disc = BdmvReader.read(fs, root, avchd = format == VideoFormat.AVCHD)
        val titles = disc.titles.map { pl ->
            val notes = mutableListOf<String>()
            val clips = pl.clips.distinct()
            notes += if (clips.size == 1) "剪辑 ${clips[0]}" else "${clips.size} 段剪辑：" + clips.take(6).joinToString("、") + if (clips.size > 6) "…" else ""
            if (pl.subPaths > 0) notes += "${pl.subPaths} 条子路径"
            VideoTitle(
                id = pl.name,
                durationMs = pl.durationMs,
                chapters = pl.chapterCount,
                angles = pl.angles,
                video = pl.streams(BdStream.Kind.VIDEO).joinToString("、") { (it.codec + " " + it.info().detail).trim() }.ifEmpty { "没有视频" },
                audio = pl.streams(BdStream.Kind.AUDIO).map { it.info() },
                subtitles = pl.streams(BdStream.Kind.PG).map { it.info() },
                sizeBytes = disc.sizeOf(pl),
                notes = notes,
                bd = pl,
            )
        }
        val sorted = titles.sortedWith(compareByDescending<VideoTitle> { it.bd === disc.main }.thenByDescending { it.durationMs }.thenBy { it.id })
        return VideoDiscInfo(format, sorted, sorted.firstOrNull { it.bd === disc.main }, disc.filtered, disc.warnings, aacs, bd = disc)
    }
}
