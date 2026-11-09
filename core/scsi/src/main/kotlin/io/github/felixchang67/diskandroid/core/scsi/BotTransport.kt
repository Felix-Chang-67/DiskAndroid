package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogLevel

/**
 * USB 层的批量传输接口。Android 实现包装 UsbDeviceConnection；测试里用模拟设备。
 *
 * 失败时返回负数。Android 的 bulkTransfer 只返回 -1（拿不到 errno），所以调用方要结合
 * [isAttached] 和耗时来判断是断开、超时还是 STALL；能拿到 errno 的实现（JNI）返回 -errno。
 */
interface BulkIo {
    val inEndpoint: Int
    val outEndpoint: Int
    val interfaceNumber: Int

    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** CLEAR_FEATURE(ENDPOINT_HALT)，并由内核复位主机侧 data toggle。返回 0 成功，-errno 失败。 */
    fun clearHalt(endpoint: Int): Int

    /** Bulk-Only Mass Storage Reset 类请求（bmRequestType 21h，bRequest FFh）。返回 >= 0 成功。 */
    fun massStorageReset(): Int

    /** 设备是否仍然连着（尽力判断）。 */
    fun isAttached(): Boolean

    /** 单调时钟（毫秒），测试里可替换。 */
    fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/** BOT 层的统计（写进诊断信息）。 */
class BotStats {
    @Volatile var commands = 0L
    @Volatile var checkConditions = 0L
    @Volatile var stalls = 0L
    @Volatile var timeouts = 0L
    @Volatile var resets = 0L
    @Volatile var skippedDataPhase = 0L
    @Volatile var zeroLengthCsw = 0L
    @Volatile var lastResetAtMs = -1L

    override fun toString() =
        "命令 $commands，CHECK CONDITION $checkConditions，STALL $stalls，超时 $timeouts，" +
            "复位恢复 $resets，跳过数据阶段 $skippedDataPhase，0 长度 CSW $zeroLengthCsw"
}

/**
 * USB Mass Storage Class Bulk-Only Transport 1.0 的主机端实现。
 *
 * 流程与错误恢复参照 BOT 规范第 5、6 章和 Linux usb-storage（drivers/usb/storage/transport.c）：
 * - CBW 31 字节 → 数据阶段 → CSW 13 字节；校验签名、Tag、状态。
 * - 数据阶段 STALL：清该端点 HALT，然后照常读 CSW。
 * - 读 CSW 时 STALL：清 bulk-in HALT 后重读一次；收到 0 长度包也重读一次。
 * - 数据阶段直接收到 13 字节且是本命令的 CSW：设备跳过了数据阶段，按 CSW 处理。
 * - Phase Error、CSW 无效、超时、CBW 发送失败：复位恢复（类复位 → 等待 → 清两个端点 HALT）。
 * - CSW 状态 1：自动发 REQUEST SENSE（BOT 没有自动 sense）。
 */
class BotTransport(
    private val io: BulkIo,
    private val lun: Int,
    private val log: DiskLog,
    override val description: String,
    /** Linux usb-storage 在类复位后等待 6 秒再清 HALT。 */
    private val resetSettleMs: Long = 6_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : ScsiTransport {
    companion object {
        const val CBW_LENGTH = 31
        const val CSW_LENGTH = 13
        const val CBW_SIGNATURE = 0x43425355 // "USBC"（小端）
        const val CSW_SIGNATURE = 0x53425355 // "USBS"（小端）
        const val CSW_STATUS_PASSED = 0
        const val CSW_STATUS_FAILED = 1
        const val CSW_STATUS_PHASE_ERROR = 2
        const val SENSE_LENGTH = 18
        private const val CBW_TIMEOUT_MS = 5_000
        private const val ENODEV = 19
        private const val TAG = "BOT"

        fun buildCbw(tag: Int, dataLength: Int, dataIn: Boolean, lun: Int, cdb: ByteArray): ByteArray {
            require(cdb.size in 1..16)
            val cbw = ByteArray(CBW_LENGTH)
            putLe32(cbw, 0, CBW_SIGNATURE)
            putLe32(cbw, 4, tag)
            putLe32(cbw, 8, dataLength)
            cbw[12] = if (dataIn) 0x80.toByte() else 0
            cbw[13] = (lun and 0x0F).toByte()
            cbw[14] = cdb.size.toByte()
            cdb.copyInto(cbw, 15)
            return cbw
        }

        fun le32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        private fun putLe32(b: ByteArray, at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v ushr 8).toByte()
            b[at + 2] = (v ushr 16).toByte()
            b[at + 3] = (v ushr 24).toByte()
        }
    }

    val stats = BotStats()

    override val resetCount: Long get() = stats.resets

    private var tag = 0
    private var learnedCswSignature: Int? = null

    @Volatile
    private var closed = false

    /** 一次 BOT 交互的原始结果（不含自动 sense）。 */
    private sealed interface Transaction {
        data class Completed(val status: Int, val transferred: Int, val residue: Int) : Transaction
        data class Failed(val kind: TransportErrorKind, val detail: String, val transferred: Int) : Transaction
    }

    override fun execute(cmd: ScsiCommand): ScsiResult {
        if (closed) return ScsiResult.TransportError(0, TransportErrorKind.CLOSED, "传输层已关闭")
        stats.commands++
        return when (val t = transact(cmd)) {
            is Transaction.Failed -> ScsiResult.TransportError(t.transferred, t.kind, t.detail)
            is Transaction.Completed -> when (t.status) {
                CSW_STATUS_PASSED -> ScsiResult.Good(t.transferred, t.residue)
                else -> {
                    stats.checkConditions++
                    ScsiResult.CheckCondition(t.transferred, requestSense())
                }
            }
        }
    }

    override fun close() {
        closed = true
    }

    /** 自动 REQUEST SENSE（固定格式，18 字节；Linux usb-storage 也用 18）。失败返回 null。 */
    private fun requestSense(): SenseData? {
        val buf = ByteArray(SENSE_LENGTH)
        val cdb = byteArrayOf(0x03, 0, 0, 0, SENSE_LENGTH.toByte(), 0)
        val cmd = ScsiCommand(cdb, DataDirection.IN, buf, 0, SENSE_LENGTH, 5_000, "REQUEST SENSE（自动）")
        return when (val t = transact(cmd)) {
            is Transaction.Completed -> if (t.status == CSW_STATUS_PASSED) {
                SenseData.parse(buf, t.transferred)
            } else {
                log.w(TAG, "自动 REQUEST SENSE 本身失败", "CSW 状态" to t.status)
                null
            }
            is Transaction.Failed -> {
                log.w(TAG, "自动 REQUEST SENSE 传输失败", "原因" to "${t.kind.label}：${t.detail}")
                null
            }
        }
    }

    private fun transact(cmd: ScsiCommand): Transaction {
        tag++
        val myTag = tag
        val dataIn = cmd.direction == DataDirection.IN
        val cbw = buildCbw(myTag, cmd.length, dataIn, lun, cmd.cdb)
        if (!cmd.quiet) log.data(LogLevel.VERBOSE, TAG, "CBW tag=$myTag", cbw)

        // 1. 命令阶段
        var t0 = io.nowMs()
        val sent = io.bulkOut(cbw, 0, CBW_LENGTH, CBW_TIMEOUT_MS)
        if (sent != CBW_LENGTH) {
            val kind = classify(sent, t0, CBW_TIMEOUT_MS)
            if (kind == TransportErrorKind.DEVICE_GONE) return Transaction.Failed(kind, "发送 CBW 时设备断开", 0)
            resetRecovery("发送 CBW 失败（返回 $sent，${kind.label}）")
            return Transaction.Failed(kind, "发送 CBW 失败（返回 $sent）", 0)
        }

        // 2. 数据阶段
        var transferred = 0
        var csw: ByteArray? = null
        if (cmd.direction != DataDirection.NONE && cmd.length > 0) {
            val buf = cmd.buffer!!
            val endpoint = if (dataIn) io.inEndpoint else io.outEndpoint
            t0 = io.nowMs()
            val n = if (dataIn) {
                io.bulkIn(buf, cmd.offset, cmd.length, cmd.timeoutMs)
            } else {
                io.bulkOut(buf, cmd.offset, cmd.length, cmd.timeoutMs)
            }
            if (n >= 0) {
                transferred = n
                if (dataIn && n == CSW_LENGTH && n < cmd.length && isCswFor(buf, cmd.offset, myTag)) {
                    // Linux："Device skipped data phase"
                    stats.skippedDataPhase++
                    log.w(TAG, "设备跳过了数据阶段，直接发来了 CSW", "tag" to myTag)
                    csw = buf.copyOfRange(cmd.offset, cmd.offset + CSW_LENGTH)
                    buf.fill(0, cmd.offset, cmd.offset + CSW_LENGTH)
                    transferred = 0
                }
            } else {
                when (val kind = classify(n, t0, cmd.timeoutMs)) {
                    TransportErrorKind.DEVICE_GONE -> return Transaction.Failed(kind, "数据阶段设备断开", 0)
                    TransportErrorKind.TIMEOUT -> {
                        stats.timeouts++
                        resetRecovery("数据阶段超时（${cmd.timeoutMs} ms）")
                        return Transaction.Failed(kind, "数据阶段超时（${cmd.timeoutMs} ms）", 0)
                    }
                    else -> {
                        stats.stalls++
                        val r = io.clearHalt(endpoint)
                        if (r == -ENODEV) return Transaction.Failed(TransportErrorKind.DEVICE_GONE, "清除 HALT 时设备已断开", 0)
                        log.w(TAG, "数据阶段 STALL，已清除端点 HALT", "端点" to "0x${Hex.byte(endpoint)}", "结果" to r)
                    }
                }
            }
        }

        // 3. 状态阶段
        val status = csw ?: when (val r = readCsw(cmd.timeoutMs)) {
            is CswRead.Ok -> r.bytes
            is CswRead.Error -> return Transaction.Failed(r.kind, r.detail, transferred)
        }
        if (!cmd.quiet) log.data(LogLevel.VERBOSE, TAG, "CSW tag=$myTag", status)

        val signature = le32(status, 0)
        val cswTag = le32(status, 4)
        val residue = le32(status, 8)
        val cswStatus = status[12].toInt() and 0xFF
        if (cswTag != myTag || cswStatus > CSW_STATUS_PHASE_ERROR) {
            resetRecovery("CSW 无效（tag ${cswTag}≠$myTag 或状态 $cswStatus）")
            return Transaction.Failed(TransportErrorKind.PROTOCOL_ERROR, "CSW 无效：${Hex.encode(status)}", transferred)
        }
        // 与 Linux 一样：不强求标准签名，记住第一次看到的签名，之后必须一致
        val learned = learnedCswSignature
        if (learned == null) {
            learnedCswSignature = signature
            if (signature != CSW_SIGNATURE) log.w(TAG, "设备的 CSW 签名不标准：0x${Hex.hex(signature, 8)}，已记住")
        } else if (signature != learned) {
            resetRecovery("CSW 签名不一致")
            return Transaction.Failed(TransportErrorKind.PROTOCOL_ERROR, "CSW 签名不一致：${Hex.encode(status)}", transferred)
        }
        if (cswStatus == CSW_STATUS_PHASE_ERROR) {
            resetRecovery("CSW 报告 Phase Error")
            return Transaction.Failed(TransportErrorKind.PHASE_ERROR, "CSW 报告 Phase Error", transferred)
        }
        return Transaction.Completed(cswStatus, transferred, residue)
    }

    private sealed interface CswRead {
        class Ok(val bytes: ByteArray) : CswRead
        class Error(val kind: TransportErrorKind, val detail: String) : CswRead
    }

    private fun readCsw(timeoutMs: Int): CswRead {
        val buf = ByteArray(CSW_LENGTH)
        var t0 = io.nowMs()
        var n = io.bulkIn(buf, 0, CSW_LENGTH, timeoutMs)
        if (n == 0) {
            // Linux："Received 0-length CSW; retrying..."
            stats.zeroLengthCsw++
            log.w(TAG, "收到 0 长度的 CSW，重读一次")
            t0 = io.nowMs()
            n = io.bulkIn(buf, 0, CSW_LENGTH, timeoutMs)
        }
        if (n < 0) {
            when (val kind = classify(n, t0, timeoutMs)) {
                TransportErrorKind.DEVICE_GONE -> return CswRead.Error(kind, "读取 CSW 时设备断开")
                TransportErrorKind.TIMEOUT -> {
                    stats.timeouts++
                    resetRecovery("读取 CSW 超时（$timeoutMs ms）")
                    return CswRead.Error(kind, "读取 CSW 超时（$timeoutMs ms）")
                }
                else -> {
                    stats.stalls++
                    val r = io.clearHalt(io.inEndpoint)
                    if (r == -ENODEV) return CswRead.Error(TransportErrorKind.DEVICE_GONE, "清除 HALT 时设备已断开")
                    log.w(TAG, "读取 CSW 时 STALL，已清除 bulk-in HALT，重读一次", "结果" to r)
                    t0 = io.nowMs()
                    n = io.bulkIn(buf, 0, CSW_LENGTH, timeoutMs)
                    if (n < 0) {
                        val kind2 = classify(n, t0, timeoutMs)
                        if (kind2 == TransportErrorKind.DEVICE_GONE) return CswRead.Error(kind2, "读取 CSW 时设备断开")
                        resetRecovery("第二次读取 CSW 仍然失败（${kind2.label}）")
                        return CswRead.Error(kind2, "第二次读取 CSW 失败")
                    }
                }
            }
        }
        if (n != CSW_LENGTH) {
            resetRecovery("CSW 长度错误（$n 字节）")
            return CswRead.Error(TransportErrorKind.PROTOCOL_ERROR, "CSW 长度错误：$n 字节")
        }
        return CswRead.Ok(buf)
    }

    private fun isCswFor(buf: ByteArray, offset: Int, expectedTag: Int): Boolean {
        val sig = le32(buf, offset)
        val okSig = sig == CSW_SIGNATURE || sig == learnedCswSignature
        return okSig && le32(buf, offset + 4) == expectedTag && (buf[offset + 12].toInt() and 0xFF) <= CSW_STATUS_PHASE_ERROR
    }

    /**
     * 判断失败原因。Android 的 bulkTransfer 失败只返回 -1：
     * 设备不在了 → 断开；耗时接近超时 → 超时；否则按 STALL 处理（后续清 HALT 时若返回 ENODEV 再改判为断开）。
     */
    private fun classify(result: Int, startedAt: Long, timeoutMs: Int): TransportErrorKind {
        if (result == -ENODEV || !io.isAttached()) return TransportErrorKind.DEVICE_GONE
        val elapsed = io.nowMs() - startedAt
        return if (elapsed >= timeoutMs - 50) TransportErrorKind.TIMEOUT else TransportErrorKind.STALL
    }

    /** BOT 5.3.4 复位恢复：Bulk-Only Mass Storage Reset → 等待 → 清 bulk-in HALT → 清 bulk-out HALT。 */
    private fun resetRecovery(reason: String) {
        if (!io.isAttached()) {
            log.w(TAG, "需要复位恢复，但设备已断开，跳过", "原因" to reason)
            return
        }
        stats.resets++
        stats.lastResetAtMs = io.nowMs()
        log.w(TAG, "执行 BOT 复位恢复：$reason")
        val r = io.massStorageReset()
        log.log(if (r >= 0) LogLevel.INFO else LogLevel.ERROR, TAG, "Bulk-Only Mass Storage Reset", listOf("结果" to r))
        if (resetSettleMs > 0) sleeper(resetSettleMs)
        val rIn = io.clearHalt(io.inEndpoint)
        val rOut = io.clearHalt(io.outEndpoint)
        log.i(TAG, "复位恢复完成", "清 bulk-in HALT" to rIn, "清 bulk-out HALT" to rOut)
    }
}
