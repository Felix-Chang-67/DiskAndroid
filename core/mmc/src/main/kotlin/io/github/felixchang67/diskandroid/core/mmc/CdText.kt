package io.github.felixchang67.diskandroid.core.mmc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** CD-TEXT 字段（数据包类型 80h–87h、8Eh）。 */
enum class CdTextField(val packType: Int, val label: String) {
    TITLE(0x80, "标题"),
    PERFORMER(0x81, "演出者"),
    SONGWRITER(0x82, "作词"),
    COMPOSER(0x83, "作曲"),
    ARRANGER(0x84, "编曲"),
    MESSAGE(0x85, "附言"),
    DISC_ID(0x86, "盘片编号"),
    GENRE(0x87, "流派"),

    /** 8Eh：轨道 0 是 UPC/EAN（商品条码），其余轨道是 ISRC。 */
    UPC_ISRC(0x8E, "UPC/ISRC"),
    ;

    companion object {
        fun of(packType: Int): CdTextField? = entries.firstOrNull { it.packType == packType }
    }
}

/** 一个语言块（最多 8 个，每块一种语言）。[tracks] 的键 0 = 整张专辑。 */
class CdTextBlock(
    val block: Int,
    val charCode: Int,
    val languageCode: Int,
    val firstTrack: Int,
    val lastTrack: Int,
    val copyrighted: Boolean,
    val doubleByte: Boolean,
    val hasSizeInfo: Boolean,
    val genreCode: Int?,
    val tracks: Map<Int, Map<CdTextField, String>>,
    /** 字符集码标的是 ISO-8859-1/ASCII，但内容按 Shift-JIS 才能正确解码的字符串数（见 [CdText.decode]）。 */
    val sjisFallbacks: Int,
) {
    fun get(track: Int, field: CdTextField): String? = tracks[track]?.get(field)

    val albumTitle: String? get() = get(0, CdTextField.TITLE)
    val albumPerformer: String? get() = get(0, CdTextField.PERFORMER)

    fun charCodeName(): String = CdText.charCodeName(charCode)

    fun languageName(): String = CdText.languageName(languageCode)

    fun isJapanese(): Boolean = languageCode == CdText.LANGUAGE_JAPANESE || charCode == CdText.CHARCODE_MS_JIS
}

/**
 * 导入区 CD-TEXT（READ TOC 格式 0101b 的响应）。
 *
 * 格式依据 MMC-3 附录 J 与 libburn doc/cdtext.txt（Sony 文档的整理），解析流程参照 libcdio cdtext_data_init：
 * - 每个数据包 18 字节：[0] 类型，[1] 包内第一段文字所属的轨道号，[2] 序号，
 *   [3] bit7 = 双字节字符（DBCC），bit6–4 = 块号，bit3–0 = 字符位置；[4..15] 12 字节文字；[16..17] CRC。
 * - 文字以 0 结尾（双字节时以两个 0 结尾），可以跨包；每遇到一个结尾，轨道号加 1。
 * - 整串只有一个 TAB（双字节时两个 TAB）表示"与上一轨相同"。
 * - 8Fh 三个包（轨道号字段 0、1、2）组成 36 字节的块信息：字符集码、首末轨、版权、各类型包数、各块最后序号、各块语言码。
 * - CRC：除数 0x11021（CRC-16-CCITT），对前 16 字节计算后按位取反，大端存放。有的光驱把 CRC 全部置 0（libburn 也这样判断）。
 *
 * 与 libcdio 的一处不同：libcdio 用扫描到的最后一组 8Fh 包决定所有块的字符集，
 * 日文块（MS-JIS）+ 英文块（ISO-8859-1）的盘会把日文按 ISO-8859-1 解成乱码；这里每块用自己的 8Fh 包。
 */
class CdText(
    val blocks: List<CdTextBlock>,
    val packCount: Int,
    /** CRC 校验失败而丢弃的包数。 */
    val crcErrors: Int,
    /** 光驱返回的 CRC 全是 0：无法校验。 */
    val crcAbsent: Boolean,
) {
    /** 优先日文块（语言码 69h 或字符集 MS-JIS），其次英文，再其次第一个块。 */
    val preferred: CdTextBlock?
        get() = blocks.firstOrNull { it.languageCode == LANGUAGE_JAPANESE }
            ?: blocks.firstOrNull { it.charCode == CHARCODE_MS_JIS }
            ?: blocks.firstOrNull { it.languageCode == LANGUAGE_ENGLISH }
            ?: blocks.firstOrNull()

    /** 首选块之外的另一个块（例如日文盘附带的英文/罗马字块），用来显示副标题。 */
    val secondary: CdTextBlock? get() = preferred?.let { p -> blocks.firstOrNull { it !== p } }

    fun title(track: Int): String? = preferred?.get(track, CdTextField.TITLE) ?: blocks.firstNotNullOfOrNull { it.get(track, CdTextField.TITLE) }

    fun performer(track: Int): String? = preferred?.get(track, CdTextField.PERFORMER)
        ?: blocks.firstNotNullOfOrNull { it.get(track, CdTextField.PERFORMER) }

    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        out += "CD-TEXT：$packCount 个数据包，${blocks.size} 个语言块" +
            (if (crcAbsent) "，光驱未提供 CRC" else if (crcErrors > 0) "，⚠ $crcErrors 个包 CRC 错误已丢弃" else "，CRC 全部正确")
        for (b in blocks) {
            out += "  块 ${b.block}：${b.languageName()}，${b.charCodeName()}${if (b.doubleByte) "（双字节）" else ""}，轨道 ${b.firstTrack}–${b.lastTrack}" +
                (if (b.copyrighted) "，有版权标记" else "") + (if (!b.hasSizeInfo) "，⚠ 缺少块信息包" else "") +
                (if (b.sjisFallbacks > 0) "，${b.sjisFallbacks} 条按 Shift-JIS 解码" else "")
            for ((track, fields) in b.tracks.toSortedMap()) {
                val label = if (track == 0) "专辑" else "轨道 ${"%02d".format(track)}"
                out += "    $label：" + fields.entries.joinToString("；") { (f, v) -> "${f.label}=$v" }
            }
        }
        return out
    }

    companion object {
        const val PACK_SIZE = 18
        const val CHARCODE_ISO_8859_1 = 0x00
        const val CHARCODE_ASCII = 0x01
        const val CHARCODE_MS_JIS = 0x80
        const val CHARCODE_KOREAN = 0x81
        const val CHARCODE_MANDARIN = 0x82
        const val LANGUAGE_ENGLISH = 0x09
        const val LANGUAGE_JAPANESE = 0x69
        private const val MAX_STRING = 1024

        /** 解析 READ TOC 格式 5 的完整响应（含 4 字节头）。没有数据包时返回 null。 */
        fun parseResponse(data: ByteArray): CdText? {
            if (data.size < 4) return null
            val declared = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) + 2
            val end = minOf(declared, data.size)
            val count = (end - 4) / PACK_SIZE
            if (count <= 0) return null
            return parsePacks(data.copyOfRange(4, 4 + count * PACK_SIZE))
        }

        fun parsePacks(packs: ByteArray): CdText? {
            val count = packs.size / PACK_SIZE
            if (count == 0) return null
            val crcAbsent = (0 until count).all { packs[it * PACK_SIZE + 16].toInt() == 0 && packs[it * PACK_SIZE + 17].toInt() == 0 }
            val good = BooleanArray(count) { i -> crcAbsent || crcOk(packs, i * PACK_SIZE) }
            val crcErrors = good.count { !it }

            val byBlock = (0 until count).filter { good[it] }.groupBy { (packs[it * PACK_SIZE + 3].toInt() shr 4) and 0x07 }
            val blocks = byBlock.toSortedMap().map { (block, indices) -> parseBlock(packs, block, indices) }
                .filter { it.tracks.isNotEmpty() || it.hasSizeInfo }
            if (blocks.isEmpty()) return null
            return CdText(blocks, count, crcErrors, crcAbsent)
        }

        /** CRC-16-CCITT（多项式 1021h，初值 0，不反射），结果取反。与 libburn crc_11021 等价。 */
        fun crc(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0
            for (i in offset until offset + length) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                    crc = crc and 0xFFFF
                }
            }
            return crc xor 0xFFFF
        }

        private fun crcOk(packs: ByteArray, at: Int): Boolean {
            val stored = ((packs[at + 16].toInt() and 0xFF) shl 8) or (packs[at + 17].toInt() and 0xFF)
            return crc(packs, at, 16) == stored
        }

        private class SizeInfo(val bytes: ByteArray, val parts: Int) {
            val charCode get() = bytes[0].toInt() and 0xFF
            val firstTrack get() = bytes[1].toInt() and 0xFF
            val lastTrack get() = bytes[2].toInt() and 0xFF
            val copyright get() = bytes[3].toInt() and 0xFF
            fun language(block: Int) = bytes[28 + block].toInt() and 0xFF
        }

        private fun parseBlock(packs: ByteArray, block: Int, indices: List<Int>): CdTextBlock {
            // 块信息（8Fh）：轨道号字段 0/1/2 分别是 36 字节记录的第 0–11、12–23、24–35 字节
            val sizeBytes = ByteArray(36)
            var parts = 0
            for (i in indices) {
                val at = i * PACK_SIZE
                if (packs[at].toInt() and 0xFF != 0x8F) continue
                val part = packs[at + 1].toInt() and 0xFF
                if (part !in 0..2) continue
                System.arraycopy(packs, at + 4, sizeBytes, part * 12, 12)
                parts = parts or (1 shl part)
            }
            val size = if (parts == 0b111) SizeInfo(sizeBytes, parts) else null
            val doubleByte = indices.any { (packs[it * PACK_SIZE + 3].toInt() and 0x80) != 0 }
            val charCode = size?.charCode ?: if (doubleByte) CHARCODE_MS_JIS else CHARCODE_ISO_8859_1

            val tracks = sortedMapOf<Int, MutableMap<CdTextField, String>>()
            var genreCode: Int? = null
            var sjisFallbacks = 0

            val buffer = java.io.ByteArrayOutputStream()
            var previous: ByteArray? = null
            var previousType = -1
            var expectedSeq = -1
            var skipping = false

            for (i in indices) {
                val at = i * PACK_SIZE
                val type = packs[at].toInt() and 0xFF
                val field = CdTextField.of(type) ?: continue
                val seq = packs[at + 2].toInt() and 0xFF
                val db = (packs[at + 3].toInt() and 0x80) != 0
                val charPos = packs[at + 3].toInt() and 0x0F
                var track = packs[at + 1].toInt() and 0x7F

                if (type != previousType || seq != expectedSeq) {
                    // 新类型开始，或中间有包丢失（CRC 错误被丢弃）：缓冲区里没写完的文字作废；
                    // 字符位置不为 0 说明包开头的文字是从前面（丢失的）包延续来的，前半截没了，跳到下一个结尾为止
                    buffer.reset()
                    previous = null
                    skipping = charPos != 0
                }
                previousType = type
                expectedSeq = (seq + 1) and 0xFF

                var j = 0
                if (field == CdTextField.GENRE && buffer.size() == 0 && !skipping && (packs[at + 4].toInt() and 0xFF) <= 31) {
                    // 第一个流派包以 2 字节的流派码开头（libcdio 同样按"首字节不可打印"判断）
                    if (genreCode == null) genreCode = ((packs[at + 4].toInt() and 0xFF) shl 8) or (packs[at + 5].toInt() and 0xFF)
                    j = 2
                }
                val step = if (db) 2 else 1
                while (j + step <= 12) {
                    val b0 = packs[at + 4 + j].toInt() and 0xFF
                    val b1 = if (db) packs[at + 4 + j + 1].toInt() and 0xFF else 0
                    val terminator = b0 == 0 && (!db || b1 == 0)
                    if (!terminator) {
                        if (!skipping && buffer.size() < MAX_STRING) {
                            buffer.write(b0)
                            if (db) buffer.write(b1)
                        }
                    } else {
                        if (!skipping && buffer.size() > 0) {
                            val bytes = buffer.toByteArray()
                            val isTab = if (db) bytes.size == 2 && bytes[0].toInt() == 9 && bytes[1].toInt() == 9 else bytes.size == 1 && bytes[0].toInt() == 9
                            val value = if (isTab) previous else bytes
                            if (value != null && track in 0..99) {
                                val decoded = decode(value, field, charCode, track)
                                if (decoded.second) sjisFallbacks++
                                decoded.first.trim().takeIf { it.isNotEmpty() }?.let { tracks.getOrPut(track) { linkedMapOf() }[field] = it }
                            }
                            if (!isTab) previous = bytes
                        }
                        buffer.reset()
                        skipping = false
                        track++
                    }
                    j += step
                }
            }
            return CdTextBlock(
                block = block,
                charCode = charCode,
                languageCode = size?.language(block) ?: 0,
                firstTrack = size?.firstTrack ?: tracks.keys.filter { it > 0 }.minOrNull() ?: 0,
                lastTrack = size?.lastTrack ?: tracks.keys.maxOrNull() ?: 0,
                copyrighted = size?.let { (it.copyright and 0x03) == 0x03 } ?: false,
                doubleByte = doubleByte,
                hasSizeInfo = size != null,
                genreCode = genreCode,
                tracks = tracks,
                sjisFallbacks = sjisFallbacks,
            )
        }

        /**
         * 按块的字符集码解码。返回（文字，是否按 Shift-JIS 回退解码）。
         * - 00h ISO-8859-1；01h 声明为 ASCII，但有的盘里有 8 位字符，按 ISO-8859-1 解（与 libcdio 相同）。
         * - 80h MS-JIS：用 windows-31j（Shift-JIS 的 Windows 扩展，含 ①、Ⅱ 等 NEC 特殊字符），不可用时退回 Shift_JIS。
         * - 81h 韩文（KS C 5601）、82h 中文（GB 2312）：按 EUC 字节形式尽力解码。
         * - 流派文字、盘片编号、UPC/ISRC 规定为 ASCII / ISO-8859-1。
         * 日本盘兼容：字符集标成 ISO-8859-1/ASCII，但内容含 80h–9Fh（ISO-8859-1 里是控制字符，正常文字不会出现）
         * 且能按 Shift-JIS 完整解码时，按 Shift-JIS 解。
         */
        fun decode(bytes: ByteArray, field: CdTextField, charCode: Int, track: Int): Pair<String, Boolean> {
            val asciiField = field == CdTextField.GENRE || field == CdTextField.DISC_ID || field == CdTextField.UPC_ISRC
            if (asciiField) return String(bytes, Charsets.ISO_8859_1) to false
            return when (charCode) {
                CHARCODE_MS_JIS -> (strict(bytes, shiftJis) ?: String(bytes, shiftJis)) to false
                CHARCODE_KOREAN -> String(bytes, charset("EUC-KR") ?: Charsets.ISO_8859_1) to false
                CHARCODE_MANDARIN -> String(bytes, charset("GB2312") ?: Charsets.ISO_8859_1) to false
                else -> {
                    if (bytes.any { (it.toInt() and 0xFF) in 0x80..0x9F }) {
                        strict(bytes, shiftJis)?.let { return it to true }
                    }
                    String(bytes, Charsets.ISO_8859_1) to false
                }
            }
        }

        private fun charset(name: String): Charset? = runCatching { if (Charset.isSupported(name)) Charset.forName(name) else null }.getOrNull()

        /** windows-31j（MS932）优先；Android 与桌面 JDK 都应该有，保险起见逐个尝试。 */
        val shiftJis: Charset by lazy { charset("windows-31j") ?: charset("MS932") ?: charset("Shift_JIS") ?: Charsets.ISO_8859_1 }

        private fun strict(bytes: ByteArray, cs: Charset): String? = try {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }

        fun charCodeName(code: Int): String = when (code) {
            CHARCODE_ISO_8859_1 -> "ISO-8859-1"
            CHARCODE_ASCII -> "ASCII"
            CHARCODE_MS_JIS -> "MS-JIS（Shift-JIS，解码器 ${shiftJis.name()}）"
            CHARCODE_KOREAN -> "韩文 KS C 5601"
            CHARCODE_MANDARIN -> "中文 GB 2312"
            else -> "未知字符集 ${"%02X".format(code)}h"
        }

        /** 语言码（EBU Tech 3264 附录 3，libburn doc/cdtext.txt 列表）里常见的几种。 */
        fun languageName(code: Int): String = when (code) {
            0x00 -> "语言未标注"
            0x08 -> "德语"
            0x09 -> "英语"
            0x0A -> "西班牙语"
            0x0F -> "法语"
            0x15 -> "意大利语"
            0x1D -> "荷兰语"
            0x56 -> "俄语"
            0x65 -> "韩语"
            0x69 -> "日语"
            0x75 -> "中文"
            else -> "语言码 ${"%02X".format(code)}h"
        }
    }
}
