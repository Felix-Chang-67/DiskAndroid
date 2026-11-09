package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.Hex

/** Sense key（SPC-4 表 49）。 */
object SenseKey {
    const val NO_SENSE = 0x0
    const val RECOVERED_ERROR = 0x1
    const val NOT_READY = 0x2
    const val MEDIUM_ERROR = 0x3
    const val HARDWARE_ERROR = 0x4
    const val ILLEGAL_REQUEST = 0x5
    const val UNIT_ATTENTION = 0x6
    const val DATA_PROTECT = 0x7
    const val BLANK_CHECK = 0x8
    const val VENDOR_SPECIFIC = 0x9
    const val COPY_ABORTED = 0xA
    const val ABORTED_COMMAND = 0xB
    const val VOLUME_OVERFLOW = 0xD
    const val MISCOMPARE = 0xE

    fun name(key: Int): String = when (key) {
        NO_SENSE -> "NO SENSE"
        RECOVERED_ERROR -> "RECOVERED ERROR"
        NOT_READY -> "NOT READY"
        MEDIUM_ERROR -> "MEDIUM ERROR"
        HARDWARE_ERROR -> "HARDWARE ERROR"
        ILLEGAL_REQUEST -> "ILLEGAL REQUEST"
        UNIT_ATTENTION -> "UNIT ATTENTION"
        DATA_PROTECT -> "DATA PROTECT"
        BLANK_CHECK -> "BLANK CHECK"
        VENDOR_SPECIFIC -> "VENDOR SPECIFIC"
        COPY_ABORTED -> "COPY ABORTED"
        ABORTED_COMMAND -> "ABORTED COMMAND"
        VOLUME_OVERFLOW -> "VOLUME OVERFLOW"
        MISCOMPARE -> "MISCOMPARE"
        else -> "RESERVED"
    }
}

/**
 * 解析后的 sense 数据（SPC-4 4.5）。支持固定格式（70h/71h）与描述符格式（72h/73h）。
 */
class SenseData(
    val responseCode: Int,
    val senseKey: Int,
    val asc: Int,
    val ascq: Int,
    /** INFORMATION 字段（例如出错的 LBA）；VALID=0 或没有该字段时为 null。 */
    val information: Long?,
    val filemark: Boolean,
    val endOfMedium: Boolean,
    val incorrectLength: Boolean,
    /** SENSE KEY SPECIFIC 是否有效（SKSV）。 */
    val sksv: Boolean,
    /** SENSE KEY SPECIFIC 的 3 字节原值（去掉 SKSV 位）。 */
    val senseKeySpecific: Int,
    val raw: ByteArray,
) {
    val isDeferred: Boolean get() = responseCode == 0x71 || responseCode == 0x73
    val isRecognizedFormat: Boolean get() = responseCode in 0x70..0x73

    /** NOT READY 时的进度（0.0..1.0），例如格式化/正在就绪；没有则为 null。 */
    val progress: Double?
        get() = if (sksv && (senseKey == SenseKey.NOT_READY || senseKey == SenseKey.NO_SENSE)) {
            (senseKeySpecific and 0xFFFF) / 65536.0
        } else {
            null
        }

    fun matches(key: Int, asc: Int, ascq: Int? = null): Boolean =
        senseKey == key && this.asc == asc && (ascq == null || this.ascq == ascq)

    /** 例如 `2/3A/01`。 */
    fun code(): String = "${Hex.hex(senseKey, 1)}/${Hex.byte(asc)}/${Hex.byte(ascq)}"

    /** 例如 `2/3A/01 NOT READY: MEDIUM NOT PRESENT - TRAY CLOSED（无盘，托盘已关闭）`。 */
    fun describe(): String {
        if (!isRecognizedFormat) return "无法识别的 sense 格式（响应码 0x${Hex.byte(responseCode)}）"
        val sb = StringBuilder()
        sb.append(code()).append(' ').append(SenseKey.name(senseKey)).append(": ").append(SenseCodes.ascText(asc, ascq))
        SenseCodes.hint(asc, ascq)?.let { sb.append("（").append(it).append("）") }
        if (isDeferred) sb.append(" [延迟错误]")
        information?.let { sb.append(" INFORMATION=").append(it) }
        progress?.let { sb.append(" 进度=").append((it * 100).toInt()).append('%') }
        return sb.toString()
    }

    override fun toString(): String = describe()

    companion object {
        /** @param length 实际收到的字节数。 */
        fun parse(buffer: ByteArray, length: Int = buffer.size): SenseData? {
            val n = minOf(length, buffer.size)
            if (n < 1) return null
            val raw = buffer.copyOf(n)
            fun u8(i: Int) = if (i < n) raw[i].toInt() and 0xFF else 0
            val responseCode = u8(0) and 0x7F
            return when (responseCode) {
                0x70, 0x71 -> {
                    val valid = (u8(0) and 0x80) != 0
                    val info = if (valid && n >= 7) {
                        (u8(3).toLong() shl 24) or (u8(4).toLong() shl 16) or (u8(5).toLong() shl 8) or u8(6).toLong()
                    } else {
                        null
                    }
                    val sksv = n > 15 && (u8(15) and 0x80) != 0
                    SenseData(
                        responseCode = responseCode,
                        senseKey = u8(2) and 0x0F,
                        asc = u8(12),
                        ascq = u8(13),
                        information = info,
                        filemark = (u8(2) and 0x80) != 0,
                        endOfMedium = (u8(2) and 0x40) != 0,
                        incorrectLength = (u8(2) and 0x20) != 0,
                        sksv = sksv,
                        senseKeySpecific = ((u8(15) and 0x7F) shl 16) or (u8(16) shl 8) or u8(17),
                        raw = raw,
                    )
                }

                0x72, 0x73 -> {
                    var info: Long? = null
                    var sksv = false
                    var sks = 0
                    var pos = 8
                    val end = minOf(n, 8 + u8(7))
                    while (pos + 2 <= end) {
                        val type = u8(pos)
                        val len = u8(pos + 1)
                        if (pos + 2 + len > end) break
                        when (type) {
                            0x00 -> if (len >= 0x0A && (u8(pos + 2) and 0x80) != 0) {
                                var v = 0L
                                for (i in 4 until 12) v = (v shl 8) or u8(pos + i).toLong()
                                info = v
                            }
                            0x02 -> if (len >= 6) {
                                sksv = (u8(pos + 4) and 0x80) != 0
                                sks = ((u8(pos + 4) and 0x7F) shl 16) or (u8(pos + 5) shl 8) or u8(pos + 6)
                            }
                        }
                        pos += 2 + len
                    }
                    SenseData(
                        responseCode = responseCode,
                        senseKey = u8(1) and 0x0F,
                        asc = u8(2),
                        ascq = u8(3),
                        information = info,
                        filemark = false,
                        endOfMedium = false,
                        incorrectLength = false,
                        sksv = sksv,
                        senseKeySpecific = sks,
                        raw = raw,
                    )
                }

                else -> SenseData(responseCode, -1, 0, 0, null, false, false, false, false, 0, raw)
            }
        }
    }
}

/** ASC/ASCQ 文本（T10 官方英文）与常见代码的中文解释。 */
object SenseCodes {
    fun ascText(asc: Int, ascq: Int): String =
        AscTable.entries[(asc shl 8) or ascq]
            ?: when {
                asc == 0x40 && ascq >= 0x80 -> "DIAGNOSTIC FAILURE ON COMPONENT ${Hex.byte(ascq)}"
                asc >= 0x80 || ascq >= 0x80 -> "VENDOR SPECIFIC"
                else -> "UNKNOWN ASC/ASCQ"
            }

    /** 与本 App 行为相关的代码给出中文解释；其余返回 null。 */
    fun hint(asc: Int, ascq: Int): String? = when ((asc shl 8) or ascq) {
        0x3A00 -> "无盘"
        0x3A01 -> "无盘，托盘已关闭"
        0x3A02 -> "无盘，托盘已打开"
        0x0400 -> "光驱未就绪"
        0x0401 -> "光驱正在就绪（读取盘片中）"
        0x0402 -> "光驱需要启动命令"
        0x0403 -> "需要人工干预"
        0x0407 -> "光驱正忙"
        0x0408 -> "正在进行长时间写入"
        0x2800 -> "盘片可能已更换"
        0x2900, 0x2901, 0x2902, 0x2903, 0x2904 -> "光驱上电或复位"
        0x2000 -> "光驱不支持此命令"
        0x2400 -> "命令参数无效"
        0x2100 -> "地址超出盘片范围"
        0x2C00 -> "命令顺序错误"
        0x3000, 0x3001, 0x3002 -> "无法读取此盘片（格式未知或不兼容）"
        0x1100, 0x1101, 0x1105, 0x1106 -> "读取错误（盘片划伤或污损？）"
        0x1111 -> "流式读取丢失"
        0x0200 -> "寻道失败"
        0x0900, 0x0901, 0x0902, 0x0903 -> "伺服 / 机械故障"
        0x1500, 0x1501, 0x1502 -> "定位错误"
        0x5300 -> "装载或弹出失败"
        0x5302 -> "已禁止取出盘片"
        0x5A01 -> "用户按下了弹出键"
        0x5700 -> "无法读取目录（TOC）"
        0x6400 -> "该轨道模式下不能这样读取"
        0x6F00 -> "版权保护认证失败"
        0x6F01 -> "版权保护密钥不存在"
        0x6F02 -> "版权保护密钥尚未建立"
        0x6F03 -> "未认证就读取加密扇区"
        0x6F04 -> "盘片地区码与光驱地区码不符"
        0x6F05 -> "光驱地区设置次数已用完或必须永久设置"
        0x3E01, 0x4400 -> "光驱内部故障"
        else -> null
    }
}
