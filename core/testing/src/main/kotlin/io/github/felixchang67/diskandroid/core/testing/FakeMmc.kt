package io.github.felixchang67.diskandroid.core.testing

/**
 * 在 SCSI 层面模拟一台 MMC 光驱（只实现目前用到的命令），给 JVM 单元测试用。
 *
 * 状态：托盘开/关、托盘里的盘（profile）、就绪前需要的 TUR 次数、待报告的 UNIT ATTENTION、
 * 待报告的 GESN 媒体事件。收到的所有 CDB 都记在 [received] 里，便于断言"没有发出写命令"。
 */
class FakeMmc {
    class Reply(val status: Int, val data: ByteArray = ByteArray(0)) {
        companion object {
            const val GOOD = 0
            const val CHECK_CONDITION = 1
        }
    }

    // ---- 可配置的固定数据 ----
    var vendor = "HL-DT-ST"
    var product = "BD-RE  BU40N"
    var revision = "1.03"
    var supportsGesn = true
    var supportsGetConfiguration = true
    var rpcState: ByteArray = byteArrayOf(0x00, 0x06, 0, 0, 0x45.toByte(), 0xFD.toByte(), 0x01, 0) // 地区 2，用户还可改 5 次
    /** 支持的 profile（GET CONFIGURATION 的 Profile List）。 */
    var supportedProfiles = listOf(0x0043, 0x0042, 0x0041, 0x0040, 0x002B, 0x001B, 0x001A, 0x0016, 0x0015, 0x0014, 0x0013, 0x0012, 0x0011, 0x0010, 0x000A, 0x0009, 0x0008)

    // ---- 动态状态 ----
    var trayOpen = false
    /** 托盘里的盘（不管托盘开没开），null 表示没有盘。 */
    var disc: FakeDisc? = null

    /** 只换 profile 时用默认盘片模型。 */
    var discProfile: Int?
        get() = disc?.profile
        set(value) {
            disc = value?.let { FakeDisc.forProfile(it) }
        }
    /** READ CD 返回大端 CD-DA（模拟少数按大端输出的光驱）。 */
    var bigEndianAudio = false
    /** 模拟不支持 DAP 位的光驱（READ CD 带 DAP 时返回 5/24/00）。 */
    var rejectDap = false
    /** 收到的 SET CD SPEED 读取速度（kB/s）。 */
    val speedRequests = mutableListOf<Int>()
    /** READ CD 成功读出的扇区总数。 */
    var cdSectorsRead = 0L
    var becomingReadyPolls = 0
    val pendingUnitAttention = ArrayDeque<Pair<Int, Int>>()
    val pendingMediaEvents = ArrayDeque<Int>()
    val received = mutableListOf<ByteArray>()
    private var sense: ByteArray? = null

    val mediumPresent: Boolean get() = !trayOpen && disc != null

    fun powerOn() {
        pendingUnitAttention.addLast(0x29 to 0x00)
    }

    /** 用户把盘放进托盘并推回去。 */
    fun insertDisc(profile: Int, spinUpPolls: Int = 2) = insertDisc(FakeDisc.forProfile(profile), spinUpPolls)

    fun insertDisc(disc: FakeDisc, spinUpPolls: Int = 2) {
        this.disc = disc
        closeTray(spinUpPolls)
    }

    fun closeTray(spinUpPolls: Int = 2) {
        trayOpen = false
        if (disc != null) {
            becomingReadyPolls = spinUpPolls
            pendingUnitAttention.addLast(0x28 to 0x00)
            pendingMediaEvents.addLast(2) // NewMedia
        }
    }

    fun openTray() {
        trayOpen = true
        pendingMediaEvents.addLast(3) // MediaRemoval
    }

    fun pressEjectButton() {
        pendingMediaEvents.addLast(1) // EjectRequest
    }

    fun removeDiscFromOpenTray() {
        disc = null
    }

    fun handle(cdb: ByteArray, dataOut: ByteArray? = null): Reply {
        received += cdb.copyOf()
        val op = cdb[0].toInt() and 0xFF
        // INQUIRY 与 REQUEST SENSE 不受 UNIT ATTENTION 影响（SPC-4）
        if (op == 0x12) return good(inquiry(), allocation6(cdb))
        if (op == 0x03) {
            val s = sense ?: senseBytes(0, 0, 0)
            sense = null
            return good(s, cdb[4].toInt() and 0xFF)
        }
        pendingUnitAttention.removeFirstOrNull()?.let { (asc, ascq) -> return check(0x6, asc, ascq) }
        return when (op) {
            0x00 -> testUnitReady()
            0x1B -> startStop(cdb)
            0x46 -> if (supportsGetConfiguration) good(configuration(cdb), allocation10(cdb)) else check(0x5, 0x20, 0x00)
            0x4A -> gesn(cdb)
            0x5A -> if ((cdb[2].toInt() and 0x3F) == 0x2A) good(modeSense2A(), allocation10(cdb)) else check(0x5, 0x24, 0x00)
            0xA4 -> if ((cdb[10].toInt() and 0x3F) == 0x08) good(rpcState, allocation12(cdb)) else check(0x5, 0x24, 0x00)
            0xBD -> good(mechanismStatus(), allocation12(cdb))
            0x25, 0x51, 0x52, 0x43, 0xAD, 0xAC -> notReady() ?: mediaCommand(op, cdb)
            0xBE -> notReady() ?: readCd(cdb)
            0xBB -> {
                speedRequests += ((cdb[2].toInt() and 0xFF) shl 8) or (cdb[3].toInt() and 0xFF)
                Reply(Reply.GOOD)
            }
            else -> check(0x5, 0x20, 0x00)
        }
    }

    /** 盘片未就绪时（无盘 / 正在就绪）返回对应的 CHECK CONDITION；不消耗就绪计数（只有 TUR 消耗）。 */
    private fun notReady(): Reply? = when {
        disc == null || trayOpen -> check(0x2, 0x3A, if (trayOpen) 0x02 else 0x01)
        becomingReadyPolls > 0 -> check(0x2, 0x04, 0x01)
        else -> null
    }

    private fun mediaCommand(op: Int, cdb: ByteArray): Reply {
        val d = disc!!
        return when (op) {
            0x25 -> good(be32(d.leadOutLba - 1) + be32(d.blockLength.toLong()), 8)
            0x51 -> good(discInformation(d), allocation10(cdb))
            0x52 -> trackInformation(d, cdb)
            0x43 -> toc(d, cdb)
            0xAD -> discStructure(d, cdb)
            else -> performance(d, cdb)
        }
    }

    private fun isCd(d: FakeDisc) = d.profile in 0x0008..0x000A
    private fun isDvd(d: FakeDisc) = d.profile in 0x0010..0x002B
    private fun isBd(d: FakeDisc) = d.profile in 0x0040..0x0043

    private fun discInformation(d: FakeDisc): ByteArray {
        val b = ByteArray(34)
        b[1] = 32
        val state = if (d.finalized) 0x0E else 0x05 // 已完成区段 + 已封盘 / 未完成区段 + 可追加
        b[2] = ((if (d.erasable) 0x10 else 0) or state).toByte()
        b[3] = 1
        val lastSession = d.sessions.last()
        val inLast = d.tracks.filter { it.session == lastSession }
        b[4] = d.sessions.size.toByte()
        b[5] = inLast.first().number.toByte()
        b[6] = inLast.last().number.toByte()
        b[7] = 0x20
        b[8] = if (isCd(d)) 0x00 else 0xFF.toByte()
        for (i in 16..23) b[i] = 0xFF.toByte()
        return b
    }

    private fun trackInformation(d: FakeDisc, cdb: ByteArray): Reply {
        val type = cdb[1].toInt() and 0x03
        val value = ((cdb[2].toLong() and 0xFF) shl 24) or ((cdb[3].toLong() and 0xFF) shl 16) or ((cdb[4].toLong() and 0xFF) shl 8) or (cdb[5].toLong() and 0xFF)
        val t = when (type) {
            0 -> d.tracks.firstOrNull { value >= it.startLba && value < it.startLba + it.sectors }
            1 -> d.tracks.firstOrNull { it.number.toLong() == value }
            else -> d.tracks.firstOrNull { it.session.toLong() == value }
        } ?: return check(0x5, 0x24, 0x00)
        val b = ByteArray(48)
        b[1] = 46
        b[2] = t.number.toByte()
        b[3] = t.session.toByte()
        b[5] = if (t.audio) 0x00 else 0x04
        b[6] = if (t.audio) 0x0F else 0x01
        be32(t.startLba).copyInto(b, 8)
        be32(t.sectors).copyInto(b, 24)
        be32(t.startLba + t.sectors - 1).copyInto(b, 28)
        return good(b, allocation10(cdb))
    }

    private fun toc(d: FakeDisc, cdb: ByteArray): Reply {
        val msf = (cdb[1].toInt() and 0x02) != 0
        val format = cdb[2].toInt() and 0x0F
        val body = when (format) {
            0 -> {
                val start = maxOf(1, cdb[6].toInt() and 0xFF)
                val entries = d.tracks.filter { it.number >= start }.map { tocEntry(it.number, control(it), it.startLba, msf) } +
                    tocEntry(0xAA, 0x14, d.leadOutLba, msf)
                byteArrayOf(d.tracks.first().number.toByte(), d.tracks.last().number.toByte()) + entries.fold(ByteArray(0)) { a, e -> a + e }
            }
            1 -> {
                val last = d.sessions.last()
                val first = d.tracks.first { it.session == last }
                byteArrayOf(1, last.toByte(), 0, 0x14, first.number.toByte(), 0) + be32(first.startLba)
            }
            2 -> {
                if (!isCd(d)) return check(0x5, 0x24, 0x00)
                val entries = mutableListOf<ByteArray>()
                for (s in d.sessions) {
                    val ts = d.tracks.filter { it.session == s }
                    val ctrl = if (ts.first().audio) 0x10 else 0x14
                    val end = if (s == d.sessions.last()) d.leadOutLba else ts.last().startLba + ts.last().sectors
                    entries += fullTocEntry(s, ctrl, 0xA0, ts.first().number, 0x00, 0)
                    entries += fullTocEntry(s, ctrl, 0xA1, ts.last().number, 0, 0)
                    entries += msfEntry(s, ctrl, 0xA2, end)
                    ts.forEach { t -> entries += msfEntry(s, control(t), t.number, t.startLba) }
                }
                byteArrayOf(d.sessions.first().toByte(), d.sessions.last().toByte()) + entries.fold(ByteArray(0)) { a, e -> a + e }
            }
            5 -> {
                val packs = d.cdTextPacks ?: return check(0x5, 0x24, 0x00)
                return good(CdTextBuilder.response(packs), allocation10(cdb))
            }
            else -> return check(0x5, 0x24, 0x00)
        }
        val len = body.size
        return good(byteArrayOf((len shr 8).toByte(), len.toByte()) + body, allocation10(cdb))
    }

    /** ADR 1 + 控制位：音频 0h（预加重 1h），数据 4h。 */
    private fun control(t: FakeTrack): Int = if (t.audio) (if (t.preEmphasis) 0x11 else 0x10) else 0x14

    /**
     * READ CD：只支持 CD-DA（期望扇区类型 0 或 1，字节 9 含用户数据位）。音频内容是确定的正弦波，
     * 第 N 轨频率 220×N Hz，左声道 sin、右声道 cos，振幅 6000，方便测试检查字节序和连续性。
     */
    private fun readCd(cdb: ByteArray): Reply {
        val d = disc!!
        val type = (cdb[1].toInt() shr 2) and 0x07
        val dap = (cdb[1].toInt() and 0x02) != 0
        if (dap && rejectDap) return check(0x5, 0x24, 0x00)
        if ((cdb[9].toInt() and 0x10) == 0) return check(0x5, 0x24, 0x00)
        val lba = (((cdb[2].toInt() and 0xFF) shl 24) or ((cdb[3].toInt() and 0xFF) shl 16) or ((cdb[4].toInt() and 0xFF) shl 8) or (cdb[5].toInt() and 0xFF)).toLong()
        val count = ((cdb[6].toInt() and 0xFF) shl 16) or ((cdb[7].toInt() and 0xFF) shl 8) or (cdb[8].toInt() and 0xFF)
        val out = ByteArray(count * 2352)
        for (i in 0 until count) {
            val sector = lba + i
            val track = d.tracks.firstOrNull { sector >= it.startLba && sector < it.startLba + it.sectors } ?: return check(0x5, 0x21, 0x00)
            if (!track.audio || (type != 0 && type != 1)) return check(0x5, 0x64, 0x00)
            if (sector in d.badSectors) return check(0x3, 0x11, 0x05)
            fillAudio(out, i * 2352, sector, track.number)
        }
        cdSectorsRead += count
        return good(out, out.size)
    }

    /** 测试用：当前盘片 [lba] 起 [count] 个扇区"应该"读到的小端 PCM（不考虑坏扇区）。 */
    fun expectedAudio(lba: Long, count: Int): ByteArray {
        val d = disc!!
        val out = ByteArray(count * 2352)
        val saved = bigEndianAudio
        bigEndianAudio = false
        for (i in 0 until count) {
            val sector = lba + i
            val track = d.tracks.first { sector >= it.startLba && sector < it.startLba + it.sectors }
            fillAudio(out, i * 2352, sector, track.number)
        }
        bigEndianAudio = saved
        return out
    }

    private fun fillAudio(out: ByteArray, offset: Int, sector: Long, track: Int) {
        val w = 2 * Math.PI * 220.0 * track / 44_100.0
        for (f in 0 until 588) {
            val n = sector * 588 + f
            val l = (6000 * kotlin.math.sin(w * n)).toInt()
            val r = (6000 * kotlin.math.cos(w * n)).toInt()
            putSample(out, offset + f * 4, l)
            putSample(out, offset + f * 4 + 2, r)
        }
    }

    private fun putSample(out: ByteArray, at: Int, v: Int) {
        if (bigEndianAudio) {
            out[at] = (v shr 8).toByte()
            out[at + 1] = v.toByte()
        } else {
            out[at] = v.toByte()
            out[at + 1] = (v shr 8).toByte()
        }
    }

    private fun tocEntry(track: Int, adrCtrl: Int, lba: Long, msf: Boolean): ByteArray {
        val addr = if (msf) byteArrayOf(0) + msfBytes(lba) else be32(lba)
        return byteArrayOf(0, adrCtrl.toByte(), track.toByte(), 0) + addr
    }

    private fun msfBytes(lba: Long): ByteArray {
        val a = lba + 150
        return byteArrayOf((a / 4500).toByte(), ((a / 75) % 60).toByte(), (a % 75).toByte())
    }

    private fun fullTocEntry(session: Int, adrCtrl: Int, point: Int, pmin: Int, psec: Int, pframe: Int) =
        byteArrayOf(session.toByte(), adrCtrl.toByte(), 0, point.toByte(), 0, 0, 0, 0, pmin.toByte(), psec.toByte(), pframe.toByte())

    private fun msfEntry(session: Int, adrCtrl: Int, point: Int, lba: Long): ByteArray {
        val m = msfBytes(lba)
        return fullTocEntry(session, adrCtrl, point, m[0].toInt(), m[1].toInt(), m[2].toInt())
    }

    private fun discStructure(d: FakeDisc, cdb: ByteArray): Reply {
        val mediaType = cdb[1].toInt() and 0x0F
        val format = cdb[7].toInt() and 0xFF
        val alloc = allocation12(cdb)
        return when {
            mediaType == 0 && isDvd(d) && format == 0x00 -> {
                val b = ByteArray(2052)
                b[0] = 0x08
                b[1] = 0x02
                b[4] = 0x01 // DVD-ROM，Part 1
                b[5] = 0x02 // 120 mm，10.08 Mbit/s
                b[6] = (((d.dvdLayers - 1) shl 5) or 0x01).toByte()
                b[7] = 0x10
                be32(0x030000).copyInto(b, 8)
                be32(0x030000 + d.leadOutLba - 1).copyInto(b, 12)
                good(b, alloc)
            }
            mediaType == 0 && isDvd(d) && format == 0x01 ->
                good(byteArrayOf(0x00, 0x06, 0, 0, d.dvdProtection.toByte(), d.dvdRegionMask.toByte(), 0, 0), alloc)
            mediaType == 1 && isBd(d) && format == 0x00 -> {
                val b = ByteArray(4 + 64)
                b[1] = 66
                byteArrayOf('D'.code.toByte(), 'I'.code.toByte(), 0x01, 0x08).copyInto(b, 4)
                d.bdTypeId.toByteArray(Charsets.US_ASCII).copyInto(b, 12)
                b[15] = 0x11
                good(b, alloc)
            }
            else -> check(0x5, 0x30, 0x02)
        }
    }

    private fun performance(d: FakeDisc, cdb: ByteArray): Reply {
        if ((cdb[10].toInt() and 0xFF) != 0) return check(0x5, 0x24, 0x00)
        val desc = be32(0) + be32(d.readSpeedKBps / 2L) + be32(d.leadOutLba - 1) + be32(d.readSpeedKBps.toLong())
        // GET PERFORMANCE 的字节 8–9 是"最多返回几个描述符"，传输长度 = 8 + 16 × N
        val maxDescriptors = allocation12(cdb)
        return good(be32(4L + desc.size) + byteArrayOf(0, 0, 0, 0) + desc, 8 + 16 * maxDescriptors)
    }

    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun testUnitReady(): Reply = when {
        disc == null || trayOpen -> check(0x2, 0x3A, if (trayOpen) 0x02 else 0x01)
        becomingReadyPolls > 0 -> {
            becomingReadyPolls--
            check(0x2, 0x04, 0x01)
        }
        else -> Reply(Reply.GOOD)
    }

    private fun startStop(cdb: ByteArray): Reply {
        val b4 = cdb[4].toInt() and 0xFF
        val loej = b4 and 0x02 != 0
        val start = b4 and 0x01 != 0
        if (loej && !start) openTray() else if (loej && start) closeTray()
        return Reply(Reply.GOOD)
    }

    private fun gesn(cdb: ByteArray): Reply {
        if (!supportsGesn || (cdb[1].toInt() and 0x01) == 0) return check(0x5, 0x24, 0x00)
        val requested = cdb[4].toInt() and 0xFF
        if (requested and 0x10 == 0) {
            return good(byteArrayOf(0x00, 0x02, 0x80.toByte(), 0x5E), allocation10(cdb))
        }
        val event = pendingMediaEvents.removeFirstOrNull() ?: 0
        val status = (if (mediumPresent) 0x02 else 0) or (if (trayOpen) 0x01 else 0)
        val data = byteArrayOf(0x00, 0x06, 0x04, 0x5E, event.toByte(), status.toByte(), 0, 0)
        return good(data, allocation10(cdb))
    }

    private fun inquiry(): ByteArray {
        val d = ByteArray(36)
        d[0] = 0x05 // 外设类型 05h = CD/DVD
        d[1] = 0x80.toByte() // RMB
        d[2] = 0x00
        d[3] = 0x32
        d[4] = 31
        fun put(at: Int, s: String, len: Int) = s.padEnd(len).take(len).toByteArray(Charsets.US_ASCII).copyInto(d, at)
        put(8, vendor, 8)
        put(16, product, 16)
        put(32, revision, 4)
        return d
    }

    private fun configuration(cdb: ByteArray): ByteArray {
        val current = if (mediumPresent && becomingReadyPolls == 0) discProfile ?: 0 else 0
        val features = mutableListOf<ByteArray>()
        // 0000h Profile List
        val profiles = ByteArray(supportedProfiles.size * 4)
        supportedProfiles.forEachIndexed { i, p ->
            profiles[i * 4] = (p shr 8).toByte()
            profiles[i * 4 + 1] = p.toByte()
            profiles[i * 4 + 2] = if (p == current) 1 else 0
        }
        features += feature(0x0000, current = true, data = profiles)
        // 0001h Core：Serial ATAPI，DBE
        features += feature(0x0001, current = true, data = byteArrayOf(0, 0, 0, 7, 1, 0, 0, 0))
        // 0003h Removable Medium：托盘，可弹出
        features += feature(0x0003, current = true, data = byteArrayOf(0x29, 0, 0, 0))
        // 001Eh CD Read：C2、CD-Text
        features += feature(0x001E, current = current in 0x08..0x0A, data = byteArrayOf(0x03, 0, 0, 0))
        // 0106h DVD CSS：版本 1
        features += feature(0x0106, current = current == 0x10, data = byteArrayOf(0, 0, 0, 1))
        // 010Dh AACS：BNG，AGID 数 4，版本 1
        features += feature(0x010D, current = current == 0x40, data = byteArrayOf(1, 1, 4, 1))
        val body = features.fold(ByteArray(0)) { acc, f -> acc + f }
        val header = ByteArray(8)
        val dataLength = 4 + body.size
        header[0] = (dataLength ushr 24).toByte()
        header[1] = (dataLength ushr 16).toByte()
        header[2] = (dataLength ushr 8).toByte()
        header[3] = dataLength.toByte()
        header[6] = (current shr 8).toByte()
        header[7] = current.toByte()
        return header + body
    }

    private fun feature(code: Int, current: Boolean, data: ByteArray, version: Int = 0): ByteArray =
        byteArrayOf((code shr 8).toByte(), code.toByte(), ((version shl 2) or 0x02 or (if (current) 1 else 0)).toByte(), data.size.toByte()) + data

    private fun modeSense2A(): ByteArray {
        val page = ByteArray(32)
        page[0] = 0x2A
        page[1] = 30
        page[2] = 0x3B // CD-R/RW 读、DVD-ROM/R/RAM 读
        page[3] = 0x37 // CD-R/RW 写、测试写、DVD-R/RAM 写
        page[4] = 0x71
        page[5] = 0x13.toByte() // CD-DA 命令、精确流、C2
        page[6] = 0x29 // 托盘、可弹出、锁定
        page[8] = 0x10 // 最大读速 4234 kB/s（≈24x）
        page[9] = 0x8A.toByte()
        page[12] = 0x04 // 缓存 1024 KB
        page[13] = 0x00
        val header = byteArrayOf(0, (6 + page.size).toByte(), 0, 0, 0, 0, 0, 0)
        return header + page
    }

    private fun mechanismStatus(): ByteArray {
        val b1 = if (trayOpen) 0x10 else 0
        return byteArrayOf(0, b1.toByte(), 0, 0, 0, 0, 0, 0)
    }

    private fun allocation6(cdb: ByteArray) = cdb[4].toInt() and 0xFF

    /** 10 字节 CDB（GET CONFIGURATION、MODE SENSE(10)、GESN）：分配长度在字节 7–8。 */
    private fun allocation10(cdb: ByteArray): Int = ((cdb[7].toInt() and 0xFF) shl 8) or (cdb[8].toInt() and 0xFF)

    /** 12 字节 CDB（MECHANISM STATUS、REPORT KEY）：分配长度在字节 8–9。 */
    private fun allocation12(cdb: ByteArray): Int = ((cdb[8].toInt() and 0xFF) shl 8) or (cdb[9].toInt() and 0xFF)

    private fun good(data: ByteArray, allocation: Int) = Reply(Reply.GOOD, data.copyOf(minOf(data.size, allocation)))

    private fun check(key: Int, asc: Int, ascq: Int): Reply {
        sense = senseBytes(key, asc, ascq)
        return Reply(Reply.CHECK_CONDITION)
    }

    companion object {
        /** 固定格式 sense，18 字节。 */
        fun senseBytes(key: Int, asc: Int, ascq: Int): ByteArray {
            val s = ByteArray(18)
            s[0] = 0x70
            s[2] = key.toByte()
            s[7] = 10
            s[12] = asc.toByte()
            s[13] = ascq.toByte()
            return s
        }
    }
}
