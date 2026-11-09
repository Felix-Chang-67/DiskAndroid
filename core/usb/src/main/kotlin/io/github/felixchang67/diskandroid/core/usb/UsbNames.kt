package io.github.felixchang67.diskandroid.core.usb

import io.github.felixchang67.diskandroid.core.log.Hex

/** USB 类别 / 厂商代码的可读名称（只收录和本项目相关的）。 */
object UsbNames {
    fun className(cls: Int): String = when (cls) {
        0x00 -> "由接口定义"
        0x01 -> "Audio"
        0x02 -> "CDC"
        0x03 -> "HID"
        0x07 -> "Printer"
        0x08 -> "Mass Storage"
        0x09 -> "Hub"
        0x0A -> "CDC-Data"
        0x0E -> "Video"
        0xDC -> "Diagnostic"
        0xE0 -> "Wireless"
        0xEF -> "Miscellaneous"
        0xFE -> "Application Specific"
        0xFF -> "Vendor Specific"
        else -> "未知"
    }

    /** Mass Storage 子类（USB MSC Overview 1.4）。 */
    fun massStorageSubclassName(sub: Int): String = when (sub) {
        0x00 -> "SCSI 命令集未报告"
        0x01 -> "RBC"
        0x02 -> "MMC-5 (ATAPI) / SFF-8020i"
        0x03 -> "QIC-157（已废弃）"
        0x04 -> "UFI（软驱）"
        0x05 -> "SFF-8070i（已废弃）"
        0x06 -> "SCSI 透明命令集"
        0x07 -> "LSD FS"
        0x08 -> "IEEE 1667"
        0xFF -> "厂商自定义"
        else -> "未知"
    }

    /** Mass Storage 传输协议。 */
    fun massStorageProtocolName(proto: Int): String = when (proto) {
        0x00 -> "CBI（带完成中断）"
        0x01 -> "CBI（无完成中断）"
        0x50 -> "Bulk-Only (BOT)"
        0x62 -> "UAS"
        0xFF -> "厂商自定义"
        else -> "未知"
    }

    fun interfaceTriple(cls: Int, sub: Int, proto: Int): String =
        if (cls == 0x08) {
            "类 ${Hex.byte(cls)} ${className(cls)} / 子类 ${Hex.byte(sub)} ${massStorageSubclassName(sub)} / 协议 ${Hex.byte(proto)} ${massStorageProtocolName(proto)}"
        } else {
            "类 ${Hex.byte(cls)} ${className(cls)} / 子类 ${Hex.byte(sub)} / 协议 ${Hex.byte(proto)}"
        }

    fun transferTypeName(type: Int): String = when (type and 0x03) {
        0 -> "Control"
        1 -> "Isochronous"
        2 -> "Bulk"
        else -> "Interrupt"
    }

    /** 常见厂商（光驱、USB-SATA 桥接芯片、扩展坞）。仅用于显示，不参与任何判断。 */
    fun vendorName(vid: Int): String? = when (vid) {
        0x0411 -> "BUFFALO INC."
        0x04DA -> "Panasonic"
        0x0930 -> "Toshiba"
        0x093B -> "Plextor"
        0x0E8D -> "MediaTek"
        0x13FD -> "Initio"
        0x152D -> "JMicron"
        0x174C -> "ASMedia"
        0x1C6B -> "Philips & Lite-ON Digital Solutions"
        0x05E3 -> "Genesys Logic"
        0x0BDA -> "Realtek"
        0x2109 -> "VIA Labs"
        0x0BC2 -> "Seagate"
        0x2717 -> "Xiaomi"
        else -> null
    }
}
