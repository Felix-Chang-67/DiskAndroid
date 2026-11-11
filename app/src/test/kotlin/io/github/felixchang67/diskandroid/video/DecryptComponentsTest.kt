package io.github.felixchang67.diskandroid.video

import androidx.test.core.app.ApplicationProvider
import io.github.felixchang67.diskandroid.DiskApp
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/** 在 KEYDB.cfg 里按盘 ID 找这张盘（KEYDB 里多是大写十六进制；文件按 4 MB 一块读，盘 ID 可能正好跨在两块之间）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecryptComponentsTest {
    @Test
    fun findsDiscIdsInKeydbAcrossChunks() = runTest {
        val app = ApplicationProvider.getApplicationContext<DiskApp>()
        val dir = File(app.filesDir, "aacs-config/aacs").apply { mkdirs() }
        val crossing = "9F3483F64D18A9FC0F67DD6D95AB08A8DD3F0ECF" // 跨 4 MB 边界
        val early = "0123456789ABCDEF0123456789ABCDEF01234567"
        val boundary = 4 shl 20
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write("; KEYDB.cfg test\n| PK | 0x00000000000000000000000000000000\n".toByteArray())
        bytes.write("0x$early = Early | D | 2020-01-01 | V | 0x00000000000000000000000000000000\n".toByteArray())
        // 补到"0x" 之后盘 ID 的第 21 个字符正好落在 4 MB 处
        val idStart = boundary - 20
        while (bytes.size() < idStart - 2 - 100) bytes.write(("; filler" + " ".repeat(91) + "\n").toByteArray())
        bytes.write(" ".repeat(idStart - 2 - bytes.size()).toByteArray())
        bytes.write("0x".toByteArray())
        assertEquals(idStart, bytes.size())
        bytes.write("$crossing = Crossing | V | 0x00000000000000000000000000000000\n".toByteArray())
        File(dir, DecryptComponents.KEYDB_NAME).writeBytes(bytes.toByteArray())
        val decrypt = DecryptComponents(app, app.container.log)
        assertEquals(true, decrypt.keydbHas(crossing.lowercase()))
        assertEquals(true, decrypt.keydbHas(early.lowercase()))
        assertEquals(false, decrypt.keydbHas("c762a73f9337a0cbf623b40eb3b2d7637e2e0f9c"))
        // 删掉 KEYDB 之后：不知道
        decrypt.remove(DecryptComponents.Kind.KEYDB)
        assertEquals(null, decrypt.keydbHas(crossing.lowercase()))
    }
}
