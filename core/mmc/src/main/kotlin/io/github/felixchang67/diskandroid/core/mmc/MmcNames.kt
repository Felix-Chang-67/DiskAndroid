package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex

/** Profile（盘片类型）、Feature 等代码的可读名称。依据 MMC-6，并与 libburn 的表核对。 */
object MmcNames {
    enum class DiscFamily { NONE, CD, DVD, BD, HD_DVD, OTHER }

    fun profileName(profile: Int): String = when (profile) {
        0x0000 -> "无盘片 / 未知"
        0x0001 -> "Non-removable disk"
        0x0002 -> "Removable disk"
        0x0003 -> "MO Erasable"
        0x0004 -> "Optical Write Once"
        0x0005 -> "AS-MO"
        0x0008 -> "CD-ROM"
        0x0009 -> "CD-R"
        0x000A -> "CD-RW"
        0x0010 -> "DVD-ROM"
        0x0011 -> "DVD-R（顺序记录）"
        0x0012 -> "DVD-RAM"
        0x0013 -> "DVD-RW（限制覆写）"
        0x0014 -> "DVD-RW（顺序记录）"
        0x0015 -> "DVD-R DL（顺序记录）"
        0x0016 -> "DVD-R DL（跳层记录）"
        0x0017 -> "DVD-RW DL"
        0x0018 -> "DVD-Download"
        0x001A -> "DVD+RW"
        0x001B -> "DVD+R"
        0x0020 -> "DDCD-ROM"
        0x0021 -> "DDCD-R"
        0x0022 -> "DDCD-RW"
        0x002A -> "DVD+RW DL"
        0x002B -> "DVD+R DL"
        0x0040 -> "BD-ROM"
        0x0041 -> "BD-R（SRM 顺序记录）"
        0x0042 -> "BD-R（RRM 随机记录）"
        0x0043 -> "BD-RE"
        0x0050 -> "HD DVD-ROM"
        0x0051 -> "HD DVD-R"
        0x0052 -> "HD DVD-RAM"
        0x0053 -> "HD DVD-RW"
        0x0058 -> "HD DVD-R DL"
        0x005A -> "HD DVD-RW DL"
        0xFFFF -> "非标准 profile"
        else -> "未知 profile ${Hex.word(profile)}h"
    }

    fun family(profile: Int): DiscFamily = when (profile) {
        0x0000 -> DiscFamily.NONE
        in 0x0008..0x000A -> DiscFamily.CD
        in 0x0010..0x002B -> DiscFamily.DVD
        in 0x0040..0x004F -> DiscFamily.BD
        in 0x0050..0x005F -> DiscFamily.HD_DVD
        else -> DiscFamily.OTHER
    }

    fun featureName(code: Int): String = when (code) {
        0x0000 -> "Profile List"
        0x0001 -> "Core"
        0x0002 -> "Morphing"
        0x0003 -> "Removable Medium"
        0x0004 -> "Write Protect"
        0x0010 -> "Random Readable"
        0x001D -> "Multi-Read"
        0x001E -> "CD Read"
        0x001F -> "DVD Read"
        0x0020 -> "Random Writable"
        0x0021 -> "Incremental Streaming Writable"
        0x0022 -> "Sector Erasable"
        0x0023 -> "Formattable"
        0x0024 -> "Hardware Defect Management"
        0x0025 -> "Write Once"
        0x0026 -> "Restricted Overwrite"
        0x0027 -> "CD-RW CAV Write"
        0x0028 -> "MRW"
        0x0029 -> "Enhanced Defect Reporting"
        0x002A -> "DVD+RW"
        0x002B -> "DVD+R"
        0x002C -> "Rigid Restricted Overwrite"
        0x002D -> "CD Track at Once"
        0x002E -> "CD Mastering"
        0x002F -> "DVD-R/-RW Write"
        0x0030, 0x0031, 0x0032 -> "DDCD（旧）"
        0x0033 -> "Layer Jump Recording"
        0x0034 -> "LJ Rigid Restricted Overwrite"
        0x0035 -> "Stop Long Operation"
        0x0037 -> "CD-RW Media Write Support"
        0x0038 -> "BD-R POW"
        0x003A -> "DVD+RW Dual Layer"
        0x003B -> "DVD+R Dual Layer"
        0x0040 -> "BD Read"
        0x0041 -> "BD Write"
        0x0042 -> "TSR"
        0x0050 -> "HD DVD Read"
        0x0051 -> "HD DVD Write"
        0x0052 -> "HD DVD-RW Fragment Recording"
        0x0080 -> "Hybrid Disc"
        0x0100 -> "Power Management"
        0x0101 -> "S.M.A.R.T."
        0x0102 -> "Embedded Changer"
        0x0103 -> "CD Audio External Play（旧）"
        0x0104 -> "Microcode Upgrade"
        0x0105 -> "Time-Out"
        0x0106 -> "DVD CSS"
        0x0107 -> "Real Time Streaming"
        0x0108 -> "Drive Serial Number"
        0x0109 -> "Media Serial Number"
        0x010A -> "Disc Control Blocks"
        0x010B -> "DVD CPRM"
        0x010C -> "Firmware Information"
        0x010D -> "AACS"
        0x010E -> "DVD CSS Managed Recording"
        0x0110 -> "VCPS"
        0x0113 -> "SecurDisc"
        0x0142 -> "OSSC"
        in 0xFF00..0xFFFF -> "厂商自定义"
        else -> "保留"
    }

    /** Core 特性里的物理接口标准。 */
    fun physicalInterface(code: Long): String = when (code) {
        0L -> "未指定"
        1L -> "SCSI 系列"
        2L -> "ATAPI"
        3L -> "IEEE 1394-1995"
        4L -> "IEEE 1394A"
        5L -> "Fibre Channel"
        6L -> "IEEE 1394B"
        7L -> "Serial ATAPI"
        8L -> "USB"
        0xFFFFL -> "厂商自定义"
        else -> "未知（$code）"
    }

    /** 装载机构类型（Removable Medium 特性 / 2Ah 页）。 */
    fun loadingMechanism(type: Int): String = when (type) {
        0 -> "吸入式 / 盒式"
        1 -> "托盘式"
        2 -> "弹出式（pop-up）"
        4 -> "内置换碟器（单独更换）"
        5 -> "内置换碟器（弹匣）"
        else -> "保留（$type）"
    }

    /** GESN 媒体事件代码。 */
    fun mediaEvent(code: Int): String = when (code) {
        0 -> "无变化"
        1 -> "弹出请求（按了弹出键）"
        2 -> "新盘片"
        3 -> "盘片取出"
        4 -> "盘片更换"
        5 -> "后台格式化完成"
        6 -> "后台格式化重新开始"
        else -> "保留（$code）"
    }

    /** GESN 通知类位图 → 文本。 */
    fun eventClasses(mask: Int): String {
        val names = listOf(1 to "操作变化", 2 to "电源管理", 3 to "外部请求", 4 to "媒体", 5 to "多主机", 6 to "设备忙")
        val set = names.filter { (bit, _) -> (mask shr bit) and 1 == 1 }.map { it.second }
        return if (set.isEmpty()) "无" else set.joinToString("、")
    }
}
