package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex

/** 返回 null 表示放行，否则返回拒绝原因。 */
fun interface CommandPolicy {
    fun check(cmd: ScsiCommand): String?
}

/**
 * 阶段 1–2 的只读白名单。只放行登记过的读类命令，其余一律在发出前拒绝。
 * 写盘类命令（WRITE、BLANK、CLOSE TRACK/SESSION、FORMAT UNIT、SEND CUE SHEET、MODE SELECT、
 * WRITE BUFFER、SEND KEY 等）根本不在名单里；任何 Data-Out 命令也被拒绝。
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
        0xA4 to "REPORT KEY（仅 RPC 状态）",
        0xA8 to "READ(12)",
        0xAC to "GET PERFORMANCE（仅读取速度）",
        0xAD to "READ DISC STRUCTURE（不含密钥类格式）",
        0xBB to "SET CD SPEED",
        0xBD to "MECHANISM STATUS",
        0xBE to "READ CD",
    )

    override fun check(cmd: ScsiCommand): String? {
        val op = cmd.opcode
        val name = allowed[op] ?: return "操作码 0x${Hex.byte(op)} 不在只读白名单内"
        if (cmd.direction == DataDirection.OUT) return "$name 不允许 Data-Out"
        val cdb = cmd.cdb
        return when (op) {
            // START STOP UNIT：POWER CONDITION（字节 4 高 4 位）必须为 0
            0x1B -> if (cdb.size >= 5 && (cdb[4].toInt() and 0xF0) != 0) "START STOP UNIT 不允许修改电源状态" else null
            // REPORT KEY：只允许 KEY CLASS 00h + KEY FORMAT 08h（读取 RPC 地区状态）
            0xA4 -> {
                val keyClass = if (cdb.size > 7) cdb[7].toInt() and 0xFF else -1
                val keyFormat = if (cdb.size > 10) cdb[10].toInt() and 0x3F else -1
                if (keyClass == 0x00 && keyFormat == 0x08) null else "REPORT KEY 只允许读取 RPC 状态（KEY FORMAT 08h）"
            }
            // READ DISC STRUCTURE：不允许密钥 / 认证相关的格式（DVD 盘钥 02h、CPRM 06h/07h、AACS 80h–8Fh），AGID 必须为 0
            0xAD -> {
                val format = if (cdb.size > 7) cdb[7].toInt() and 0xFF else -1
                val agid = if (cdb.size > 10) (cdb[10].toInt() shr 6) and 0x03 else 0
                when {
                    format == 0x02 || format == 0x06 || format == 0x07 || format in 0x80..0x8F ->
                        "READ DISC STRUCTURE 格式 ${Hex.byte(format)}h 与密钥/认证有关，阶段 1 不允许"
                    agid != 0 -> "READ DISC STRUCTURE 不允许指定 AGID"
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
