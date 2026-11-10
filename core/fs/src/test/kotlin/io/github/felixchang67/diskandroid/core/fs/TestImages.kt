package io.github.felixchang67.diskandroid.core.fs

/** 测试用的手工镜像（按 ECMA-119 / ECMA-167 / UDF 2.50 的字节布局拼出来），覆盖建盘工具做不出来的结构。 */
internal class ImageWriter(sectors: Int) {
    val img = ByteArray(sectors * SECTOR_SIZE)

    fun le16(o: Int, v: Int) {
        img[o] = v.toByte()
        img[o + 1] = (v shr 8).toByte()
    }

    fun le32(o: Int, v: Long) {
        for (k in 0 until 4) img[o + k] = (v ushr (8 * k)).toByte()
    }

    fun be32(o: Int, v: Long) {
        for (k in 0 until 4) img[o + 3 - k] = (v ushr (8 * k)).toByte()
    }

    fun both32(o: Int, v: Long) {
        le32(o, v)
        be32(o + 4, v)
    }

    fun both16(o: Int, v: Int) {
        le16(o, v)
        img[o + 2] = (v shr 8).toByte()
        img[o + 3] = v.toByte()
    }

    fun bytes(o: Int, b: ByteArray) = b.copyInto(img, o)
    fun ascii(o: Int, s: String) = bytes(o, s.toByteArray(Charsets.US_ASCII))

    /** UDF 描述符标签（版本 3，CRC 不填：读取代码只校验校验和）。 */
    fun tag(o: Int, id: Int, location: Long) {
        le16(o, id)
        le16(o + 2, 3)
        le16(o + 6, 1)
        le32(o + 12, location)
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += img[o + i].toInt() and 0xFF
        img[o + 4] = sum.toByte()
    }

    /** dstring：CS0 名字 + 最后一个字节放长度。 */
    fun dstring(o: Int, fieldLen: Int, s: String) {
        val cs0 = cs0(s)
        bytes(o, cs0)
        img[o + fieldLen - 1] = cs0.size.toByte()
    }

    companion object {
        fun cs0(s: String): ByteArray =
            if (s.all { it.code < 256 }) byteArrayOf(8) + s.toByteArray(Charsets.ISO_8859_1) else byteArrayOf(16) + s.toByteArray(Charsets.UTF_16BE)
    }
}

internal object TestImages {
    const val PART_START = 300
    const val TOTAL = 512

    /**
     * BD-ROM 式的 UDF 2.50：物理分区 300–399，元数据文件在物理块 0，分两段（物理块 10–11、20–24）；
     * 文件集描述符和所有文件项都在元数据分区里，文件内容用 long_ad 指回物理分区。
     * /BDMV/index.bdmv = "INDX0200"；/テスト.txt = 2048 个 A + 952 个 B（两段不相邻）；/空のファイル 是 0 字节。
     * 卷标（逻辑卷标识）"ライブBD"；锚点在 256 和 511（最后一个扇区）。
     */
    fun udf250Metadata(): ByteArray {
        val w = ImageWriter(TOTAL)
        fun s(sector: Int) = sector * SECTOR_SIZE
        fun phys(block: Int) = s(PART_START + block)
        // 元数据块 → 物理块
        val metaToPhys = listOf(10, 11, 20, 21, 22, 23, 24)
        fun meta(block: Int) = phys(metaToPhys[block])

        // 卷识别序列
        listOf("BEA01", "NSR03", "TEA01").forEachIndexed { i, id ->
            w.ascii(s(16 + i) + 1, id)
            w.img[s(16 + i) + 6] = 1
        }
        // 主卷描述符序列：PVD 32、PD 33、LVD 34、TD 35
        w.le32(s(32) + 16, 1)
        w.dstring(s(32) + 24, 32, "BD_TEST")
        w.tag(s(32), 1, 32)

        w.le32(s(33) + 16, 2)
        w.le16(s(33) + 20, 1)
        w.le16(s(33) + 22, 0)
        w.ascii(s(33) + 25, "+NSR03")
        w.le32(s(33) + 184, 1)
        w.le32(s(33) + 188, PART_START.toLong())
        w.le32(s(33) + 192, 100)
        w.tag(s(33), 5, 33)

        val lvd = s(34)
        w.le32(lvd + 16, 3)
        w.dstring(lvd + 84, 128, "ライブBD")
        w.le32(lvd + 212, 2048)
        w.ascii(lvd + 217, "*OSTA UDF Compliant")
        w.le16(lvd + 240, 0x0250)
        w.le32(lvd + 248, 2048) // 文件集描述符：元数据分区（引用号 1）的块 0
        w.le32(lvd + 252, 0)
        w.le16(lvd + 256, 1)
        w.le32(lvd + 264, 6 + 64)
        w.le32(lvd + 268, 2)
        // 映射 0：类型 1，分区号 0
        w.img[lvd + 440] = 1
        w.img[lvd + 441] = 6
        w.le16(lvd + 442, 1)
        w.le16(lvd + 444, 0)
        // 映射 1：元数据分区
        val m = lvd + 446
        w.img[m] = 2
        w.img[m + 1] = 64
        w.ascii(m + 5, "*UDF Metadata Partition")
        w.le16(m + 28, 0x0250)
        w.le16(m + 36, 1)
        w.le16(m + 38, 0)
        w.le32(m + 40, 0) // 元数据文件在物理块 0
        w.le32(m + 44, 0xFFFFFFFFL)
        w.le32(m + 48, 0xFFFFFFFFL)
        w.le32(m + 52, 32)
        w.le16(m + 56, 1)
        w.tag(lvd, 6, 34)
        w.tag(s(35), 8, 35)

        // 锚点
        for (a in listOf(256, TOTAL - 1)) {
            w.le32(s(a) + 16, 4L * SECTOR_SIZE)
            w.le32(s(a) + 20, 32)
            w.le32(s(a) + 24, 4L * SECTOR_SIZE)
            w.le32(s(a) + 28, 32)
            w.tag(s(a), 2, a.toLong())
        }

        // 元数据文件（File Entry，类型 250，两个 short_ad）
        val mf = phys(0)
        w.img[mf + 27] = 250.toByte()
        w.le16(mf + 34, 0)
        w.le32(mf + 56, 7L * SECTOR_SIZE)
        w.le32(mf + 172, 16)
        w.le32(mf + 176, 2L * SECTOR_SIZE)
        w.le32(mf + 180, 10)
        w.le32(mf + 184, 5L * SECTOR_SIZE)
        w.le32(mf + 188, 20)
        w.tag(mf, 261, 0)

        // 文件集描述符（元数据块 0）
        val fsd = meta(0)
        w.dstring(fsd + 112, 128, "ライブBD")
        w.dstring(fsd + 304, 32, "FSET")
        w.le32(fsd + 400, 2048)
        w.le32(fsd + 404, 1)
        w.le16(fsd + 408, 1)
        w.tag(fsd, 256, 0)

        // 目录项（FID）
        fun fid(chars: Int, name: String?, icbBlock: Int, icbPart: Int, location: Long): ByteArray {
            val n = name?.let { ImageWriter.cs0(it) } ?: ByteArray(0)
            val len = (38 + n.size + 3) and 3.inv()
            val tmp = ImageWriter(1)
            tmp.le16(16, 1)
            tmp.img[18] = chars.toByte()
            tmp.img[19] = n.size.toByte()
            tmp.le32(20, 2048)
            tmp.le32(24, icbBlock.toLong())
            tmp.le16(28, icbPart)
            tmp.bytes(38, n)
            tmp.tag(0, 257, location)
            return tmp.img.copyOf(len)
        }

        // 根目录（Extended File Entry，元数据块 1，目录数据嵌在里面）
        val rootData = fid(0x0A, null, 1, 1, 1) + fid(0x02, "BDMV", 2, 1, 1) + fid(0, "テスト.txt", 5, 1, 1) + fid(0, "空のファイル", 6, 1, 1)
        val root = meta(1)
        w.img[root + 27] = 4
        w.le16(root + 34, 3)
        w.le32(root + 56, rootData.size.toLong())
        w.le32(root + 212, rootData.size.toLong())
        w.bytes(root + 216, rootData)
        w.tag(root, 266, 1)

        // BDMV 目录（元数据块 2），数据在元数据块 3（short_ad，同一分区 = 元数据分区）
        val bdmvData = fid(0x0A, null, 1, 1, 3) + fid(0, "index.bdmv", 4, 1, 3)
        val bdmv = meta(2)
        w.img[bdmv + 27] = 4
        w.le16(bdmv + 34, 0)
        w.le32(bdmv + 56, bdmvData.size.toLong())
        w.le32(bdmv + 212, 8)
        w.le32(bdmv + 216, bdmvData.size.toLong())
        w.le32(bdmv + 220, 3)
        w.tag(bdmv, 266, 2)
        w.bytes(meta(3), bdmvData)

        // index.bdmv（元数据块 4）：long_ad 指向物理分区块 40
        val idx = meta(4)
        w.img[idx + 27] = 5
        w.le16(idx + 34, 1)
        w.le32(idx + 56, 8)
        w.le32(idx + 172, 16)
        w.le32(idx + 176, 8)
        w.le32(idx + 180, 40)
        w.le16(idx + 184, 0)
        w.tag(idx, 261, 4)
        w.ascii(phys(40), "INDX0200")

        // テスト.txt（元数据块 5）：两段 long_ad，物理块 41 和 50
        val txt = meta(5)
        w.img[txt + 27] = 5
        w.le16(txt + 34, 1)
        w.le32(txt + 56, 3000)
        w.le32(txt + 172, 32)
        w.le32(txt + 176, 2048)
        w.le32(txt + 180, 41)
        w.le16(txt + 184, 0)
        w.le32(txt + 192, 952)
        w.le32(txt + 196, 50)
        w.le16(txt + 200, 0)
        w.tag(txt, 261, 5)
        w.img.fill('A'.code.toByte(), phys(41), phys(41) + 2048)
        w.img.fill('B'.code.toByte(), phys(50), phys(50) + 952)

        // 空のファイル（元数据块 6）
        val empty = meta(6)
        w.img[empty + 27] = 5
        w.tag(empty, 261, 6)
        return w.img
    }

    /**
     * 只有主卷描述符的 ISO 9660（没有 Joliet / Rock Ridge），从 [base] 开始（模拟多区段 CD 的最后一个区段）：
     * - 卷标是 Shift-JIS 的「ライブ映像」；
     * - BIG.BIN 由两个目录记录拼成（第一个带多区段标志）：4096 字节在扇区 base+30，100 字节在 base+40；
     * - 文件名「テスト.TXT;1」是 Shift-JIS；HIDDEN.DAT 带隐藏标志；README. 去掉结尾的点。
     */
    fun isoShiftJis(base: Int = 0): ByteArray {
        val w = ImageWriter(base + 64)
        val sjis = charset("windows-31j")
        fun s(sector: Int) = (base + sector) * SECTOR_SIZE
        val pvd = s(16)
        w.img[pvd] = 1
        w.ascii(pvd + 1, "CD001")
        w.img[pvd + 6] = 1
        w.img.fill(0x20, pvd + 8, pvd + 72)
        w.bytes(pvd + 40, "ライブ映像".toByteArray(sjis))
        w.both32(pvd + 80, base + 64L)
        w.both16(pvd + 128, 2048)
        record(w, pvd + 156, base + 20L, 2048, 0x02, byteArrayOf(0))
        w.img[s(17)] = 255.toByte()
        w.ascii(s(17) + 1, "CD001")
        w.img[s(17) + 6] = 1

        var o = s(20)
        o += record(w, o, base + 20L, 2048, 0x02, byteArrayOf(0))
        o += record(w, o, base + 20L, 2048, 0x02, byteArrayOf(1))
        o += record(w, o, base + 30L, 4096, 0x80, "BIG.BIN;1".toByteArray())
        o += record(w, o, base + 40L, 100, 0x00, "BIG.BIN;1".toByteArray())
        o += record(w, o, base + 41L, 5, 0x01, "HIDDEN.DAT;1".toByteArray())
        o += record(w, o, base + 42L, 6, 0x00, "README.;1".toByteArray())
        record(w, o, base + 43L, 7, 0x00, "テスト.TXT;1".toByteArray(sjis))
        w.img.fill(1, s(30), s(30) + 4096)
        w.img.fill(2, s(40), s(40) + 100)
        w.ascii(s(42), "readme")
        w.ascii(s(43), "sjis ok")
        return w.img
    }

    /** 写一条目录记录，返回长度。 */
    private fun record(w: ImageWriter, o: Int, extent: Long, size: Long, flags: Int, name: ByteArray): Int {
        val len = (33 + name.size + 1) and 1.inv()
        w.img[o] = len.toByte()
        w.both32(o + 2, extent)
        w.both32(o + 10, size)
        // 2025-11-10 05:00:00 GMT+9
        w.img[o + 18] = 125
        w.img[o + 19] = 11
        w.img[o + 20] = 10
        w.img[o + 21] = 14
        w.img[o + 22] = 0
        w.img[o + 23] = 0
        w.img[o + 24] = 36
        w.img[o + 25] = flags.toByte()
        w.both16(o + 28, 1)
        w.img[o + 32] = name.size.toByte()
        w.bytes(o + 33, name)
        return len
    }
}
