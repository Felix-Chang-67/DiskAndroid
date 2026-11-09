package io.github.felixchang67.diskandroid.core.meta

import java.io.File

/**
 * 本机缓存（App 私有目录）：识别结果按 MusicBrainz 光盘 ID 存一个 JSON；歌词按歌曲存。
 * 同一张盘再放进来时直接用缓存，不再联网；"没有歌词"也记下来，7 天内不重复查。
 */
class MetadataStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface CachedLyrics {
        class Found(val lyrics: Lyrics) : CachedLyrics
        data object None : CachedLyrics
    }

    private val discDir get() = File(dir, "discs").apply { mkdirs() }
    private val lyricDir get() = File(dir, "lyrics").apply { mkdirs() }

    fun load(discId: String): DiscMetadata? = runCatching { File(discDir, safe(discId) + ".json").takeIf { it.isFile }?.readText() }
        .getOrNull()?.let { DiscMetadata.fromJson(it) }

    fun save(meta: DiscMetadata) {
        writeAtomically(File(discDir, safe(meta.discId) + ".json"), meta.toJson())
    }

    fun delete(discId: String) {
        File(discDir, safe(discId) + ".json").delete()
    }

    fun loadLyrics(key: String): CachedLyrics? {
        val f = File(lyricDir, safe(key) + ".json")
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (text.startsWith(NONE_PREFIX)) {
            val at = text.removePrefix(NONE_PREFIX).trim().toLongOrNull() ?: 0
            return if (clock() - at < NONE_TTL_MS) CachedLyrics.None else null
        }
        return Lyrics.fromJson(text)?.let { CachedLyrics.Found(it) }
    }

    fun saveLyrics(key: String, lyrics: Lyrics?) {
        writeAtomically(File(lyricDir, safe(key) + ".json"), lyrics?.toJson() ?: (NONE_PREFIX + clock()))
    }

    /** 缓存占用（字节），给设置页显示。 */
    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun safe(key: String): String = key.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    companion object {
        private const val NONE_PREFIX = "none:"
        private const val NONE_TTL_MS = 7L * 24 * 3600 * 1000

        /** 歌词缓存的键：有网易云歌曲 ID 就用它，否则用"曲名|艺人|时长（秒）"。 */
        fun lyricsKey(neteaseSongId: Long?, title: String, artist: String?, durationMs: Long): String =
            neteaseSongId?.let { "ne-$it" } ?: ("t-" + Integer.toHexString("$title|${artist ?: ""}|${durationMs / 1000}".hashCode()))
    }
}
