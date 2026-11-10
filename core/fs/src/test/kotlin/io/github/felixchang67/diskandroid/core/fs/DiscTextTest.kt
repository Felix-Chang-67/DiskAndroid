package io.github.felixchang67.diskandroid.core.fs

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscTextTest {
    private val sjis = charset("windows-31j")

    @Test
    fun asciiUtf8AndShiftJis() {
        assertEquals("DVD_VIDEO", DiscText.decode("DVD_VIDEO".toByteArray()))
        assertEquals("ライブ_LIVE", DiscText.decode("ライブ_LIVE".toByteArray(Charsets.UTF_8)))
        assertEquals("ライブ映像", DiscText.decode("ライブ映像".toByteArray(sjis)))
        // 只有汉字的 GBK（没有 80h–9Fh 字节，也没有假名）：靠"像常用汉字"的得分认出来
        assertEquals("演唱会资料", DiscText.decode("演唱会资料".toByteArray(charset("GBK"))))
        // EUC-JP 的假名和 GBK 编码相同，结果一样
        assertEquals("ライブ", DiscText.decode("ライブ".toByteArray(charset("EUC-JP"))))
    }

    /** N2.1 真机：DVDFab（中文 Windows）把卷标按 GBK 写进 ISO 9660 和 UDF。 */
    @Test
    fun gbkLabelsWrittenByDvdFab() {
        val kana = byteArrayOf(0xA5.toByte(), 0xD2.toByte(), 0xA5.toByte(), 0xC8.toByte(), 0xA5.toByte(), 0xEA.toByte(), 0xA5.toByte(), 0xA8.toByte())
        // ISO 9660 主卷描述符的 32 字节卷标（原始字节照抄日志）
        val iso = kana + " LIVE AT LIQUIDROOM EXTR".toByteArray()
        assertEquals(32, iso.size)
        assertEquals("ヒトリエ LIVE AT LIQUIDROOM EXTR", DiscText.field(iso, 0, 32))
        // UDF 逻辑卷标识：压缩 ID 8（按规范应是 Latin-1），实际放的是 GBK 字节。以前显示成「¥Ò¥È¥ê¥¨」
        val lvid = ByteArray(128)
        val body = byteArrayOf(8) + kana + " LIVE at LIQUIDROOM Extra Video".toByteArray()
        body.copyInto(lvid)
        lvid[127] = body.size.toByte()
        assertEquals("ヒトリエ LIVE at LIQUIDROOM Extra Video", DiscText.dstring(lvid, 0, 128))
        // 32 字节的 UDF 卷标识被截短
        val vid = ByteArray(32)
        body.copyInto(vid, 0, 0, 31)
        vid[31] = 31
        assertEquals("ヒトリエ LIVE at LIQUIDROOM Ex", DiscText.dstring(vid, 0, 32))
    }

    @Test
    fun westernLatin1StaysLatin1() {
        val cafe = "Café".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Café", DiscText.decode(cafe))
        assertEquals("AÇÃO", DiscText.decode("AÇÃO".toByteArray(Charsets.ISO_8859_1)))
        val udf = ByteArray(32)
        val body = byteArrayOf(8) + "Música Café".toByteArray(Charsets.ISO_8859_1)
        body.copyInto(udf)
        udf[31] = body.size.toByte()
        assertEquals("Música Café", DiscText.dstring(udf, 0, 32))
        // Shift-JIS 放在 UDF 8 位字符串里（日本的 Windows 软件）
        val sj = ByteArray(32)
        val sjBody = byteArrayOf(8) + "ライブ映像".toByteArray(sjis)
        sjBody.copyInto(sj)
        sj[31] = sjBody.size.toByte()
        assertEquals("ライブ映像", DiscText.dstring(sj, 0, 32))
    }

    @Test
    fun labelCutInTheMiddleOfAMultiByteCharacter() {
        // 32 字节的卷标字段把最后一个 UTF-8 字符截成两半：丢掉残缺的尾巴，不要整体当成乱码
        val utf8 = "LIVE_AT_LIQUIDROOM_ライブ映像".toByteArray(Charsets.UTF_8)
        assertEquals("LIVE_AT_LIQUIDROOM_ライブ映", DiscText.decode(utf8.copyOf(32)))
        val s = "ライブ映像ライブ映像ライブ映像ライブ映像".toByteArray(sjis)
        assertEquals("ライブ映像ライブ映像ライブ映像ラ", DiscText.decode(s.copyOf(33)))
    }

    @Test
    fun fieldTrimsPaddingAndJolietIsUtf16() {
        val b = ByteArray(32) { 0x20 }
        "ABC".toByteArray().copyInto(b)
        assertEquals("ABC", DiscText.field(b, 0, 32))
        val j = "ライブ".toByteArray(Charsets.UTF_16BE) + ByteArray(26) { if (it % 2 == 0) 0 else 0x20 }
        assertEquals("ライブ", DiscText.ucs2(j, 0, 32))
    }

    @Test
    fun udfDstrings() {
        val b = ByteArray(32)
        val cs0 = byteArrayOf(16) + "ブルーレイ".toByteArray(Charsets.UTF_16BE)
        cs0.copyInto(b)
        b[31] = cs0.size.toByte()
        assertEquals("ブルーレイ", DiscText.dstring(b, 0, 32))
        val latin = ByteArray(32)
        (byteArrayOf(8) + "LinuxUDF".toByteArray()).copyInto(latin)
        latin[31] = 9
        assertEquals("LinuxUDF", DiscText.dstring(latin, 0, 32))
        assertEquals("", DiscText.dstring(ByteArray(32), 0, 32))
    }

    @Test
    fun safeFileNames() {
        assertEquals("ライブ_LIVE", DiscText.safeFileName("ライブ_LIVE"))
        assertEquals("a_b_c", DiscText.safeFileName("a/b:c"))
        assertEquals("DISC", DiscText.safeFileName("  "))
    }
}
