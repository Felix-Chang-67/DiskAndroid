package io.github.felixchang67.diskandroid.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.github.felixchang67.diskandroid.core.log.Hex

/** 某一时刻 USB 设备的不可变快照（不需要权限即可获得的信息 + 权限状态）。 */
data class UsbDeviceSnapshot(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    /** Android 10+ 需要 USB 权限才能读取序列号。 */
    val serial: String?,
    val version: String?,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val configurationCount: Int,
    val interfaces: List<UsbInterfaceModel>,
    val hasPermission: Boolean,
) {
    val vidPid: String get() = "${Hex.word(vendorId)}:${Hex.word(productId)}"

    val displayName: String
        get() = listOfNotNull(manufacturer?.trim(), product?.trim()).filter { it.isNotEmpty() }
            .joinToString(" ").ifEmpty { "USB 设备 $vidPid" }

    val massStorage: MassStorageSelection by lazy { MassStorageInterfaceSelector.select(interfaces) }

    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        val vendor = UsbNames.vendorName(vendorId)?.let { "（$it）" } ?: ""
        out += "设备：$displayName"
        out += "  路径 $deviceName  ID $deviceId  VID:PID $vidPid$vendor  版本 ${version ?: "?"}  序列号 ${serial ?: "（需权限/无）"}"
        out += "  设备类 ${Hex.byte(deviceClass)}/${Hex.byte(deviceSubclass)}/${Hex.byte(deviceProtocol)}  配置数 $configurationCount  USB 权限 ${if (hasPermission) "已授权" else "未授权"}"
        for (i in interfaces) {
            out += "  接口 ${i.id}/${i.alternateSetting}：${UsbNames.interfaceTriple(i.interfaceClass, i.interfaceSubclass, i.interfaceProtocol)}"
            for (e in i.endpoints) {
                out += "    端点 0x${Hex.byte(e.address)} ${if (e.isIn) "IN " else "OUT"} ${UsbNames.transferTypeName(e.type)} 包长 ${e.maxPacketSize}"
            }
        }
        out += when (val sel = massStorage) {
            is MassStorageSelection.Selected -> "  → 可用的大容量存储接口：${sel.summary()}"
            is MassStorageSelection.Unsupported -> "  → 大容量存储但不支持：${sel.reason}"
            MassStorageSelection.NotMassStorage -> "  → 不是大容量存储设备"
        }
        (massStorage as? MassStorageSelection.Selected)?.notes?.forEach { out += "    · $it" }
        (massStorage as? MassStorageSelection.Unsupported)?.notes?.forEach { out += "    · $it" }
        return out
    }

    companion object {
        fun from(device: UsbDevice, usbManager: UsbManager): UsbDeviceSnapshot {
            val permission = runCatching { usbManager.hasPermission(device) }.getOrDefault(false)
            return UsbDeviceSnapshot(
                deviceName = device.deviceName,
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = runCatching { device.manufacturerName }.getOrNull(),
                product = runCatching { device.productName }.getOrNull(),
                serial =if (permission) runCatching { device.serialNumber }.getOrNull() else null,
                version = runCatching { device.version }.getOrNull(),
                deviceClass = device.deviceClass,
                deviceSubclass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
                configurationCount = device.configurationCount,
                interfaces = interfaceModels(device),
                hasPermission = permission,
            )
        }

        /** 当前（第一个）配置下的所有接口（包括备用设置）。 */
        fun androidInterfaces(device: UsbDevice): List<UsbInterface> =
            if (device.configurationCount > 0) {
                val config = device.getConfiguration(0)
                (0 until config.interfaceCount).map { config.getInterface(it) }
            } else {
                (0 until device.interfaceCount).map { device.getInterface(it) }
            }

        fun interfaceModels(device: UsbDevice): List<UsbInterfaceModel> = androidInterfaces(device).map { intf ->
            UsbInterfaceModel(
                id = intf.id,
                alternateSetting = intf.alternateSetting,
                interfaceClass = intf.interfaceClass,
                interfaceSubclass = intf.interfaceSubclass,
                interfaceProtocol = intf.interfaceProtocol,
                endpoints = (0 until intf.endpointCount).map { k ->
                    val ep = intf.getEndpoint(k)
                    UsbEndpointModel(ep.address, ep.type, ep.maxPacketSize)
                },
            )
        }

        fun findInterface(device: UsbDevice, id: Int, alternateSetting: Int): UsbInterface? =
            androidInterfaces(device).firstOrNull { it.id == id && it.alternateSetting == alternateSetting }
    }
}
