package io.github.felixchang67.diskandroid.core.fs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyntheticTest {
    @Test
    fun udf250MetadataPartitionLikeBdRom() = runTest {
        val src = CachedSectorSource(ByteArraySectorSource(TestImages.udf250Metadata()))
        val vol = DiscFs.probe(src)
        val udf = assertNotNull(vol.udf, vol.udfError)
        assertEquals("2.50", udf.revisionText)
        assertTrue(udf.hasMetadataPartition)
        assertEquals("ライブBD", vol.label)
        assertEquals("FSET", udf.fileSetId)
        assertEquals(listOf("物理", "元数据（2 段）"), udf.mapDescriptions)

        val fs = DiscFs.open(src, vol)!!
        assertEquals("UDF 2.50（元数据分区）", fs.kind)
        val root = fs.list(fs.root)
        assertEquals(listOf("BDMV", "テスト.txt", "空のファイル"), root.map { it.name })
        assertEquals("INDX0200", String(fs.readAll(fs.find("/BDMV/index.bdmv")!!)))

        val txt = fs.find("/テスト.txt")!!
        assertEquals(3000, txt.size)
        val data = fs.readAll(txt)
        assertTrue((0 until 2048).all { data[it] == 'A'.code.toByte() })
        assertTrue((2048 until 3000).all { data[it] == 'B'.code.toByte() })
        assertEquals(
            listOf("0+2048@341", "2048+952@350"),
            fs.runs(txt).map { "${it.fileOffset}+${it.length}@${it.sector}" },
        )
        assertEquals(0, fs.readAll(fs.find("/空のファイル")!!).size)
        assertEquals(listOf(ContentKind.BDMV), ContentDetector.detect(fs).kinds)

        // 结尾锚点在最后一个扇区：文件系统一直到盘尾
        assertEquals(400, udf.structuresEnd)
        assertEquals(512, DiscFs.volumeEnd(src, vol))
    }

    @Test
    fun volumeEndIgnoresDataPastTheFileSystem() = runTest {
        // 同一个镜像放在更大的"盘"上（DVD+RW 格式化容量比文件系统大）：结尾仍是 512
        val img = TestImages.udf250Metadata().copyOf(2000 * SECTOR_SIZE)
        val src = CachedSectorSource(ByteArraySectorSource(img))
        val vol = DiscFs.probe(src)
        assertEquals(512, DiscFs.volumeEnd(src, vol))
    }

    @Test
    fun shiftJisPrimaryOnlyWithMultiExtentFile() = runTest {
        val src = CachedSectorSource(ByteArraySectorSource(TestImages.isoShiftJis()))
        val vol = DiscFs.probe(src)
        assertEquals("ライブ映像", vol.label)
        val fs = DiscFs.open(src, vol)!!
        assertEquals("ISO 9660", fs.kind)
        val root = fs.list(fs.root)
        assertEquals(listOf("BIG.BIN", "HIDDEN.DAT", "README", "テスト.TXT"), root.map { it.name })
        val big = root[0]
        assertEquals(4196, big.size)
        assertEquals(listOf("0+4096@30", "4096+100@40"), fs.runs(big).map { "${it.fileOffset}+${it.length}@${it.sector}" })
        val data = fs.readAll(big)
        assertTrue((0 until 4096).all { data[it].toInt() == 1 } && (4096 until 4196).all { data[it].toInt() == 2 })
        assertTrue(root[1].hidden)
        assertFalse(root[2].hidden)
        assertEquals("readme", String(fs.readAll(root[2])))
        assertEquals(1762750800000L, root[3].modifiedMillis)
    }

    @Test
    fun lastSessionOfMultiSessionCd() = runTest {
        val src = CachedSectorSource(ByteArraySectorSource(TestImages.isoShiftJis(base = 1000)))
        // 第一个区段没有文件系统
        assertFalse(DiscFs.probe(src).hasFileSystem)
        val vol = DiscFs.probe(src, ProbeOptions(sessionStart = 1000))
        val fs = DiscFs.open(src, vol)!!
        assertEquals("sjis ok", String(fs.readAll(fs.find("/テスト.TXT")!!)))
    }

    /** 读小文件时的预读不越过文件结尾（后面是 CSS 加密的 VOB 时会读出错）。 */
    @Test
    fun cachedSmallFileReadsDoNotReadPastTheFile() = runTest {
        val img = TestImages.isoShiftJis()
        val requested = mutableListOf<LongRange>()
        val inner = object : SectorSource {
            val base = ByteArraySectorSource(img)
            override val sectorCount = base.sectorCount
            override suspend fun read(lba: Long, count: Int, dst: ByteArray, offset: Int) {
                requested += lba until lba + count
                // 文件后面的扇区"读不出"（模拟未认证的 CSS 扇区）
                if (lba <= 44 && lba + count > 44) throw SectorReadException(lba, count, "5/6F/03", protected = true)
                base.read(lba, count, dst, offset)
            }
        }
        val src = CachedSectorSource(inner)
        val fs = DiscFs.open(src, DiscFs.probe(src))!!
        requested.clear()
        assertEquals("sjis ok", String(fs.readAll(fs.find("/テスト.TXT")!!)).trimEnd('\u0000'))
        // テスト.TXT 只占扇区 43：只读这一个，没有碰到 44
        assertEquals(listOf(43L..43L), requested)
    }
}
