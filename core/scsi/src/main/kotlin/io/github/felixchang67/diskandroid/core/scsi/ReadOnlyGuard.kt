package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex

/** 返回 null 表示放行，否则返回拒绝原因。 */
fun interface CommandPolicy {
    fun check(cmd: ScsiCommand): String?
}

/**
 * 阶段 1–3 的只读白名单。只放行登记过的命令，其余一律在发出前拒绝。
 * 写盘类命令（WRITE、BLANK、CLOSE TRACK/SESSION、FORMAT UNIT、SEND CUE SHEET、MODE SELECT、WRITE BUFFER 等）根本不在名单里。
 *
 * 阶段 3：解密组件（libdvdcss / libaacs）要和光驱做认证，放行认证用到的格式（只交换密钥，不改盘片、不改光驱的持久设置）。
 * 格式值对照：T10 MMC 的 REPORT KEY / SEND KEY 通用 CDB（字节 7 = KEY CLASS，字节 10 = AGID << 6 | KEY FORMAT；
 * KEY CLASS 00h = DVD CSS/CPPM/CPRM），libdvdcss 1.6.0 的 ioctl.h、Linux cdrom.c、Windows ntddcdvd.h 的 DVD_KEY_TYPE
 * （DvdSetRpcKey = 6）、libaacs 0.12.0 的 mmc.c 和 ReactOS cdrom 驱动的 aacs.c（AACS KEY CLASS 02h）。
 *
 * - REPORT KEY（A4h，读）：
 *   - KEY CLASS 00h：00h AGID、01h 光驱 Challenge、02h KEY1、04h 标题密钥、05h ASF、08h RPC 状态、3Fh 作废 AGID
 *   - KEY CLASS 02h（AACS）：00h AGID、01h 光驱证书 + Challenge、02h 光驱密钥、38h 光驱证书、3Fh 作废 AGID
 * - SEND KEY（A3h，Data-Out：把认证数据发给光驱）：
 *   - KEY CLASS 00h：01h 主机 Challenge、03h KEY2、3Fh 作废 AGID。**06h（设置地区码 RPC）永久禁止**
 *   - KEY CLASS 02h：01h 主机证书 + Challenge、02h 主机密钥、3Fh 作废 AGID
 * - READ DISC STRUCTURE（ADh，读）：DVD 02h 盘钥（带 AGID）；BD（媒体类型 1）80h 卷标识、81h 序列号、82h 媒体标识、83h MKB、
 *   84h 数据密钥（带 AGID）。CPRM（06h / 07h）仍然不放行。
 * - 除 SEND KEY 的上述格式外，任何 Data-Out 一律拒绝。
 */
object ReadOnlyPolicy : CommandPolicy {
    /** 操作码 → 名称。全部是只读命令（不改变盘片内容，也不改变光驱的持久设置）。 */
    val allowed: Map<Int, String> = linkedMapOf(
        0x00 to "TEST UNIT READY",
        0x03 to "REQUEST SENSE",
        0x12 to "INQUIRY",
        0x1B to "START STOP UNIT（仅启动/停止/弹出/装载）",
        0x1E to "PREVENT ALLOW MEDIUM REMOVAL",
        0x25 to "READ CAPACITY",
        0x28 to "READ(10)",
        0x42 to "READ SUB-CHANNEL",
        0x43 to "READ TOC/PMA/ATIP",
        0x46 to "GET CONFIGURATION",
        0x4A to "GET EVENT STATUS NOTIFICATION",
        0x51 to "READ DISC INFORMATION",
        0x52 to "READ TRACK INFORMATION",
        0x5A to "MODE SENSE(10)",
        0xA3 to "SEND KEY（仅认证，不含设置地区码）",
        0xA4 to "REPORT KEY（RPC 状态、CSS / AACS 认证）",
        0xA8 to "READ(12)",
        0xAC to "GET PERFORMANCE（仅读取速度）",
        0xAD to "READ DISC STRUCTURE（含 CSS 盘钥、AACS 结构）",
        0xBB to "SET CD SPEED",
        0xBD to "MECHANISM STATUS",
        0xBE to "READ CD",
    )

    /** REPORT KEY 允许的 KEY FORMAT（按 KEY CLASS）。 */
    private val reportKeyFormats = mapOf(
        0x00 to setOf(0x00, 0x01, 0x02, 0x04, 0x05, 0x08, 0x3F),
        0x02 to setOf(0x00, 0x01, 0x02, 0x38, 0x3F),
    )

    /** SEND KEY 允许的 KEY FORMAT（按 KEY CLASS）。CSS 的 06h（设置地区码）不在这里，永远发不出去。 */
    private val sendKeyFormats = mapOf(
        0x00 to setOf(0x01, 0x03, 0x3F),
        0x02 to setOf(0x01, 0x02, 0x3F),
    )

    /** SEND KEY KEY CLASS 00h 的 KEY FORMAT 06h：RPC 结构（设置光驱地区码，次数有限、不可逆）。 */
    const val SEND_KEY_RPC = 0x06

    override fun check(cmd: ScsiCommand): String? {
        val op = cmd.opcode
        val name = allowed[op] ?: return "操作码 0x${Hex.byte(op)} 不在只读白名单内"
        if (cmd.direction == DataDirection.OUT && op != 0xA3) return "$name 不允许 Data-Out"
        val cdb = cmd.cdb
        return when (op) {
            // START STOP UNIT：POWER CONDITION（字节 4 高 4 位）必须为 0
            0x1B -> if (cdb.size >= 5 && (cdb[4].toInt() and 0xF0) != 0) "START STOP UNIT 不允许修改电源状态" else null
            0xA4 -> {
                val keyClass = if (cdb.size > 7) cdb[7].toInt() and 0xFF else -1
                val keyFormat = if (cdb.size > 10) cdb[10].toInt() and 0x3F else -1
                when {
                    cmd.direction == DataDirection.OUT -> "REPORT KEY 不能有 Data-Out"
                    reportKeyFormats[keyClass]?.contains(keyFormat) == true -> null
                    else -> "REPORT KEY KEY CLASS ${Hex.byte(keyClass)}h / KEY FORMAT ${Hex.byte(keyFormat)}h 不在允许范围"
                }
            }
            0xA3 -> {
                val keyClass = if (cdb.size > 7) cdb[7].toInt() and 0xFF else -1
                val keyFormat = if (cdb.size > 10) cdb[10].toInt() and 0x3F else -1
                when {
                    keyClass == 0x00 && keyFormat == SEND_KEY_RPC -> "SEND KEY 06h 会修改光驱的地区码：永久禁止"
                    sendKeyFormats[keyClass]?.contains(keyFormat) != true ->
                        "SEND KEY KEY CLASS ${Hex.byte(keyClass)}h / KEY FORMAT ${Hex.byte(keyFormat)}h 不在允许范围"
                    cmd.direction == DataDirection.IN -> "SEND KEY 不能有 Data-In"
                    else -> null
                }
            }
            0xAD -> {
                val mediaType = if (cdb.size > 1) cdb[1].toInt() and 0x0F else -1
                val format = if (cdb.size > 7) cdb[7].toInt() and 0xFF else -1
                val agid = if (cdb.size > 10) (cdb[10].toInt() shr 6) and 0x03 else 0
                when {
                    format == 0x06 || format == 0x07 -> "READ DISC STRUCTURE 格式 ${Hex.byte(format)}h（CPRM）不放行"
                    mediaType == 0x00 && format == 0x02 -> null // DVD 盘钥（CSS 认证后读取）
                    mediaType == 0x01 && format in 0x80..0x84 -> null // BD 的 AACS 结构
                    format in 0x80..0x8F -> "READ DISC STRUCTURE 格式 ${Hex.byte(format)}h 只允许 BD（媒体类型 1）的 80h–84h"
                    agid != 0 -> "READ DISC STRUCTURE 格式 ${Hex.byte(format)}h 不需要 AGID"
                    else -> null
                }
            }
            // GET PERFORMANCE：只允许 Type 00h（读取性能）
            0xAC -> {
                val type = if (cdb.size > 10) cdb[10].toInt() and 0xFF else -1
                if (type == 0x00 && (cdb[1].toInt() and 0x04) == 0) null else "GET PERFORMANCE 只允许读取性能（Type 00h，Write=0）"
            }
            else -> null
        }
    }
}

/** 在命令发出前按 [policy] 检查；被拒绝的命令不会到达光驱。 */
class GuardedScsiTransport(
    private val inner: ScsiTransport,
    private val policy: CommandPolicy,
    private val log: DiskLog,
) : ScsiTransport {
    override val description: String get() = inner.description
    override val resetCount: Long get() = inner.resetCount

    override fun execute(cmd: ScsiCommand): ScsiResult {
        val reason = policy.check(cmd)
        if (reason != null) {
            log.e("GUARD", "拒绝发送命令：$reason", "命令" to cmd.displayName, "CDB" to Hex.encode(cmd.cdb))
            return ScsiResult.Refused(reason)
        }
        return inner.execute(cmd)
    }

    override fun close() = inner.close()
}
