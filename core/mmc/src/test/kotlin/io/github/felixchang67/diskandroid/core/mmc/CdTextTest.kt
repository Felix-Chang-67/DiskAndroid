package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.testing.CdTextBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdTextTest {
    /** libburn doc/cdtext.txt 里 Sony 示例的真实数据包（含 CRC）。 */
    private val libburnPacks = listOf(
        "88 01 24 00 00 02 00 04 0b 19 08 02 32 0b 2f 3e 67 2d",
        "88 0d 27 00 35 18 19 39 03 19 00 00 00 00 00 00 ea af",
        "89 01 28 00 01 04 00 00 00 00 02 29 30 02 34 3a f3 0c",
        "89 06 29 00 02 04 00 00 00 00 17 0e 19 17 1d 3c 73 92",
        "8f 00 2a 00 01 01 03 00 06 05 04 05 07 06 01 02 48 65",
        "8f 01 2b 00 00 00 00 00 00 00 06 03 2c 00 00 00 c0 20",
        "8f 02 2c 00 00 00 00 00 09 00 00 00 00 00 00 00 11 45",
        "88 00 23 00 01 0e 00 3f 02 12 00 00 00 00 00 00 12 00",
    ).map { Hex.decode(it.replace(" ", "")) }

    @Test
    fun crcMatchesLibburnExamples() {
        for (p in libburnPacks) {
            val stored = ((p[16].toInt() and 0xFF) shl 8) or (p[17].toInt() and 0xFF)
            assertEquals(stored, CdText.crc(p, 0, 16), Hex.encode(p))
            assertEquals(stored, CdTextBuilder.crc(p, 0, 16))
        }
    }

    @Test
    fun blockSizeInfoFromLibburnExampleDecodes() {
        val packs = libburnPacks.filter { (it[0].toInt() and 0xFF) == 0x8F }.reduce { a, b -> a + b }
        val text = assertNotNull(CdText.parsePacks(packs))
        val block = text.blocks.single()
        assertEquals(CdText.CHARCODE_ASCII, block.charCode)
        assertEquals(1, block.firstTrack)
        assertEquals(3, block.lastTrack)
        assertEquals(CdText.LANGUAGE_ENGLISH, block.languageCode)
        assertFalse(block.copyrighted)
        assertTrue(block.hasSizeInfo)
        assertEquals(0, text.crcErrors)
    }

    @Test
    fun japaneseAlbumPrefersJapaneseBlockAndDecodesShiftJis() {
        val text = assertNotNull(CdText.parseResponse(CdTextBuilder.response(CdTextBuilder.japaneseAlbum())))
        assertEquals(2, text.blocks.size)
        assertEquals(0, text.crcErrors)
        assertFalse(text.crcAbsent)

        val ja = assertNotNull(text.preferred)
        assertEquals(0, ja.block)
        assertTrue(ja.isJapanese())
        assertTrue(ja.doubleByte)
        assertEquals("テスト・アルバム", text.title(0))
        assertEquals("春の歌", text.title(1))
        assertEquals("夏の歌～ライブ", text.title(2))
        assertEquals("秋の歌①", text.title(3)) // NEC 特殊字符，只有 windows-31j 能解
        assertEquals("山田太郎", text.performer(0))
        // 第 2 轨演出者在数据包里是双字节 TAB（与上一轨相同）
        assertEquals("山田太郎", text.performer(2))
        assertEquals("山田太郎＆花子", text.performer(3))

        val en = assertNotNull(text.secondary)
        assertEquals(CdText.LANGUAGE_ENGLISH, en.languageCode)
        assertEquals("Natsu no Uta (Live)", en.get(2, CdTextField.TITLE))
        assertEquals("Taro Yamada", en.get(2, CdTextField.PERFORMER)) // 单字节 TAB
        assertEquals(0x0014, en.genreCode)
        assertEquals("J-Pop", en.get(0, CdTextField.GENRE))
    }

    @Test
    fun corruptedPackIsDroppedWithoutShiftingOtherTitles() {
        val packs = CdTextBuilder.japaneseAlbum()
        // 找到第一个英文标题包（块 1，类型 80h），改坏一个字节
        val index = (0 until packs.size / 18).first { (packs[it * 18].toInt() and 0xFF) == 0x80 && ((packs[it * 18 + 3].toInt() shr 4) and 7) == 1 }
        packs[index * 18 + 6] = (packs[index * 18 + 6].toInt() xor 0x20).toByte()
        val text = assertNotNull(CdText.parsePacks(packs))
        assertEquals(1, text.crcErrors)
        val en = text.blocks.first { it.block == 1 }
        // 第一个包装着 "Test Album\0" 和第 1 轨标题的首字母：专辑标题与第 1 轨标题都不完整，丢弃；后面各轨不受影响
        assertNull(en.get(0, CdTextField.TITLE))
        assertNull(en.get(1, CdTextField.TITLE))
        assertEquals("Natsu no Uta (Live)", en.get(2, CdTextField.TITLE))
        assertEquals("Aki no Uta", en.get(3, CdTextField.TITLE))
        // 日文块完全不受影响
        assertEquals("夏の歌～ライブ", text.title(2))
    }

    @Test
    fun allZeroCrcMeansDriveDoesNotReportCrc() {
        val packs = CdTextBuilder.japaneseAlbum()
        for (i in 0 until packs.size / 18) {
            packs[i * 18 + 16] = 0
            packs[i * 18 + 17] = 0
        }
        val text = assertNotNull(CdText.parsePacks(packs))
        assertTrue(text.crcAbsent)
        assertEquals(0, text.crcErrors)
        assertEquals("春の歌", text.title(1))
    }

    @Test
    fun shiftJisLabelledAsIsoLatinIsDecodedAsJapanese() {
        // 构造器按 ISO-8859-1 编码字符串：把 Shift-JIS 字节先按 ISO-8859-1 变成字符串，编码后就是原样的 Shift-JIS 字节
        val sjis = "ありがとう".toByteArray(charset("windows-31j"))
        val fixed = CdTextBuilder()
            .block(
                CdTextBuilder.Block(
                    language = 0x00, charCode = 0x00, doubleByte = false, firstTrack = 1, lastTrack = 2,
                    texts = mapOf(0x80 to listOf("Best", "LOVE", String(sjis, Charsets.ISO_8859_1))),
                ),
            )
            .build()
        val text = assertNotNull(CdText.parsePacks(fixed))
        assertEquals("LOVE", text.title(1))
        assertEquals("ありがとう", text.title(2))
        assertEquals(1, text.blocks.single().sjisFallbacks)
    }

    @Test
    fun latinTextStaysLatin() {
        val packs = CdTextBuilder()
            .block(
                CdTextBuilder.Block(
                    language = 0x0F, charCode = 0x00, doubleByte = false, firstTrack = 1, lastTrack = 1,
                    texts = mapOf(0x80 to listOf("Café", "Déjà vu")),
                ),
            )
            .build()
        val text = assertNotNull(CdText.parsePacks(packs))
        assertEquals("Déjà vu", text.title(1))
        assertEquals(0, text.blocks.single().sjisFallbacks)
    }

    @Test
    fun headerOnlyResponseMeansNoCdText() {
        assertNull(CdText.parseResponse(byteArrayOf(0x00, 0x02, 0x00, 0x00)))
        assertNull(CdText.parseResponse(byteArrayOf(0x00)))
    }

    @Test
    fun readCddaCdbFollowsLibburnLayout() {
        val buffer = ByteArray(27 * MmcCommands.CDDA_SECTOR_SIZE)
        val cmd = MmcCommands.readCdda(lba = 0x0102_0304, blocks = 27, buffer = buffer, offset = 0, dap = true)
        assertEquals("BE060102030400001B100000", Hex.compact(cmd.cdb))
        assertEquals(27 * 2352, cmd.length)
        assertTrue(cmd.streaming)
        val noDap = MmcCommands.readCdda(lba = 150, blocks = 1, buffer = buffer, offset = 0, dap = false)
        assertEquals("BE0400000096000001100000", Hex.compact(noDap.cdb))
        assertEquals("43020500000000000400", Hex.compact(MmcCommands.readCdText(4).cdb))
        assertEquals("BB0002C2FFFF000000000000", Hex.compact(MmcCommands.setCdSpeed(706).cdb))
    }

    @Test
    fun mcnCommandAndParserFollowLinuxAndLibcdio() {
        assertEquals("42004002000000001800", Hex.compact(MmcCommands.readMcn().cdb))
        val resp = ByteArray(24)
        resp[1] = 0x15
        resp[3] = 20
        resp[4] = 0x02
        resp[8] = 0x80.toByte()
        "4547403043075".toByteArray().copyInto(resp, 9)
        assertEquals("4547403043075", SubChannel.parseMcn(resp))
        resp[8] = 0 // MCVAL = 0：没有 MCN
        assertNull(SubChannel.parseMcn(resp))
    }
}
