package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand

/**
 * MMC / SPC 命令的 CDB 构造。字段布局依据 SPC-4、MMC-6，并与 Linux（drivers/cdrom、drivers/scsi/sr.c）、
 * libburn、libdvdcss 中已在真实光驱上验证过的写法交叉核对。阶段 1 只有读类命令。
 */
object MmcCommands {
    /** TEST UNIT READY（00h）。 */
    fun testUnitReady(quiet: Boolean = false) =
        ScsiCommand.noData(ByteArray(6), "TEST UNIT READY", timeoutMs = 10_000, quiet = quiet)

    /**
     * INQUIRY（12h），标准数据。分配长度 ≤ 255 时字节 3 为 0，兼容只认字节 4 的老设备。
     * 默认 36 字节：Linux 对 USB 存储设备也只取 36 字节，个别桥接芯片对更长的 INQUIRY 处理不好。
     */
    fun inquiry(allocation: Int = 36): ScsiCommand {
        require(allocation in 5..255)
        return ScsiCommand.dataIn(byteArrayOf(0x12, 0, 0, 0, allocation.toByte(), 0), allocation, "INQUIRY", timeoutMs = 5_000)
    }

    /**
     * GET CONFIGURATION（46h）。
     * @param requestType 0 = 全部特性，1 = 当前特性，2 = 只要 [startingFeature] 这一个。
     */
    fun getConfiguration(requestType: Int, startingFeature: Int, allocation: Int, quiet: Boolean = false): ScsiCommand {
        require(requestType in 0..2 && allocation in 8..0xFFFF)
        val cdb = ByteArray(10)
        cdb[0] = 0x46
        cdb[1] = requestType.toByte()
        cdb[2] = (startingFeature shr 8).toByte()
        cdb[3] = startingFeature.toByte()
        cdb[7] = (allocation shr 8).toByte()
        cdb[8] = allocation.toByte()
        return ScsiCommand.dataIn(cdb, allocation, "GET CONFIGURATION", timeoutMs = 10_000, quiet = quiet)
    }

    /** MODE SENSE(10)（5Ah），PC=0（当前值）。Linux sr.c 也用 DBD=0 读 2Ah 页。 */
    fun modeSense10(page: Int, allocation: Int = 256): ScsiCommand {
        val cdb = ByteArray(10)
        cdb[0] = 0x5A
        cdb[2] = (page and 0x3F).toByte()
        cdb[7] = (allocation shr 8).toByte()
        cdb[8] = allocation.toByte()
        return ScsiCommand.dataIn(cdb, allocation, "MODE SENSE(10) 页 ${Hex.byte(page)}h", timeoutMs = 10_000)
    }

    /**
     * GET EVENT STATUS NOTIFICATION（4Ah），轮询方式（Polled=1，不支持异步）。
     * @param classes 通知类请求位图，默认只要 Media 类（bit 4）。与 Linux cdrom_get_media_event 相同。
     */
    fun getEventStatus(classes: Int = 0x10, allocation: Int = 8, quiet: Boolean = false): ScsiCommand {
        val cdb = ByteArray(10)
        cdb[0] = 0x4A
        cdb[1] = 0x01
        cdb[4] = classes.toByte()
        cdb[7] = (allocation shr 8).toByte()
        cdb[8] = allocation.toByte()
        return ScsiCommand.dataIn(cdb, allocation, "GET EVENT STATUS NOTIFICATION", timeoutMs = 5_000, quiet = quiet)
    }

    /** MECHANISM STATUS（BDh），12 字节 CDB，分配长度在字节 8–9。 */
    fun mechanismStatus(allocation: Int = 8): ScsiCommand {
        val cdb = ByteArray(12)
        cdb[0] = 0xBD.toByte()
        cdb[8] = (allocation shr 8).toByte()
        cdb[9] = allocation.toByte()
        return ScsiCommand.dataIn(cdb, allocation, "MECHANISM STATUS", timeoutMs = 5_000)
    }

    /**
     * REPORT KEY（A4h），KEY CLASS 00h，KEY FORMAT 08h：读取 RPC（地区码）状态，8 字节。
     * 与 libdvdcss ioctl_ReportRPC 相同。只读，不会修改地区设置。
     */
    fun reportRpcState(): ScsiCommand {
        val cdb = ByteArray(12)
        cdb[0] = 0xA4.toByte()
        cdb[7] = 0x00
        cdb[8] = 0
        cdb[9] = 8
        cdb[10] = 0x08
        return ScsiCommand.dataIn(cdb, 8, "REPORT KEY（RPC 状态）", timeoutMs = 10_000)
    }

    /**
     * START STOP UNIT（1Bh）。弹出：LoEj=1、Start=0；装载（关托盘）：LoEj=1、Start=1。
     * POWER CONDITION 恒为 0。与 Linux sr_tray_move 相同（字节 4 = 02h / 03h）。
     */
    fun startStopUnit(loadEject: Boolean, start: Boolean, immediate: Boolean = false): ScsiCommand {
        val cdb = ByteArray(6)
        cdb[0] = 0x1B
        cdb[1] = if (immediate) 1 else 0
        cdb[4] = ((if (loadEject) 0x02 else 0) or (if (start) 0x01 else 0)).toByte()
        val name = when {
            loadEject && !start -> "START STOP UNIT（弹出）"
            loadEject && start -> "START STOP UNIT（装载）"
            start -> "START STOP UNIT（启动）"
            else -> "START STOP UNIT（停止）"
        }
        return ScsiCommand.noData(cdb, name, timeoutMs = 30_000)
    }

    /** INQUIRY 扩展：读取厂商自定义区（字节 36 起）。分配长度取设备声明的 ADDITIONAL LENGTH + 5，最多 96。 */
    fun inquiryFull(allocation: Int): ScsiCommand {
        require(allocation in 36..255)
        return ScsiCommand.dataIn(byteArrayOf(0x12, 0, 0, 0, allocation.toByte(), 0), allocation, "INQUIRY（完整）", timeoutMs = 5_000)
    }

    /** READ CAPACITY(10)（25h）：最后一个 LBA + 块长度，共 8 字节。 */
    fun readCapacity() = ScsiCommand.dataIn(ByteArray(10).also { it[0] = 0x25 }, 8, "READ CAPACITY", timeoutMs = 10_000)

    /** READ DISC INFORMATION（51h），Data Type 000b（标准盘片信息）。 */
    fun readDiscInformation(allocation: Int): ScsiCommand {
        val cdb = ByteArray(10)
        cdb[0] = 0x51
        cdb[7] = (allocation shr 8).toByte()
        cdb[8] = allocation.toByte()
        return ScsiCommand.dataIn(cdb, allocation, "READ DISC INFORMATION", timeoutMs = 10_000)
    }

    /**
     * READ TRACK INFORMATION（52h）。
     * @param addressType 0 = LBA，1 = 轨道号，2 = 区段号。
     */
    fun readTrackInformation(addressType: Int, value: Long, allocation: Int): ScsiCommand {
        require(addressType in 0..2)
        val cdb = ByteArray(10)
        cdb[0] = 0x52
        cdb[1] = addressType.toByte()
        putBe32(cdb, 2, value)
        cdb[7] = (allocation shr 8).toByte()
        cdb[8] = allocation.toByte()
        return ScsiCommand.dataIn(cdb, allocation, "READ TRACK INFORMATION", timeoutMs = 10_000)
    }

    /**
     * READ TOC/PMA/ATIP（43h）。与 Linux sr_read_tocentry / libburn 一致：字节 1 的 bit 1 = MSF，字节 2 = 格式，
     * 字节 6 = 轨道/区段号，字节 7–8 = 分配长度，字节 9（Control）恒为 0。
     * @param format 0 = TOC，1 = 区段信息，2 = 完整 TOC，5 = CD-TEXT。
     */
    fun readToc(format: Int, msf: Boolean, trackOrSession: Int, allocation: Int): ScsiCommand {
        require(format in 0..5 && trackOrSession in 0..0xFF)
        val cdb = ByteArray(10)
        cdb[0] = 0x43
        cdb[1] = if (msf) 0x02 else 0
        cdb[2] = format.toByte()
        cdb[6] = trackOrSession.toByte()
        cdb[7] = (allocation shr 8).toByte()
        cdb[8] = allocation.toByte()
        val name = when (format) {
            0 -> "READ TOC（格式 0 TOC）"
            1 -> "READ TOC（格式 1 区段信息）"
            2 -> "READ TOC（格式 2 完整 TOC）"
            5 -> "READ TOC（格式 5 CD-TEXT）"
            else -> "READ TOC（格式 $format）"
        }
        return ScsiCommand.dataIn(cdb, allocation, name, timeoutMs = 10_000)
    }

    /**
     * READ DISC STRUCTURE（ADh）。与 Linux dvd_read_physical / dvd_read_copyright 一致：
     * 字节 1 = 介质类型（0 = DVD/HD DVD，1 = BD），字节 2–5 = 地址，字节 6 = 层号，字节 7 = 格式，字节 8–9 = 分配长度。
     * 阶段 1 不读取密钥类格式（只读守卫会拦截）。
     */
    fun readDiscStructure(mediaType: Int, format: Int, allocation: Int, layer: Int = 0, address: Long = 0): ScsiCommand {
        val cdb = ByteArray(12)
        cdb[0] = 0xAD.toByte()
        cdb[1] = (mediaType and 0x0F).toByte()
        putBe32(cdb, 2, address)
        cdb[6] = layer.toByte()
        cdb[7] = format.toByte()
        cdb[8] = (allocation shr 8).toByte()
        cdb[9] = allocation.toByte()
        val media = if (mediaType == 1) "BD" else "DVD"
        return ScsiCommand.dataIn(cdb, allocation, "READ DISC STRUCTURE（$media 格式 ${Hex.byte(format)}h）", timeoutMs = 10_000)
    }

    /**
     * GET PERFORMANCE（ACh），Type 00h：名义读取性能（Data Type 10h：容差 10%、读、Except=00），与 libburn 相同。
     * 返回 8 字节头 + 每个描述符 16 字节。
     */
    fun getReadPerformance(maxDescriptors: Int): ScsiCommand {
        require(maxDescriptors in 1..255)
        val cdb = ByteArray(12)
        cdb[0] = 0xAC.toByte()
        cdb[1] = 0x10
        cdb[8] = (maxDescriptors shr 8).toByte()
        cdb[9] = maxDescriptors.toByte()
        cdb[10] = 0x00
        return ScsiCommand.dataIn(cdb, 8 + 16 * maxDescriptors, "GET PERFORMANCE（读取速度）", timeoutMs = 10_000)
    }

    private fun putBe32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }
}
