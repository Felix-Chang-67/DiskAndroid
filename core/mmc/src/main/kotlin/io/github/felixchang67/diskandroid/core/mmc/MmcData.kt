package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex

/** INQUIRY 标准数据（SPC-4 6.6.2）。 */
class InquiryData(
    val peripheralQualifier: Int,
    val deviceType: Int,
    val removable: Boolean,
    val version: Int,
    val responseDataFormat: Int,
    val additionalLength: Int,
    val vendor: String,
    val product: String,
    val revision: String,
    /** 字节 36–55 的厂商自定义区（很多光驱在这里放固件日期），没有则为空。 */
    val vendorSpecific: String,
) {
    /** 外设类型 05h = CD/DVD 设备（MMC）。 */
    val isMmcDevice: Boolean get() = deviceType == 0x05

    val displayName: String get() = listOf(vendor, product).filter { it.isNotBlank() }.joinToString(" ")

    fun deviceTypeName(): String = when (deviceType) {
        0x00 -> "直接存取设备（硬盘 / U 盘）"
        0x05 -> "CD/DVD 设备（MMC）"
        0x07 -> "光存储设备"
        0x1F -> "未知或无设备"
        else -> "类型 ${Hex.byte(deviceType)}h"
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): InquiryData? {
            if (length < 5) return null
            val b = Bytes(data, minOf(length, data.size))
            return InquiryData(
                peripheralQualifier = b.u8(0) shr 5,
                deviceType = b.u8(0) and 0x1F,
                removable = b.bit(1, 7),
                version = b.u8(2),
                responseDataFormat = b.u8(3) and 0x0F,
                additionalLength = b.u8(4),
                vendor = b.ascii(8, 8).trim(),
                product = b.ascii(16, 16).trim(),
                revision = b.ascii(32, 4).trim(),
                vendorSpecific = b.ascii(36, 20).trim(),
            )
        }
    }
}

/** GET CONFIGURATION 的一个特性描述符。 */
class Feature(
    val code: Int,
    val version: Int,
    val persistent: Boolean,
    val current: Boolean,
    val data: ByteArray,
) {
    val name: String get() = MmcNames.featureName(code)
    internal val bytes: Bytes get() = Bytes(data)
}

/** GET CONFIGURATION 的响应（MMC-6 6.6）：特性头 + 特性描述符。 */
class FeatureList(
    /** 特性头里的 DATA LENGTH（不含该字段本身的 4 字节）。 */
    val dataLength: Long,
    val currentProfile: Int,
    val features: List<Feature>,
    /** 响应是否被分配长度截断。 */
    val truncated: Boolean,
) {
    fun feature(code: Int): Feature? = features.firstOrNull { it.code == code }

    /** Profile List 特性里的所有 profile（profile 号 → 是否当前）。 */
    val profiles: List<Pair<Int, Boolean>>
        get() {
            val f = feature(0x0000) ?: return emptyList()
            val b = f.bytes
            return (0 until f.data.size / 4).map { i -> b.be16(i * 4) to b.bit(i * 4 + 2, 0) }
        }

    /** Core（0001h）：物理接口标准。 */
    val physicalInterface: Long? get() = feature(0x0001)?.takeIf { it.data.size >= 4 }?.bytes?.be32(0)

    /** Removable Medium（0003h）：装载机构、可弹出、锁定。 */
    val loadingMechanism: Int? get() = feature(0x0003)?.takeIf { it.data.isNotEmpty() }?.let { it.bytes.u8(0) shr 5 }
    val canEject: Boolean? get() = feature(0x0003)?.takeIf { it.data.isNotEmpty() }?.bytes?.bit(0, 3)
    val locked: Boolean? get() = feature(0x0003)?.takeIf { it.data.isNotEmpty() }?.bytes?.bit(0, 0)

    /** CD Read（001Eh）：DAP、C2 错误指针、CD-Text。 */
    val cdReadDap: Boolean? get() = feature(0x001E)?.takeIf { it.data.isNotEmpty() }?.bytes?.bit(0, 7)
    val cdReadC2: Boolean? get() = feature(0x001E)?.takeIf { it.data.isNotEmpty() }?.bytes?.bit(0, 1)
    val cdReadCdText: Boolean? get() = feature(0x001E)?.takeIf { it.data.isNotEmpty() }?.bytes?.bit(0, 0)

    /** DVD CSS（0106h）：CSS 版本。 */
    val cssVersion: Int? get() = feature(0x0106)?.takeIf { it.data.size >= 4 }?.bytes?.u8(3)

    /** AACS（010Dh）：BNG、绑定 nonce 块数、AGID 数、AACS 版本。 */
    val aacs: AacsFeature?
        get() = feature(0x010D)?.takeIf { it.data.size >= 4 }?.let {
            val b = it.bytes
            AacsFeature(b.bit(0, 0), b.u8(1), b.u8(2) and 0x0F, b.u8(3), it.current)
        }

    /** Real Time Streaming（0107h）的标志位文本。 */
    val realTimeStreaming: String?
        get() = feature(0x0107)?.takeIf { it.data.isNotEmpty() }?.let {
            val v = it.bytes.u8(0)
            listOf(4 to "RBCB", 3 to "SCS", 2 to "MP2A", 1 to "WSPD", 0 to "SW").filter { (bit, _) -> (v shr bit) and 1 == 1 }
                .joinToString(" ") { p -> p.second }.ifEmpty { "无扩展" }
        }

    /** Drive Serial Number（0108h）。 */
    val driveSerialNumber: String? get() = feature(0x0108)?.let { Bytes(it.data).ascii(0, it.data.size).trim() }

    /** Firmware Information（010Ch）：14 个 ASCII 数字 世纪年月日时分秒。 */
    val firmwareDate: String?
        get() = feature(0x010C)?.takeIf { it.data.size >= 14 }?.let {
            val s = Bytes(it.data).ascii(0, 14)
            if (s.length == 14 && s.all { c -> c.isDigit() }) {
                "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}:${s.substring(12, 14)}"
            } else {
                s
            }
        }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): FeatureList? {
            val n = minOf(length, data.size)
            if (n < 8) return null
            val b = Bytes(data, n)
            val dataLength = b.be32(0)
            val end = minOf(n.toLong(), dataLength + 4).toInt()
            val features = mutableListOf<Feature>()
            var pos = 8
            while (pos + 4 <= end) {
                val add = b.u8(pos + 3)
                val bodyEnd = minOf(pos + 4 + add, end)
                features += Feature(
                    code = b.be16(pos),
                    version = (b.u8(pos + 2) shr 2) and 0x0F,
                    persistent = b.bit(pos + 2, 1),
                    current = b.bit(pos + 2, 0),
                    data = b.slice(pos + 4, bodyEnd - (pos + 4)),
                )
                pos += 4 + add
            }
            return FeatureList(dataLength, b.be16(6), features, truncated = dataLength + 4 > n)
        }
    }
}

class AacsFeature(
    val bindingNonceGeneration: Boolean,
    val bindingNonceBlockCount: Int,
    val agidCount: Int,
    val version: Int,
    val current: Boolean,
)

/** MODE SENSE 2Ah 页：MM Capabilities and Mechanical Status（MMC-3；MMC-5 起列为旧特性，但光驱普遍仍支持）。 */
class CapabilitiesPage(
    val pageLength: Int,
    val readCdR: Boolean,
    val readCdRw: Boolean,
    val readDvdRom: Boolean,
    val readDvdR: Boolean,
    val readDvdRam: Boolean,
    val writeCdR: Boolean,
    val writeCdRw: Boolean,
    val testWrite: Boolean,
    val writeDvdR: Boolean,
    val writeDvdRam: Boolean,
    val bufferUnderrunFree: Boolean,
    val multiSession: Boolean,
    val mode2Form1: Boolean,
    val mode2Form2: Boolean,
    val audioPlay: Boolean,
    val cdDaCommands: Boolean,
    val cdDaAccurate: Boolean,
    val c2Pointers: Boolean,
    val rwSupported: Boolean,
    val rwDeinterleaved: Boolean,
    val isrc: Boolean,
    val upc: Boolean,
    val loadingMechanism: Int,
    val eject: Boolean,
    val preventJumper: Boolean,
    val lockState: Boolean,
    val lock: Boolean,
    /** 已废弃字段：最大读速（kB/s），很多光驱仍会填。 */
    val maxReadSpeedKBps: Int,
    val bufferSizeKB: Int,
    val currentReadSpeedKBps: Int,
) {
    companion object {
        /** 解析 MODE SENSE(10) 的完整响应（8 字节头 + 块描述符 + 页）。 */
        fun parse(data: ByteArray, length: Int = data.size): CapabilitiesPage? {
            val n = minOf(length, data.size)
            if (n < 8) return null
            val b = Bytes(data, n)
            val offset = 8 + b.be16(6) // 头 + 块描述符（Linux sr.c：header_length + block_descriptor_length）
            if (offset + 2 > n || (b.u8(offset) and 0x3F) != 0x2A) return null
            val p = offset
            return CapabilitiesPage(
                pageLength = b.u8(p + 1),
                readCdR = b.bit(p + 2, 0),
                readCdRw = b.bit(p + 2, 1),
                readDvdRom = b.bit(p + 2, 3),
                readDvdR = b.bit(p + 2, 4),
                readDvdRam = b.bit(p + 2, 5),
                writeCdR = b.bit(p + 3, 0),
                writeCdRw = b.bit(p + 3, 1),
                testWrite = b.bit(p + 3, 2),
                writeDvdR = b.bit(p + 3, 4),
                writeDvdRam = b.bit(p + 3, 5),
                bufferUnderrunFree = b.bit(p + 4, 7),
                multiSession = b.bit(p + 4, 6),
                mode2Form2 = b.bit(p + 4, 5),
                mode2Form1 = b.bit(p + 4, 4),
                audioPlay = b.bit(p + 4, 0),
                cdDaCommands = b.bit(p + 5, 0),
                cdDaAccurate = b.bit(p + 5, 1),
                rwSupported = b.bit(p + 5, 2),
                rwDeinterleaved = b.bit(p + 5, 3),
                c2Pointers = b.bit(p + 5, 4),
                isrc = b.bit(p + 5, 5),
                upc = b.bit(p + 5, 6),
                loadingMechanism = b.u8(p + 6) shr 5,
                eject = b.bit(p + 6, 3),
                preventJumper = b.bit(p + 6, 2),
                lockState = b.bit(p + 6, 1),
                lock = b.bit(p + 6, 0),
                maxReadSpeedKBps = b.be16(p + 8),
                bufferSizeKB = b.be16(p + 12),
                currentReadSpeedKBps = b.be16(p + 14),
            )
        }
    }
}

/** GET EVENT STATUS NOTIFICATION 的结果（媒体类）。与 Linux struct event_header / media_event_desc 一致。 */
class EventStatus(
    val noEventAvailable: Boolean,
    val notificationClass: Int,
    val supportedClasses: Int,
    /** 仅当 notificationClass == 4（媒体）且数据完整时有值。 */
    val mediaEventCode: Int?,
    val mediaPresent: Boolean?,
    val trayOpen: Boolean?,
) {
    val supportsMediaClass: Boolean get() = (supportedClasses shr 4) and 1 == 1

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): EventStatus? {
            val n = minOf(length, data.size)
            if (n < 4) return null
            val b = Bytes(data, n)
            val descriptorLength = b.be16(0)
            val nea = b.bit(2, 7)
            val cls = b.u8(2) and 0x07
            val hasMedia = !nea && cls == 4 && descriptorLength >= 6 && n >= 8
            return EventStatus(
                noEventAvailable = nea,
                notificationClass = cls,
                supportedClasses = b.u8(3),
                mediaEventCode = if (hasMedia) b.u8(4) and 0x0F else null,
                mediaPresent = if (hasMedia) b.bit(5, 1) else null,
                trayOpen = if (hasMedia) b.bit(5, 0) else null,
            )
        }
    }
}

/** MECHANISM STATUS 头（8 字节）。与 Linux struct cdrom_mechstat_header 一致。 */
class MechanismStatus(
    val fault: Boolean,
    val changerState: Int,
    val currentSlot: Int,
    val mechanismState: Int,
    val doorOpen: Boolean,
    val slots: Int,
) {
    fun mechanismStateName(): String = when (mechanismState) {
        0 -> "空闲"
        1 -> "正在播放音频"
        2 -> "正在扫描"
        3 -> "主机或复合操作中"
        7 -> "无状态信息"
        else -> "保留（$mechanismState）"
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): MechanismStatus? {
            val n = minOf(length, data.size)
            if (n < 8) return null
            val b = Bytes(data, n)
            return MechanismStatus(
                fault = b.bit(0, 7),
                changerState = (b.u8(0) shr 5) and 0x03,
                currentSlot = b.u8(0) and 0x1F,
                mechanismState = b.u8(1) shr 5,
                doorOpen = b.bit(1, 4),
                slots = b.u8(5),
            )
        }
    }
}

/** REPORT KEY 08h 的 RPC（DVD 地区码）状态。与 Linux rpc_state_t、libdvdcss ioctl_ReportRPC 一致。 */
class RpcState(
    /** 0 = 未设置地区；1 = 已设置；2 = 已设置，再改需额外限制；3 = 已永久设置。 */
    val typeCode: Int,
    val vendorResetsAvailable: Int,
    val userChangesAvailable: Int,
    /** 某位为 0 表示该地区可播放（bit0 = 地区 1）。 */
    val regionMask: Int,
    /** 0 = RPC-I（或未知），1 = RPC-II。 */
    val rpcScheme: Int,
) {
    /** 当前可播放的地区（1..8）。 */
    val regions: List<Int> get() = (0 until 8).filter { (regionMask shr it) and 1 == 0 }.map { it + 1 }

    fun typeName(): String = when (typeCode) {
        0 -> "未设置地区"
        1 -> "已设置地区"
        2 -> "已设置地区（再修改需要额外条件）"
        3 -> "地区已永久设置"
        else -> "未知"
    }

    fun schemeName(): String = when (rpcScheme) {
        0 -> "RPC-I 或未知"
        1 -> "RPC-II（光驱强制地区码）"
        else -> "保留（$rpcScheme）"
    }

    fun summary(): String {
        val r = regions
        val regionText = when {
            r.isEmpty() -> "无可播放地区"
            r.size == 8 -> "全部地区"
            else -> "地区 " + r.joinToString("、")
        }
        return "${schemeName()}，${typeName()}，$regionText，用户还可修改 $userChangesAvailable 次，厂商重置还剩 $vendorResetsAvailable 次"
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): RpcState? {
            val n = minOf(length, data.size)
            if (n < 7) return null
            val b = Bytes(data, n)
            return RpcState(
                typeCode = b.u8(4) shr 6,
                vendorResetsAvailable = (b.u8(4) shr 3) and 0x07,
                userChangesAvailable = b.u8(4) and 0x07,
                regionMask = b.u8(5),
                rpcScheme = b.u8(6),
            )
        }
    }
}
