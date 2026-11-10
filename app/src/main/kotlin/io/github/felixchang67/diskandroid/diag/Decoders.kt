package io.github.felixchang67.diskandroid.diag

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * 这台设备上播放 DVD / BD 要用到的解码器（MediaCodec）。N2.3 起播放视频盘时，设备没有的格式（常见的是 AC-3 / DTS）
 * 要用软件解码补上，这里先列出来、写进日志和诊断信息。LPCM 是未压缩的，不需要解码器。
 */
object Decoders {
    class Need(val key: String, val label: String, val mimes: List<String>)

    class Found(val need: Need, val decoders: List<String>) {
        val available: Boolean get() = decoders.isNotEmpty()
        fun describe(): String = "${need.label}：" + if (decoders.isEmpty()) "没有" else decoders.joinToString("、")
    }

    val needs = listOf(
        Need("mpeg2", "MPEG-2 视频", listOf("video/mpeg2")),
        Need("avc", "H.264/AVC 视频", listOf("video/avc")),
        Need("hevc", "H.265/HEVC 视频", listOf("video/hevc")),
        Need("vc1", "VC-1 视频", listOf("video/wvc1", "video/x-ms-wmv")),
        Need("mpega", "MPEG 音频（MP2）", listOf("audio/mpeg-L2", "audio/mpeg")),
        Need("ac3", "AC-3（杜比数字）", listOf("audio/ac3")),
        Need("eac3", "E-AC-3（杜比数字+）", listOf("audio/eac3")),
        Need("dts", "DTS", listOf("audio/vnd.dts")),
        Need("dtshd", "DTS-HD", listOf("audio/vnd.dts.hd")),
        Need("truehd", "Dolby TrueHD", listOf("audio/true-hd")),
    )

    @Volatile
    private var cached: List<Found>? = null

    /** 扫描一次（MediaCodecList 不会在运行中变化）。 */
    fun scan(): List<Found> = cached ?: runCatching {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && !it.isAlias }
        needs.map { need ->
            val names = infos.filter { info -> info.supportedTypes.any { t -> need.mimes.any { it.equals(t, ignoreCase = true) } } }
                .map { "${it.name}（${kind(it)}）" }
                .distinct()
            Found(need, names)
        }
    }.getOrElse { needs.map { Found(it, emptyList()) } }.also { cached = it }

    fun find(key: String): Found? = scan().firstOrNull { it.need.key == key }

    private fun kind(info: MediaCodecInfo): String = when {
        info.isHardwareAccelerated -> "硬件"
        info.isSoftwareOnly -> "软件"
        else -> "厂商"
    }

    fun lines(): List<String> = scan().map { it.describe() }

    /** 视频 / 音频编码名（core:video 的说法）→ 解码器表里的键。 */
    fun keyFor(codec: String): String? = when {
        codec.startsWith("MPEG-2") && !codec.contains("音频") -> "mpeg2"
        codec.startsWith("MPEG-1") && !codec.contains("音频") -> "mpeg2"
        codec.startsWith("H.264") -> "avc"
        codec.startsWith("H.265") -> "hevc"
        codec.startsWith("VC-1") -> "vc1"
        codec.startsWith("E-AC-3") -> "eac3"
        codec.startsWith("AC-3") -> "ac3"
        codec.startsWith("DTS-HD") -> "dtshd"
        codec == "DTS" -> "dts"
        codec.startsWith("Dolby TrueHD") -> "truehd"
        codec.startsWith("MPEG") -> "mpega"
        else -> null
    }
}
