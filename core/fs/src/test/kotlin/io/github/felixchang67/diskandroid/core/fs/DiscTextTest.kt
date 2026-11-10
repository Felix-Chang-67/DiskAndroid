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
        // GBK 文件名：Shift-JIS 严格解码失败时才轮到 GBK（两者都能解开时按日本盘优先用 Shift-JIS）
        val gbk = "演唱会资料".toByteArray(charset("GBK"))
        val decoded = DiscText.decode(gbk)
        if (runCatching { charset("windows-31j").newDecoder().decode(java.nio.ByteBuffer.wrap(gbk)) }.isFailure) assertEquals("演唱会资料", decoded)
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
