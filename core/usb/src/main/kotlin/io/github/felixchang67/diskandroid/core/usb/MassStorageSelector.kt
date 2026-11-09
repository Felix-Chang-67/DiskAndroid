package io.github.felixchang67.diskandroid.core.usb

import io.github.felixchang67.diskandroid.core.log.Hex

/** 与 Android 无关的接口/端点模型，便于单测。 */
data class UsbEndpointModel(val address: Int, val type: Int, val maxPacketSize: Int) {
    val isIn: Boolean get() = (address and 0x80) != 0
    val isBulk: Boolean get() = type == 2
}

data class UsbInterfaceModel(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointModel>,
)

sealed interface MassStorageSelection {
    /**
     * @property needsSetInterface 该接口有多个备用设置（例如同时提供 UAS），占用后需要显式 SET_INTERFACE 切到 BOT。
     */
    data class Selected(
        val iface: UsbInterfaceModel,
        val bulkIn: UsbEndpointModel,
        val bulkOut: UsbEndpointModel,
        val needsSetInterface: Boolean,
        val notes: List<String>,
    ) : MassStorageSelection {
        fun summary(): String =
            "接口 ${iface.id} 备用设置 ${iface.alternateSetting}（子类 ${Hex.byte(iface.interfaceSubclass)} " +
                "${UsbNames.massStorageSubclassName(iface.interfaceSubclass)} / 协议 ${Hex.byte(iface.interfaceProtocol)} " +
                "${UsbNames.massStorageProtocolName(iface.interfaceProtocol)}），bulk IN 0x${Hex.byte(bulkIn.address)}（${bulkIn.maxPacketSize}），" +
                "bulk OUT 0x${Hex.byte(bulkOut.address)}（${bulkOut.maxPacketSize}）"
    }

    data class Unsupported(val reason: String, val notes: List<String>) : MassStorageSelection

    data object NotMassStorage : MassStorageSelection
}

/**
 * 选择 USB 大容量存储的 Bulk-Only（BOT）接口：
 * - 只接受 class 08h + protocol 50h；子类优先 06h（SCSI 透明命令集），其次 02h/05h（个别光驱桥接芯片），并给出提示。
 * - 如果同一接口还有 UAS（protocol 62h）备用设置，仍然使用 BOT，并标记需要 SET_INTERFACE。
 * - 必须同时有 bulk IN 和 bulk OUT 端点。
 */
object MassStorageInterfaceSelector {
    fun select(interfaces: List<UsbInterfaceModel>): MassStorageSelection {
        val massStorage = interfaces.filter { it.interfaceClass == 0x08 }
        if (massStorage.isEmpty()) return MassStorageSelection.NotMassStorage

        val notes = mutableListOf<String>()
        val uas = massStorage.filter { it.interfaceProtocol == 0x62 }
        if (uas.isNotEmpty()) {
            notes += "设备同时提供 UAS（" + uas.joinToString { "接口 ${it.id} 备用设置 ${it.alternateSetting}" } + "），本 App 使用 BOT"
        }
        val bot = massStorage.filter { it.interfaceProtocol == 0x50 }
        if (bot.isEmpty()) {
            val reason = if (uas.isNotEmpty()) {
                "设备只提供 UAS 协议，暂不支持"
            } else {
                "不是 Bulk-Only 协议（" + massStorage.joinToString { "协议 ${Hex.byte(it.interfaceProtocol)}" } + "）"
            }
            return MassStorageSelection.Unsupported(reason, notes)
        }

        val ranked = bot.sortedBy {
            when (it.interfaceSubclass) {
                0x06 -> 0
                0x02, 0x05 -> 1
                else -> 2
            }
        }
        for (candidate in ranked) {
            val inEp = candidate.endpoints.firstOrNull { it.isBulk && it.isIn }
            val outEp = candidate.endpoints.firstOrNull { it.isBulk && !it.isIn }
            if (inEp == null || outEp == null) {
                notes += "接口 ${candidate.id} 备用设置 ${candidate.alternateSetting} 缺少 bulk IN/OUT 端点，跳过"
                continue
            }
            if (candidate.interfaceSubclass != 0x06) {
                notes += "子类 ${Hex.byte(candidate.interfaceSubclass)}（${UsbNames.massStorageSubclassName(candidate.interfaceSubclass)}）" +
                    "不是 SCSI 透明命令集 (06h)，按 MMC 兼容方式处理"
            }
            val altCount = interfaces.count { it.id == candidate.id }
            return MassStorageSelection.Selected(
                iface = candidate,
                bulkIn = inEp,
                bulkOut = outEp,
                needsSetInterface = altCount > 1 || candidate.alternateSetting != 0,
                notes = notes.toList(),
            )
        }
        return MassStorageSelection.Unsupported("找不到可用的 bulk IN/OUT 端点", notes)
    }

    /** 从解析出的原始描述符构造模型（取第一个配置）。 */
    fun modelsFrom(parsed: ParsedUsbDescriptors): List<UsbInterfaceModel> =
        parsed.configurations.firstOrNull()?.interfaces.orEmpty().map { i ->
            UsbInterfaceModel(
                id = i.number,
                alternateSetting = i.alternateSetting,
                interfaceClass = i.interfaceClass,
                interfaceSubclass = i.interfaceSubClass,
                interfaceProtocol = i.interfaceProtocol,
                endpoints = i.endpoints.map { UsbEndpointModel(it.address, it.transferType, it.maxPacketSize) },
            )
        }
}
