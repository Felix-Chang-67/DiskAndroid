package io.github.felixchang67.diskandroid.video

import io.github.felixchang67.diskandroid.core.log.Hex

/** 解密组件发来的认证命令 → 日志里看得懂的名字（格式值见 core:scsi 的 ReadOnlyPolicy 注释）。 */
object AuthCommands {
    private val cssReport = mapOf(
        0x00 to "申请 AGID", 0x01 to "取光驱 Challenge", 0x02 to "取 KEY1", 0x04 to "取标题密钥", 0x05 to "取认证状态（ASF）",
        0x08 to "读 RPC 状态", 0x3F to "作废 AGID",
    )
    private val cssSend = mapOf(0x01 to "发主机 Challenge", 0x03 to "发 KEY2", 0x06 to "设置地区码（禁止）", 0x3F to "作废 AGID")
    private val aacsReport = mapOf(
        0x00 to "申请 AGID", 0x01 to "取光驱证书 + Challenge", 0x02 to "取光驱密钥", 0x38 to "读光驱证书", 0x3F to "作废 AGID",
    )
    private val aacsSend = mapOf(0x01 to "发主机证书 + Challenge", 0x02 to "发主机密钥", 0x3F to "作废 AGID")
    private val structures = mapOf(
        0x01 to "版权信息", 0x02 to "盘钥", 0x80 to "AACS 卷标识", 0x81 to "AACS 序列号", 0x82 to "AACS 媒体标识",
        0x83 to "AACS MKB", 0x84 to "AACS 数据密钥",
    )

    fun describe(cdb: ByteArray): String {
        val op = cdb[0].toInt() and 0xFF
        val keyClass = if (cdb.size > 7) cdb[7].toInt() and 0xFF else 0
        val fmt = if (cdb.size > 10) cdb[10].toInt() and 0x3F else 0
        val agid = if (cdb.size > 10) (cdb[10].toInt() shr 6) and 3 else 0
        val system = when (keyClass) {
            0x00 -> "CSS"
            0x02 -> "AACS"
            else -> "KEY CLASS ${Hex.byte(keyClass)}h"
        }
        return when (op) {
            0xA4 -> "REPORT KEY（$system：${(if (keyClass == 0x02) aacsReport else cssReport)[fmt] ?: "格式 ${Hex.byte(fmt)}h"}，AGID $agid）"
            0xA3 -> "SEND KEY（$system：${(if (keyClass == 0x02) aacsSend else cssSend)[fmt] ?: "格式 ${Hex.byte(fmt)}h"}，AGID $agid）"
            0xAD -> {
                val f = if (cdb.size > 7) cdb[7].toInt() and 0xFF else 0
                "READ DISC STRUCTURE（${structures[f] ?: "格式 ${Hex.byte(f)}h"}）"
            }
            0x46 -> "GET CONFIGURATION"
            else -> "操作码 ${Hex.byte(op)}h"
        }
    }
}
