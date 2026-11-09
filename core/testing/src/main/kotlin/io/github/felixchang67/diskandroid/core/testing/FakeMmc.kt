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
    var discProfile: Int? = null
    var becomingReadyPolls = 0
    val pendingUnitAttention = ArrayDeque<Pair<Int, Int>>()
    val pendingMediaEvents = ArrayDeque<Int>()
    val received = mutableListOf<ByteArray>()
    private var sense: ByteArray? = null

    val mediumPresent: Boolean get() = !trayOpen && discProfile != null

    fun powerOn() {
        pendingUnitAttention.addLast(0x29 to 0x00)
    }

    /** 用户把盘放进托盘并推回去。 */
    fun insertDisc(profile: Int, spinUpPolls: Int = 2) {
        discProfile = profile
        closeTray(spinUpPolls)
    }

    fun closeTray(spinUpPolls: Int = 2) {
        trayOpen = false
        if (discProfile != null) {
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
        discProfile = null
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
            else -> check(0x5, 0x20, 0x00)
        }
    }

    private fun testUnitReady(): Reply = when {
        discProfile == null || trayOpen -> check(0x2, 0x3A, if (trayOpen) 0x02 else 0x01)
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
