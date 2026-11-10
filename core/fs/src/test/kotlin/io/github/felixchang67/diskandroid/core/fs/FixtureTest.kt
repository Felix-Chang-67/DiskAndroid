package io.github.felixchang67.diskandroid.core.fs

import kotlinx.coroutines.test.runTest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用真实建盘工具（genisoimage、pycdlib、mkudffs）做的镜像检验读取代码，见 src/test/fixtures/make_fixtures.sh。 */
class FixtureTest {
    private fun image(name: String): ByteArray =
        GZIPInputStream(javaClass.getResourceAsStream("/fixtures/$name.gz")!!).use { it.readBytes() }

    private fun source(name: String) = CachedSectorSource(ByteArraySectorSource(image(name)))

    private fun bigPattern(i: Int) = ((i * 7 + i / 2048) % 251).toByte()

    @Test
    fun jolietWithJapaneseNamesAndUtf8PrimaryLabel() = runTest {
        val src = source("joliet_rr.iso")
        val vol = DiscFs.probe(src)
        assertNull(vol.udf)
        // 主卷描述符里的卷标是 UTF-8（genisoimage 原样写入），不能变成 9 个下划线
        assertEquals("ライブ_LIVE_AT_TEST", vol.primary!!.volumeId)
        assertEquals(3, vol.joliet!!.jolietLevel)
        assertEquals("ライブ_LIVE_AT_TEST", vol.joliet!!.volumeId)
        assertEquals("ライブ_LIVE_AT_TEST", vol.label)
        assertEquals(464, vol.isoVolumeSize)
        assertEquals(464, DiscFs.volumeEnd(src, vol))

        val fs = DiscFs.open(src, vol)!!
        assertEquals("ISO 9660 + Joliet", fs.kind)
        val root = fs.list(fs.root)
        assertEquals(listOf("AUDIO_TS", "many", "VIDEO_TS", "日本語フォルダ", "空", "big.bin", "README.TXT"), root.map { it.name })
        assertTrue(root.take(5).all { it.isDirectory })

        val many = fs.list(fs.find("/many")!!)
        assertEquals(100, many.size)
        assertEquals("ファイル番号_001.txt", many.first().name)
        assertEquals("ファイル番号_100.txt", many.last().name)
        assertEquals("/many/ファイル番号_042.txt", many[41].path)
        assertEquals("file 042\n", String(fs.readAll(many[41])))

        val deep = fs.find("/日本語フォルダ/サブ/深い/ファイル.dat")!!
        assertEquals(5000, deep.size)
        assertTrue(fs.readAll(deep).all { it == 'D'.code.toByte() })
        assertEquals("さくら さくら\n弥生の空は\n", String(fs.readAll(fs.find("/日本語フォルダ/歌詞（さくら）.txt")!!)))
        assertEquals(emptyList(), fs.list(fs.find("/空")!!))
        assertEquals(1762750800000L, fs.find("/README.TXT")!!.modifiedMillis)

        val content = ContentDetector.detect(fs)
        assertEquals(listOf(ContentKind.DVD_VIDEO), content.kinds)
        assertFalse(content.aacs)
    }

    @Test
    fun bigFileReadsAtAnyOffset() = runTest {
        val src = source("joliet_rr.iso")
        val fs = DiscFs.open(src, DiscFs.probe(src))!!
        val big = fs.find("/BIG.BIN")!!
        assertEquals(300_000, big.size)
        val all = fs.readAll(big)
        for (i in all.indices) if (all[i] != bigPattern(i)) error("big.bin 第 $i 字节不对")
        // 不对齐的偏移和长度，跨扇区
        val reader = fs.open(big)
        val buf = ByteArray(5000)
        val n = reader.read(4095, buf, 0, 5000)
        assertTrue(n > 0)
        for (k in 0 until n) assertEquals(bigPattern(4095 + k), buf[k])
        assertEquals(-1, reader.read(300_000, buf, 0, 10))
        val tail = ByteArray(100)
        assertEquals(10, reader.read(299_990, tail, 0, 100))
    }

    @Test
    fun rockRidgeNamesWhenUsingThePrimaryTree() = runTest {
        val src = source("joliet_rr.iso")
        val vol = DiscFs.probe(src)
        val fs = IsoFileSystem.open(src, vol.primary!!)
        assertEquals("ISO 9660 + Rock Ridge", fs.kind)
        val names = fs.list(fs.root).map { it.name }
        assertTrue("日本語フォルダ" in names, names.toString())
        assertTrue("big.bin" in names, names.toString())
        assertEquals(5000, fs.find("/日本語フォルダ/サブ/深い/ファイル.dat")!!.size)
    }

    @Test
    fun udf102BridgeLikeDvdVideo() = runTest {
        val src = source("udf102_bridge.iso")
        val vol = DiscFs.probe(src)
        val udf = assertNotNull(vol.udf, vol.udfError)
        assertEquals("1.02", udf.revisionText)
        assertEquals("DVD_VIDEO_TEST", udf.logicalVolumeId)
        assertEquals("DVD_VIDEO_TEST", vol.label)
        assertEquals("UDF 1.02 + ISO 9660 + Joliet", vol.description)
        assertEquals(442, DiscFs.volumeEnd(src, vol))

        val fs = DiscFs.open(src, vol)!!
        assertEquals("UDF 1.02", fs.kind)
        assertEquals(listOf("AUDIO_TS", "VIDEO_TS", "特典.txt"), fs.list(fs.root).map { it.name })
        val ts = fs.list(fs.find("/VIDEO_TS")!!)
        assertEquals(listOf("VIDEO_TS.IFO", "VTS_01_0.IFO", "VTS_01_1.VOB"), ts.map { it.name })
        assertEquals(10240, ts[2].size)
        assertEquals("DVDVIDEO-VMG", String(fs.readAll(ts[0])))
        assertEquals("ボーナス映像", String(fs.readAll(fs.find("/特典.txt")!!)))
        assertEquals(listOf(ContentKind.DVD_VIDEO), ContentDetector.detect(fs).kinds)
    }

    @Test
    fun pycdlibUdfWithJapaneseNames() = runTest {
        val src = source("udf260.iso")
        val vol = DiscFs.probe(src)
        val udf = assertNotNull(vol.udf, vol.udfError)
        // pycdlib 的 "2.60" 模式在域标识里写的版本其实是 1.02
        assertEquals("1.02", udf.revisionText)
        val fs = DiscFs.open(src, vol)!!
        assertEquals("UDF 1.02", fs.kind)
        assertEquals(listOf("BDMV", "音楽", "大きい.bin"), fs.list(fs.root).map { it.name })
        assertEquals("setlist\n", String(fs.readAll(fs.find("/音楽/ライブ/セットリスト.txt")!!)))
        val big = fs.readAll(fs.find("/大きい.bin")!!)
        assertEquals(70000, big.size)
        for (i in big.indices) if (big[i] != ((i * 13 + i / 2048) % 253).toByte()) error("大きい.bin 第 $i 字节不对")
        assertEquals("INDX0200", String(fs.readAll(fs.find("/BDMV/index.bdmv")!!)))
        assertEquals(listOf(ContentKind.BDMV), ContentDetector.detect(fs).kinds)

        // 同一张盘的 Joliet 树
        val joliet = IsoFileSystem.open(src, vol.joliet!!)
        assertEquals(listOf("BDMV", "音楽", "大きい.bin"), joliet.list(joliet.root).map { it.name })
    }

    @Test
    fun bdrWithVirtualAllocationTable() = runTest {
        val img = image("udf250_meta.img")
        val src = CachedSectorSource(ByteArraySectorSource(img))
        val last = img.size / SECTOR_SIZE - 1L
        val vol = DiscFs.probe(src, ProbeOptions(lastWritten = listOf(last)))
        val udf = assertNotNull(vol.udf, vol.udfError)
        assertEquals("2.50", udf.revisionText)
        assertTrue(udf.hasVat)
        assertEquals("ブルーレイ_TEST", vol.label)
        val fs = DiscFs.open(src, vol)!!
        assertEquals("UDF 2.50（VAT 增量刻录）", fs.kind)
        assertEquals(emptyList(), fs.list(fs.root))
    }

    @Test
    fun cdrWithVatFoundFromDeviceEndWithoutHints() = runTest {
        val src = source("udf201_vat.img")
        val vol = DiscFs.probe(src)
        val udf = assertNotNull(vol.udf, vol.udfError)
        assertTrue(udf.hasVat)
        assertEquals("CDR_VAT", vol.label)
        assertEquals(emptyList(), DiscFs.open(src, vol)!!.let { it.list(it.root) })
    }

    @Test
    fun dvdRwWithSparingTable() = runTest {
        val src = source("udf201_sparing.img")
        val vol = DiscFs.probe(src)
        val udf = assertNotNull(vol.udf, vol.udfError)
        assertTrue(udf.hasSparing)
        assertEquals("2.01", udf.revisionText)
        assertEquals("RW_SPARING", vol.label)
        val fs = DiscFs.open(src, vol)!!
        assertEquals(emptyList(), fs.list(fs.root))
        // 镜像就是整张"盘"：结尾锚点在最后一个扇区
        assertEquals(8192, DiscFs.volumeEnd(src, vol))
    }

    @Test
    fun metadataReadsAreBatched() = runTest {
        val inner = ByteArraySectorSource(image("joliet_rr.iso"))
        val src = CachedSectorSource(inner)
        val fs = DiscFs.open(src, DiscFs.probe(src))!!
        fs.list(fs.find("/many")!!)
        // 100 个文件的目录 + 卷描述符：靠预读，命令数远少于扇区数
        assertTrue(inner.reads < 12, "读了 ${inner.reads} 次")
    }

    @Test
    fun noFileSystemOnBlankData() = runTest {
        val src = CachedSectorSource(ByteArraySectorSource(ByteArray(600 * SECTOR_SIZE)))
        val vol = DiscFs.probe(src)
        assertFalse(vol.hasFileSystem)
        assertNull(DiscFs.open(src, vol))
        assertNull(vol.label)
    }
}
