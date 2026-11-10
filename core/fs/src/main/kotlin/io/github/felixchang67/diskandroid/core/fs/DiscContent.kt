package io.github.felixchang67.diskandroid.core.fs

/** 按目录结构识别的盘片内容。 */
enum class ContentKind(val label: String, val video: Boolean) {
    DVD_VIDEO("DVD-Video", true),
    DVD_AUDIO("DVD-Audio", false),
    DVD_VR("DVD-VR（DVD 录像机录制）", true),
    BDMV("BD-Video（BDMV）", true),
    BDAV("BDAV（蓝光录像机录制）", true),
    AVCHD("AVCHD（摄像机拍摄）", true),
    VCD("Video CD", true),
    SVCD("Super Video CD", true),
}

/**
 * 识别结果。
 * @param aacs 根目录有 AACS 文件夹（BD 的视频是 AACS 加密的）
 */
class DiscContent(val kinds: List<ContentKind>, val aacs: Boolean, val notes: List<String>) {
    val isVideo: Boolean get() = kinds.any { it.video }
    val summary: String get() = kinds.joinToString("、") { it.label }.ifEmpty { "数据盘" } + if (aacs) "（AACS 加密）" else ""
}

/** 看根目录下的标准文件夹判断盘片内容（DVD-Video 的 VIDEO_TS、BD 的 BDMV 等），文件名不分大小写。 */
object ContentDetector {
    suspend fun detect(fs: DiscFileSystem): DiscContent {
        val root = fs.list(fs.root)
        val kinds = mutableListOf<ContentKind>()
        val notes = mutableListOf<String>()

        suspend fun dirHas(dir: String, vararg files: String): Boolean {
            val d = root.firstOrNull { it.isDirectory && it.name.equals(dir, ignoreCase = true) } ?: return false
            val names = runCatching { fs.list(d).map { it.name.uppercase() } }.getOrDefault(emptyList())
            return files.all { it.uppercase() in names }
        }
        fun hasDir(name: String) = root.any { it.isDirectory && it.name.equals(name, ignoreCase = true) }

        if (dirHas("VIDEO_TS", "VIDEO_TS.IFO")) kinds += ContentKind.DVD_VIDEO
        else if (hasDir("VIDEO_TS")) notes += "有 VIDEO_TS 文件夹，但没有 VIDEO_TS.IFO"
        if (dirHas("AUDIO_TS", "AUDIO_TS.IFO")) kinds += ContentKind.DVD_AUDIO
        if (dirHas("DVD_RTAV", "VR_MANGR.IFO")) kinds += ContentKind.DVD_VR
        if (dirHas("BDMV", "index.bdmv")) kinds += ContentKind.BDMV
        if (hasDir("BDAV")) kinds += ContentKind.BDAV
        val private = root.firstOrNull { it.isDirectory && it.name.equals("PRIVATE", ignoreCase = true) }
        if (private != null) {
            val avchd = runCatching { fs.find("/PRIVATE/AVCHD/BDMV") }.getOrNull()
            if (avchd != null && avchd.isDirectory) kinds += ContentKind.AVCHD
        }
        if (dirHas("VCD", "INFO.VCD")) kinds += ContentKind.VCD
        if (dirHas("SVCD", "INFO.SVD")) kinds += ContentKind.SVCD
        val aacs = hasDir("AACS")
        if (kinds.any { it == ContentKind.VCD || it == ContentKind.SVCD }) {
            notes += "Video CD 的视频在 Mode 2 Form 2 扇区里，要用 READ CD 读，暂不支持复制和播放"
        }
        return DiscContent(kinds, aacs, notes)
    }
}
