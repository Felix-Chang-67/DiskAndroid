package io.github.felixchang67.diskandroid.core.drive

import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.mmc.CapabilitiesPage
import io.github.felixchang67.diskandroid.core.mmc.EventStatus
import io.github.felixchang67.diskandroid.core.mmc.FeatureList
import io.github.felixchang67.diskandroid.core.mmc.InquiryData
import io.github.felixchang67.diskandroid.core.mmc.MechanismStatus
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.mmc.RpcState

enum class TrayState(val label: String) { OPEN("托盘已打开"), CLOSED("托盘已关闭"), UNKNOWN("托盘状态未知") }

/** 盘片状态。 */
sealed interface MediaStatus {
    data object Unknown : MediaStatus
    data class NoMedium(val tray: TrayState) : MediaStatus
    data class BecomingReady(val sinceMs: Long) : MediaStatus
    data class Ready(val profile: Int) : MediaStatus
    data class NotReady(val reason: String) : MediaStatus

    fun label(): String = when (this) {
        Unknown -> "未知"
        is NoMedium -> "无盘（${tray.label}）"
        is BecomingReady -> "正在读取盘片…"
        is Ready -> "就绪：${MmcNames.profileName(profile)}"
        is NotReady -> "未就绪：$reason"
    }
}

/** 会话状态。 */
sealed interface DriveState {
    data object Initializing : DriveState
    data object Online : DriveState
    data class Failed(val reason: String) : DriveState
    data object Disconnected : DriveState
    data object Closed : DriveState

    fun label(): String = when (this) {
        Initializing -> "正在初始化"
        Online -> "已连接"
        is Failed -> "出错：$reason"
        Disconnected -> "已断开"
        Closed -> "已关闭"
    }
}

class DriveEvent(val timeMs: Long, val kind: Kind, val message: String) {
    enum class Kind { MEDIA_INSERTED, MEDIA_REMOVED, TRAY, EJECT_REQUEST, UNEXPECTED_RESET, ERROR, INFO }

    override fun toString() = "${LogFormat.time(timeMs)} $message"
}

/** 光驱的静态信息（INQUIRY、GET CONFIGURATION、2Ah 页、RPC、机构状态、GESN 支持情况）。 */
class DriveInfo(
    val inquiry: InquiryData?,
    val features: FeatureList?,
    val capabilities: CapabilitiesPage?,
    val rpc: RpcState?,
    val mechanism: MechanismStatus?,
    val gesn: EventStatus?,
    /** 命令名 → 原始响应（写进诊断信息，方便核对解析）。 */
    val raw: Map<String, ByteArray>,
    /** 命令名 → 失败原因。 */
    val errors: Map<String, String>,
) {
    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        inquiry?.let {
            out += "光驱：${it.vendor} ${it.product}，固件 ${it.revision}" + (if (it.vendorSpecific.isNotEmpty()) "（${it.vendorSpecific}）" else "")
            out += "  外设类型 ${Hex.byte(it.deviceType)}h ${it.deviceTypeName()}，可移动介质 ${yes(it.removable)}，SPC 版本 ${it.version}"
            if (it.ataString.isNotEmpty()) out += "  桥接芯片附加信息（ATA 字节序还原）：${it.ataString}"
        } ?: run { out += "光驱：INQUIRY 失败" }
        features?.let { f ->
            out += "  当前 profile：${Hex.word(f.currentProfile)}h ${MmcNames.profileName(f.currentProfile)}" + if (f.truncated) "（特性列表被截断）" else ""
            f.physicalInterface?.let { out += "  物理接口：${MmcNames.physicalInterface(it)}" }
            out += "  支持的盘片：" + f.profiles.joinToString("、") { (p, cur) -> MmcNames.profileName(p) + if (cur) "（当前）" else "" }
            f.loadingMechanism?.let {
                out += "  装载机构：${MmcNames.loadingMechanism(it)}，可弹出 ${yes(f.canEject)}，支持锁定盘片 ${yes(f.canLock)}" +
                    (capabilities?.let { c -> "，当前已锁定 ${yes(c.lockState)}" } ?: "")
            }
            if (f.feature(0x001E) != null) out += "  CD 读取：CD-Text ${yes(f.cdReadCdText)}，C2 错误指针 ${yes(f.cdReadC2)}，DAP ${yes(f.cdReadDap)}"
            out += "  DVD 读取：${yes(f.feature(0x001F) != null)}；BD 读取：${yes(f.feature(0x0040) != null)}；实时流：${f.realTimeStreaming ?: "否"}"
            out += "  DVD CSS：" + (f.cssVersion?.let { "支持（版本 $it）" } ?: "不支持")
            out += "  AACS：" + (
                f.aacs?.let {
                    "支持（版本 ${it.version}，AGID ${it.agidCount} 个，总线加密 ${yes(it.busEncryption)}，" +
                        "可读光驱证书 ${yes(it.readDriveCertificate)}，绑定 nonce ${yes(it.bindingNonceGeneration)}）"
                } ?: "不支持"
                )
            val writes = listOf(0x002D to "CD TAO", 0x002E to "CD SAO", 0x002F to "DVD-R/RW", 0x002B to "DVD+R", 0x002A to "DVD+RW", 0x003B to "DVD+R DL", 0x0041 to "BD-R/RE")
                .filter { (code, _) -> f.feature(code) != null }.map { it.second }
            out += "  刻录能力（仅信息，阶段 1 不会使用）：" + writes.ifEmpty { listOf("无") }.joinToString("、")
            f.firmwareDate?.let { out += "  固件日期：$it" }
            f.driveSerialNumber?.takeIf { it.isNotEmpty() }?.let { out += "  光驱序列号：$it" }
            out += "  特性列表：" + f.features.joinToString(" ") { Hex.word(it.code) + (if (it.current) "*" else "") }
        }
        capabilities?.let { c ->
            out += "  2Ah 页：读 CD-R ${yes(c.readCdR)} CD-RW ${yes(c.readCdRw)} DVD-ROM ${yes(c.readDvdRom)} DVD-R ${yes(c.readDvdR)} DVD-RAM ${yes(c.readDvdRam)}；" +
                "CD-DA 命令 ${yes(c.cdDaCommands)}，CD-DA 精确流 ${yes(c.cdDaAccurate)}，C2 ${yes(c.c2Pointers)}，R-W 子通道 ${yes(c.rwSupported)}"
            out += "  缓存 ${c.bufferSizeKB} KB，最大读速（旧字段）${c.maxReadSpeedKBps} kB/s，当前读速 ${c.currentReadSpeedKBps} kB/s，Mode 2 Form 1/2 ${yes(c.mode2Form1)}/${yes(c.mode2Form2)}"
        }
        rpc?.let { out += "  DVD 地区码：${it.summary()}" }
        mechanism?.let { out += "  机构状态：${it.mechanismStateName()}，托盘${if (it.doorOpen) "打开" else "关闭"}，故障 ${yes(it.fault)}" }
        gesn?.let { out += "  事件通知（GESN）：支持 ${MmcNames.eventClasses(it.supportedClasses)}" } ?: run { out += "  事件通知（GESN）：不支持，改用 TEST UNIT READY 轮询" }
        errors.forEach { (k, v) -> out += "  ✗ $k：$v" }
        return out
    }

    fun rawLines(): List<String> = raw.flatMap { (name, bytes) ->
        listOf("  $name（${bytes.size} 字节）：") + Hex.dump(bytes).lines().map { "    $it" }
    }

    private fun yes(b: Boolean?) = when (b) {
        true -> "是"
        false -> "否"
        null -> "?"
    }
}
