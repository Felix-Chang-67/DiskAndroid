package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.Hex
import java.io.Closeable

enum class DataDirection { NONE, IN, OUT }

/**
 * 一条 SCSI 命令。
 *
 * @property buffer IN：接收数据的缓冲区；OUT：要发送的数据。NONE 时为 null。
 * @property name 命令名（只用于日志）。
 * @property quiet 轮询类命令：结果和上一次相同时不重复写日志。
 * @property sensitive 载荷涉及密钥（阶段 3 的认证命令）：日志里不输出数据内容。
 * @property streaming 高频数据读取（播放、抓轨）：成功时只记一行精简日志（CDB、长度、结果、耗时，不含数据和 CBW/CSW），
 *   失败时照常完整记录（sense 原始 hex + 解码）。
 */
class ScsiCommand(
    val cdb: ByteArray,
    val direction: DataDirection = DataDirection.NONE,
    val buffer: ByteArray? = null,
    val offset: Int = 0,
    val length: Int = 0,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val name: String = "",
    val quiet: Boolean = false,
    val sensitive: Boolean = false,
    val streaming: Boolean = false,
) {
    init {
        require(cdb.size in 6..16) { "CDB 长度必须在 6..16 之间（实际 ${cdb.size}）" }
        if (direction == DataDirection.NONE) {
            require(length == 0) { "无数据命令的长度必须为 0" }
        } else {
            val buf = requireNotNull(buffer) { "有数据阶段的命令必须提供缓冲区" }
            require(offset >= 0 && length >= 0 && offset + length <= buf.size) { "缓冲区范围越界" }
        }
        require(timeoutMs > 0) { "超时必须为正数" }
    }

    val opcode: Int get() = cdb[0].toInt() and 0xFF

    val displayName: String get() = name.ifEmpty { "操作码 0x${Hex.byte(opcode)}" }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000

        fun noData(cdb: ByteArray, name: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS, quiet: Boolean = false) =
            ScsiCommand(cdb, DataDirection.NONE, null, 0, 0, timeoutMs, name, quiet)

        fun dataIn(cdb: ByteArray, length: Int, name: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS, quiet: Boolean = false) =
            ScsiCommand(cdb, DataDirection.IN, ByteArray(length), 0, length, timeoutMs, name, quiet)

        /** 读入调用方提供的缓冲区（避免高频读取时反复分配大数组）。 */
        fun streamIn(cdb: ByteArray, buffer: ByteArray, offset: Int, length: Int, name: String, timeoutMs: Int) =
            ScsiCommand(cdb, DataDirection.IN, buffer, offset, length, timeoutMs, name, streaming = true)
    }
}

enum class TransportErrorKind(val label: String) {
    DEVICE_GONE("设备已断开"),
    TIMEOUT("超时"),
    STALL("端点 STALL"),
    PHASE_ERROR("BOT 阶段错误"),
    PROTOCOL_ERROR("BOT 协议错误"),
    IO_ERROR("I/O 错误"),
    CLOSED("传输层已关闭"),
}

/** 命令执行结果。 */
sealed interface ScsiResult {
    /** 实际传输的数据字节数。 */
    val transferred: Int

    /** 命令成功（CSW 状态 0）。 */
    class Good(override val transferred: Int, val residue: Int) : ScsiResult {
        override fun toString() = "GOOD（${transferred} 字节，residue $residue）"
    }

    /** CHECK CONDITION（CSW 状态 1），[sense] 是自动 REQUEST SENSE 的结果（失败时为 null）。 */
    class CheckCondition(override val transferred: Int, val sense: SenseData?) : ScsiResult {
        override fun toString() = "CHECK CONDITION（${sense?.code() ?: "无 sense"}）"
    }

    /** 传输层失败（STALL 无法恢复、超时、阶段错误、设备断开……），命令结果未知。 */
    class TransportError(override val transferred: Int, val kind: TransportErrorKind, val detail: String) : ScsiResult {
        override fun toString() = "传输错误：${kind.label}（$detail）"
    }

    /** 被只读守卫拒绝，没有发给光驱。 */
    class Refused(val reason: String) : ScsiResult {
        override val transferred: Int get() = 0
        override fun toString() = "已拒绝：$reason"
    }
}

interface ScsiTransport : Closeable {
    /** 例如 "BOT over USB 0411:036D (LUN 0)"。 */
    val description: String

    /** 同步执行一条命令。调用方必须保证串行（BOT 同一时刻只能有一条命令在途）。 */
    fun execute(cmd: ScsiCommand): ScsiResult

    /** 传输层自己做过的复位恢复次数（之后光驱报告 UNIT ATTENTION 29h 属于预期）。 */
    val resetCount: Long get() = 0
}
