package io.github.felixchang67.diskandroid.core.usb

import io.github.felixchang67.diskandroid.core.log.Hex

/*
 * 解析 UsbDeviceConnection.getRawDescriptors() 返回的原始描述符
 * （设备描述符 + 完整的配置描述符集合）。布局依据 USB 2.0 规范第 9 章与 USB 3.2 规范第 9 章。
 * 纯 Kotlin，不依赖 Android，可在 JVM 上单测。
 */

/** 设备描述符（bDescriptorType 01h，18 字节）。 */
data class UsbDeviceDescriptor(
    val bcdUsb: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val maxPacketSize0Raw: Int,
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int,
    val iManufacturer: Int,
    val iProduct: Int,
    val iSerialNumber: Int,
    val numConfigurations: Int,
) {
    /** USB 3.x 设备的 bMaxPacketSize0 是 2 的指数（9 → 512）。 */
    val maxPacketSize0: Int get() = if (bcdUsb >= 0x0300) 1 shl maxPacketSize0Raw else maxPacketSize0Raw
}

/** SuperSpeed 端点伴随描述符（30h，6 字节）。 */
data class UsbSsEndpointCompanion(val maxBurst: Int, val attributes: Int, val bytesPerInterval: Int) {
    /** 仅对 bulk 端点有意义：支持的 stream 数（UAS 会用到）。 */
    val maxStreams: Int get() = (attributes and 0x1F).let { if (it == 0) 0 else 1 shl it }
}

/** 端点描述符（05h，7 字节）。 */
data class UsbEndpointDescriptor(
    val address: Int,
    val attributes: Int,
    val maxPacketSizeRaw: Int,
    val interval: Int,
    val companion: UsbSsEndpointCompanion?,
) {
    val isIn: Boolean get() = (address and 0x80) != 0
    val transferType: Int get() = attributes and 0x03
    val isBulk: Boolean get() = transferType == 2
    val maxPacketSize: Int get() = maxPacketSizeRaw and 0x07FF
}

/** 我们不专门解析的描述符（类专用、IAD 等），原样保留。 */
class UsbExtraDescriptor(val type: Int, val bytes: ByteArray) {
    override fun toString(): String = "类型 ${Hex.byte(type)}：${Hex.encode(bytes)}"
}

/** 接口描述符（04h，9 字节）。 */
data class UsbInterfaceDescriptor(
    val number: Int,
    val alternateSetting: Int,
    val numEndpoints: Int,
    val interfaceClass: Int,
    val interfaceSubClass: Int,
    val interfaceProtocol: Int,
    val iInterface: Int,
    val endpoints: List<UsbEndpointDescriptor>,
    val extras: List<UsbExtraDescriptor>,
)

/** 配置描述符（02h，9 字节）及其下属接口。 */
data class UsbConfigDescriptor(
    val totalLength: Int,
    val numInterfaces: Int,
    val configurationValue: Int,
    val iConfiguration: Int,
    val attributes: Int,
    val maxPowerRaw: Int,
    val interfaces: List<UsbInterfaceDescriptor>,
    val extras: List<UsbExtraDescriptor>,
) {
    val selfPowered: Boolean get() = (attributes and 0x40) != 0
    val remoteWakeup: Boolean get() = (attributes and 0x20) != 0

    /** bMaxPower 的单位：SuperSpeed 下为 8 mA，其他速度为 2 mA。 */
    fun maxPowerMilliAmps(superSpeed: Boolean): Int = maxPowerRaw * if (superSpeed) 8 else 2
}

class ParsedUsbDescriptors(
    val device: UsbDeviceDescriptor?,
    val configurations: List<UsbConfigDescriptor>,
    val warnings: List<String>,
) {
    /**
     * 根据描述符推断链路速度：
     * 有 SuperSpeed 伴随描述符 / bulk 包长 1024 → SuperSpeed；bulk 包长 512 → High-Speed；≤64 → Full-Speed。
     * （USB3 设备在 USB2 口上枚举时会给出 High-Speed 的配置描述符，所以这个推断反映的是实际链路。）
     */
    fun inferSpeed(): UsbSpeed {
        val endpoints = configurations.flatMap { c -> c.interfaces.flatMap { it.endpoints } }
        if (endpoints.any { it.companion != null }) return UsbSpeed.SUPER
        val bulk = endpoints.filter { it.isBulk }.map { it.maxPacketSize }
        return when {
            bulk.any { it >= 1024 } -> UsbSpeed.SUPER
            bulk.any { it == 512 } -> UsbSpeed.HIGH
            bulk.isNotEmpty() && bulk.all { it <= 64 } -> UsbSpeed.FULL
            (device?.bcdUsb ?: 0) >= 0x0300 -> UsbSpeed.SUPER
            else -> UsbSpeed.UNKNOWN
        }
    }

    /** 人类可读的描述符树（中文），用于界面和诊断信息。 */
    fun describe(): List<String> {
        val out = mutableListOf<String>()
        val superSpeed = inferSpeed().isSuperSpeed
        device?.let { d ->
            val vendor = UsbNames.vendorName(d.vendorId)?.let { "（$it）" } ?: ""
            out += "设备描述符：USB ${bcd(d.bcdUsb)}，设备类 ${Hex.byte(d.deviceClass)}/${Hex.byte(d.deviceSubClass)}/${Hex.byte(d.deviceProtocol)}" +
                "（${UsbNames.className(d.deviceClass)}），EP0 包长 ${d.maxPacketSize0}"
            out += "  VID ${Hex.word(d.vendorId)}$vendor  PID ${Hex.word(d.productId)}  设备版本 ${bcd(d.bcdDevice)}" +
                "  字符串索引 厂商/产品/序列号 = ${d.iManufacturer}/${d.iProduct}/${d.iSerialNumber}  配置数 ${d.numConfigurations}"
        } ?: run { out += "（没有设备描述符）" }
        for (c in configurations) {
            val power = if (c.selfPowered) "自供电" else "总线供电"
            out += "配置 ${c.configurationValue}：总长 ${c.totalLength}，接口数 ${c.numInterfaces}，属性 0x${Hex.byte(c.attributes)}（$power" +
                (if (c.remoteWakeup) "，支持远程唤醒" else "") +
                "），声明最大电流 ${c.maxPowerMilliAmps(superSpeed)} mA（原始值 ${c.maxPowerRaw} × ${if (superSpeed) 8 else 2} mA）"
            c.extras.forEach { out += "  其他描述符 $it" }
            for (i in c.interfaces) {
                out += "  接口 ${i.number} 备用设置 ${i.alternateSetting}：${UsbNames.interfaceTriple(i.interfaceClass, i.interfaceSubClass, i.interfaceProtocol)}，端点数 ${i.numEndpoints}"
                for (e in i.endpoints) {
                    val dir = if (e.isIn) "IN " else "OUT"
                    var line = "    端点 0x${Hex.byte(e.address)} $dir ${UsbNames.transferTypeName(e.transferType)}，最大包长 ${e.maxPacketSize}，间隔 ${e.interval}"
                    e.companion?.let { cp ->
                        line += "｜SS 伴随：MaxBurst ${cp.maxBurst}" + if (e.isBulk) "，Streams ${cp.maxStreams}" else "，每间隔字节 ${cp.bytesPerInterval}"
                    }
                    out += line
                }
                i.extras.forEach { out += "    其他描述符 $it" }
            }
        }
        warnings.forEach { out += "⚠ $it" }
        return out
    }

    companion object {
        fun bcd(v: Int): String = "${(v shr 8) and 0xFF}.${Hex.byte(v and 0xFF)}"
    }
}

object UsbDescriptorParser {
    private const val TYPE_DEVICE = 0x01
    private const val TYPE_CONFIG = 0x02
    private const val TYPE_INTERFACE = 0x04
    private const val TYPE_ENDPOINT = 0x05
    private const val TYPE_SS_EP_COMPANION = 0x30

    private class EndpointBuilder(val address: Int, val attributes: Int, val maxPacket: Int, val interval: Int) {
        var companion: UsbSsEndpointCompanion? = null
        fun build() = UsbEndpointDescriptor(address, attributes, maxPacket, interval, companion)
    }

    private class InterfaceBuilder(
        val number: Int, val alt: Int, val numEp: Int, val cls: Int, val sub: Int, val proto: Int, val iInterface: Int,
    ) {
        val endpoints = mutableListOf<EndpointBuilder>()
        val extras = mutableListOf<UsbExtraDescriptor>()
        fun build() = UsbInterfaceDescriptor(number, alt, numEp, cls, sub, proto, iInterface, endpoints.map { it.build() }, extras.toList())
    }

    private class ConfigBuilder(
        val totalLength: Int, val numInterfaces: Int, val value: Int, val iConfig: Int, val attributes: Int, val maxPower: Int,
    ) {
        val interfaces = mutableListOf<InterfaceBuilder>()
        val extras = mutableListOf<UsbExtraDescriptor>()
        fun build() = UsbConfigDescriptor(totalLength, numInterfaces, value, iConfig, attributes, maxPower, interfaces.map { it.build() }, extras.toList())
    }

    fun parse(raw: ByteArray): ParsedUsbDescriptors {
        val warnings = mutableListOf<String>()
        var device: UsbDeviceDescriptor? = null
        val configs = mutableListOf<ConfigBuilder>()
        var config: ConfigBuilder? = null
        var iface: InterfaceBuilder? = null
        var lastEndpoint: EndpointBuilder? = null

        fun u8(at: Int) = raw[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)

        var pos = 0
        while (pos < raw.size) {
            if (pos + 2 > raw.size) {
                warnings += "偏移 $pos：剩余 ${raw.size - pos} 字节不足一个描述符头"
                break
            }
            val len = u8(pos)
            val type = u8(pos + 1)
            if (len < 2) {
                warnings += "偏移 $pos：bLength=$len 无效，停止解析"
                break
            }
            if (pos + len > raw.size) {
                warnings += "偏移 $pos：描述符被截断（bLength=$len，剩余 ${raw.size - pos} 字节）"
                break
            }
            when (type) {
                TYPE_DEVICE -> if (len >= 18) {
                    if (device != null) warnings += "偏移 $pos：出现第二个设备描述符"
                    device = UsbDeviceDescriptor(
                        bcdUsb = u16(pos + 2),
                        deviceClass = u8(pos + 4),
                        deviceSubClass = u8(pos + 5),
                        deviceProtocol = u8(pos + 6),
                        maxPacketSize0Raw = u8(pos + 7),
                        vendorId = u16(pos + 8),
                        productId = u16(pos + 10),
                        bcdDevice = u16(pos + 12),
                        iManufacturer = u8(pos + 14),
                        iProduct = u8(pos + 15),
                        iSerialNumber = u8(pos + 16),
                        numConfigurations = u8(pos + 17),
                    )
                } else {
                    warnings += "偏移 $pos：设备描述符长度 $len < 18"
                }

                TYPE_CONFIG -> if (len >= 9) {
                    config = ConfigBuilder(u16(pos + 2), u8(pos + 4), u8(pos + 5), u8(pos + 6), u8(pos + 7), u8(pos + 8))
                        .also { configs += it }
                    iface = null
                    lastEndpoint = null
                } else {
                    warnings += "偏移 $pos：配置描述符长度 $len < 9"
                }

                TYPE_INTERFACE -> if (len >= 9) {
                    val built = InterfaceBuilder(u8(pos + 2), u8(pos + 3), u8(pos + 4), u8(pos + 5), u8(pos + 6), u8(pos + 7), u8(pos + 8))
                    val owner = config
                    if (owner == null) warnings += "偏移 $pos：接口描述符不在任何配置之下" else owner.interfaces += built
                    iface = built
                    lastEndpoint = null
                } else {
                    warnings += "偏移 $pos：接口描述符长度 $len < 9"
                }

                TYPE_ENDPOINT -> if (len >= 7) {
                    val ep = EndpointBuilder(u8(pos + 2), u8(pos + 3), u16(pos + 4), u8(pos + 6))
                    val owner = iface
                    if (owner == null) warnings += "偏移 $pos：端点描述符不在任何接口之下" else owner.endpoints += ep
                    lastEndpoint = ep
                } else {
                    warnings += "偏移 $pos：端点描述符长度 $len < 7"
                }

                TYPE_SS_EP_COMPANION -> if (len >= 6) {
                    val ep = lastEndpoint
                    if (ep == null) {
                        warnings += "偏移 $pos：SuperSpeed 伴随描述符前面没有端点"
                    } else {
                        ep.companion = UsbSsEndpointCompanion(u8(pos + 2), u8(pos + 3), u16(pos + 4))
                    }
                } else {
                    warnings += "偏移 $pos：SuperSpeed 伴随描述符长度 $len < 6"
                }

                else -> {
                    val extra = UsbExtraDescriptor(type, raw.copyOfRange(pos, pos + len))
                    val currentIface = iface
                    val currentConfig = config
                    when {
                        currentIface != null -> currentIface.extras += extra
                        currentConfig != null -> currentConfig.extras += extra
                        else -> warnings += "偏移 $pos：游离的描述符 类型 ${Hex.byte(type)}"
                    }
                }
            }
            pos += len
        }

        val built = configs.map { it.build() }
        for (c in built) {
            val ids = c.interfaces.map { it.number }.distinct().size
            if (ids != c.numInterfaces) warnings += "配置 ${c.configurationValue}：声明 ${c.numInterfaces} 个接口，实际解析到 $ids 个"
            for (i in c.interfaces) {
                if (i.endpoints.size != i.numEndpoints) {
                    warnings += "接口 ${i.number}/${i.alternateSetting}：声明 ${i.numEndpoints} 个端点，实际 ${i.endpoints.size} 个"
                }
            }
        }
        return ParsedUsbDescriptors(device, built, warnings)
    }
}
