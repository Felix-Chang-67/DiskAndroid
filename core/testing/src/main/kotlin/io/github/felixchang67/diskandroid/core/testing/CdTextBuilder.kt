package io.github.felixchang67.diskandroid.core.testing

import java.nio.charset.Charset

/**
 * 按 MMC-3 附录 J / libburn doc/cdtext.txt 的格式把文字编码成 CD-TEXT 数据包（测试与 FakeMmc 用）。
 * 规则：每块内按类型 80h…8Eh 排列，每种类型的文字依次是专辑（轨道 0）、第 first…last 轨，0 结尾（双字节两个 0），
 * 与上一轨相同的文字用 TAB 表示；切成 12 字节一包，包头写第一段文字的轨道号与字符位置；
 * 序号每块从 0 开始；块末尾三个 8Fh 包（块信息）；CRC = CRC-16-CCITT 取反、大端。
 */
class CdTextBuilder {
    class Block(
        val language: Int,
        val charCode: Int,
        val doubleByte: Boolean,
        val firstTrack: Int,
        val lastTrack: Int,
        /** 类型 → 文字列表（下标 0 = 专辑，1.. = 第 first.. 轨）。 */
        val texts: Map<Int, List<String?>>,
        val genreCode: Int? = null,
        val genreText: String = "",
        val copyrighted: Boolean = false,
        val useTabs: Boolean = true,
    )

    private val blocks = mutableListOf<Block>()

    fun block(b: Block) = apply { blocks += b }

    fun build(): ByteArray {
        val packsPerBlock = blocks.mapIndexed { index, b -> textPacks(index, b) }
        val lastSeq = packsPerBlock.map { it.size + 3 - 1 }
        val out = java.io.ByteArrayOutputStream()
        blocks.forEachIndexed { index, b ->
            val packs = packsPerBlock[index]
            packs.forEach { out.write(it) }
            sizePacks(index, b, packs, lastSeq).forEach { out.write(it) }
        }
        return out.toByteArray()
    }

    private fun charset(b: Block): Charset = when (b.charCode) {
        0x80 -> Charset.forName("windows-31j")
        else -> Charsets.ISO_8859_1
    }

    private fun textPacks(blockIndex: Int, b: Block): List<ByteArray> {
        val packs = mutableListOf<ByteArray>()
        var seq = 0
        val unit = if (b.doubleByte) 2 else 1
        for (type in (b.texts.keys + listOfNotNull(0x87.takeIf { b.genreCode != null })).sorted()) {
            // 拼出这一类型的完整字节流，同时记下每个字节属于哪一轨、是该轨文字的第几个字符
            val stream = java.io.ByteArrayOutputStream()
            val owner = mutableListOf<Pair<Int, Int>>()
            fun put(bytes: ByteArray, track: Int, startChar: Int) {
                bytes.forEachIndexed { i, v ->
                    stream.write(v.toInt())
                    owner += track to (startChar + i / unit)
                }
            }
            if (type == 0x87) {
                put(byteArrayOf((b.genreCode!! shr 8).toByte(), b.genreCode.toByte()), 0, 0)
                put(b.genreText.toByteArray(Charsets.ISO_8859_1) + ByteArray(1), 0, 0)
            } else {
                val list = b.texts.getValue(type)
                var previous: String? = null
                for (i in 0..(b.lastTrack - b.firstTrack + 1)) {
                    val track = if (i == 0) 0 else b.firstTrack + i - 1
                    val text = list.getOrNull(i) ?: ""
                    val body = if (b.useTabs && i >= 2 && text.isNotEmpty() && text == previous) {
                        ByteArray(unit) { 9 }
                    } else {
                        text.toByteArray(charset(b))
                    }
                    put(body + ByteArray(unit), track, 0)
                    previous = text
                }
            }
            val bytes = stream.toByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val p = ByteArray(18)
                p[0] = type.toByte()
                val (track, charPos) = owner[offset]
                p[1] = track.toByte()
                p[2] = seq++.toByte()
                p[3] = ((if (b.doubleByte) 0x80 else 0) or (blockIndex shl 4) or minOf(charPos, 15)).toByte()
                val n = minOf(12, bytes.size - offset)
                System.arraycopy(bytes, offset, p, 4, n)
                offset += n
                setCrc(p)
                packs += p
            }
        }
        return packs
    }

    private fun sizePacks(blockIndex: Int, b: Block, packs: List<ByteArray>, lastSeq: List<Int>): List<ByteArray> {
        val info = ByteArray(36)
        info[0] = b.charCode.toByte()
        info[1] = b.firstTrack.toByte()
        info[2] = b.lastTrack.toByte()
        info[3] = if (b.copyrighted) 3 else 0
        for (type in 0x80..0x8E) info[4 + type - 0x80] = packs.count { (it[0].toInt() and 0xFF) == type }.toByte()
        info[19] = 3
        lastSeq.forEachIndexed { i, s -> info[20 + i] = s.toByte() }
        blocks.forEachIndexed { i, other -> info[28 + i] = other.language.toByte() }
        val first = packs.size
        return (0..2).map { part ->
            val p = ByteArray(18)
            p[0] = 0x8F.toByte()
            p[1] = part.toByte()
            p[2] = (first + part).toByte()
            p[3] = (blockIndex shl 4).toByte()
            System.arraycopy(info, part * 12, p, 4, 12)
            setCrc(p)
            p
        }
    }

    companion object {
        fun crc(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0
            for (i in offset until offset + length) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                repeat(8) { crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 0xFFFF }
            }
            return crc xor 0xFFFF
        }

        fun setCrc(p: ByteArray) {
            val c = crc(p, 0, 16)
            p[16] = (c shr 8).toByte()
            p[17] = c.toByte()
        }

        /** READ TOC 格式 5 的完整响应：数据长度（不含自身）+ 2 个保留字节 + 数据包。 */
        fun response(packs: ByteArray): ByteArray {
            val len = packs.size + 2
            return byteArrayOf((len shr 8).toByte(), len.toByte(), 0, 0) + packs
        }

        /** 测试用的日文盘：块 0 日文（MS-JIS，双字节），块 1 英文（ISO-8859-1）。 */
        fun japaneseAlbum(): ByteArray = CdTextBuilder()
            .block(
                Block(
                    language = 0x69, charCode = 0x80, doubleByte = true, firstTrack = 1, lastTrack = 3,
                    texts = mapOf(
                        0x80 to listOf("テスト・アルバム", "春の歌", "夏の歌～ライブ", "秋の歌①"),
                        0x81 to listOf("山田太郎", "山田太郎", "山田太郎", "山田太郎＆花子"),
                    ),
                ),
            )
            .block(
                Block(
                    language = 0x09, charCode = 0x00, doubleByte = false, firstTrack = 1, lastTrack = 3,
                    texts = mapOf(
                        0x80 to listOf("Test Album", "Haru no Uta", "Natsu no Uta (Live)", "Aki no Uta"),
                        0x81 to listOf("Taro Yamada", "Taro Yamada", "Taro Yamada", "Taro & Hanako Yamada"),
                    ),
                    genreCode = 0x0014,
                    genreText = "J-Pop",
                ),
            )
            .build()
    }
}
