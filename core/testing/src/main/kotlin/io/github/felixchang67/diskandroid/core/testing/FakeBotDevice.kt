package io.github.felixchang67.diskandroid.core.testing

import io.github.felixchang67.diskandroid.core.scsi.BotTransport
import io.github.felixchang67.diskandroid.core.scsi.BulkIo
import io.github.felixchang67.diskandroid.core.scsi.DataDirection
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.scsi.ScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.SenseData

/**
 * 在 USB 层模拟 Bulk-Only Transport 设备（后面接 [FakeMmc]），可以注入各种传输故障。
 * 行为与真实设备一致：失败的 bulk 传输返回 -1；STALL 的端点在被清除 HALT 之前一直失败。
 */
class FakeBotDevice(val mmc: FakeMmc = FakeMmc()) : BulkIo {
    enum class Fault {
        /** 数据阶段 bulk-in STALL（随后 CSW 状态为 1）。 */
        STALL_DATA_IN,
        /** 数据阶段直接发 CSW（"Device skipped data phase"）。 */
        SKIP_DATA_PHASE,
        /** 第一次读 CSW 收到 0 长度包。 */
        ZERO_LENGTH_CSW,
        /** 第一次读 CSW 时 STALL。 */
        STALL_CSW,
        /** CSW 状态 2（Phase Error）。 */
        PHASE_ERROR,
        /** CSW 的 tag 不对。 */
        WRONG_TAG,
        /** 数据阶段超时。 */
        TIMEOUT_DATA_IN,
        /** 发送 CBW 后设备被拔出。 */
        DISCONNECT_AFTER_CBW,
        /** 数据阶段返回 -EPROTO（I/O 错误，设备仍停在数据阶段；不复位就会一直错位）。 */
        IO_ERROR_DATA_IN,
    }

    override val inEndpoint = 0x81
    override val outEndpoint = 0x02
    override val interfaceNumber = 0

    /** 下一条命令要注入的故障（每条命令消耗一个；null 表示正常）。 */
    val faults = ArrayDeque<Fault?>()

    val cbws = mutableListOf<ByteArray>()
    val clearHaltCalls = mutableListOf<Int>()
    var resetCalls = 0
    var attached = true
    private var clock = 0L
    private val halted = mutableSetOf<Int>()

    private enum class Phase { CBW, DATA_IN, DATA_OUT, CSW }

    private var phase = Phase.CBW
    private var currentFault: Fault? = null
    private var tag = 0
    private var expected = 0
    private var pendingData = ByteArray(0)
    private var status = 0
    private var zeroLengthPending = false
    private var cswStallPending = false

    override fun nowMs(): Long = clock

    override fun isAttached(): Boolean = attached

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        clock += 1
        if (!attached || outEndpoint in halted) return -1
        when (phase) {
            Phase.CBW -> {
                val cbw = buffer.copyOfRange(offset, offset + length)
                cbws += cbw
                if (length != BotTransport.CBW_LENGTH || BotTransport.le32(cbw, 0) != BotTransport.CBW_SIGNATURE) {
                    halted += outEndpoint
                    halted += inEndpoint
                    return -1
                }
                tag = BotTransport.le32(cbw, 4)
                expected = BotTransport.le32(cbw, 8)
                val dataIn = (cbw[12].toInt() and 0x80) != 0
                val cdb = cbw.copyOfRange(15, 15 + (cbw[14].toInt() and 0x1F))
                currentFault = faults.removeFirstOrNull()
                if (currentFault == Fault.DISCONNECT_AFTER_CBW) {
                    attached = false
                    return length
                }
                if (expected > 0 && !dataIn) {
                    phase = Phase.DATA_OUT
                    pendingData = cdb // 暂存 CDB，等数据到了再处理
                    return length
                }
                val reply = mmc.handle(cdb)
                status = reply.status
                pendingData = reply.data.copyOf(minOf(reply.data.size, expected))
                phase = if (expected > 0) Phase.DATA_IN else Phase.CSW
                zeroLengthPending = currentFault == Fault.ZERO_LENGTH_CSW
                cswStallPending = currentFault == Fault.STALL_CSW
                return length
            }
            Phase.DATA_OUT -> {
                val reply = mmc.handle(pendingData, buffer.copyOfRange(offset, offset + length))
                status = reply.status
                pendingData = ByteArray(0)
                phase = Phase.CSW
                return length
            }
            else -> {
                halted += outEndpoint
                return -1
            }
        }
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        clock += 1
        if (!attached || inEndpoint in halted) return -1
        when (phase) {
            Phase.DATA_IN -> when (currentFault) {
                Fault.STALL_DATA_IN -> {
                    halted += inEndpoint
                    status = 1
                    phase = Phase.CSW
                    return -1
                }
                Fault.TIMEOUT_DATA_IN -> {
                    clock += timeoutMs
                    return -1
                }
                Fault.IO_ERROR_DATA_IN -> return -71
                Fault.SKIP_DATA_PHASE -> {
                    phase = Phase.CBW
                    return csw(expected).copyInto(buffer, offset).let { BotTransport.CSW_LENGTH }
                }
                else -> {
                    val n = minOf(length, pendingData.size)
                    pendingData.copyInto(buffer, offset, 0, n)
                    phase = Phase.CSW
                    return n
                }
            }
            Phase.CSW -> {
                if (zeroLengthPending) {
                    zeroLengthPending = false
                    return 0
                }
                if (cswStallPending) {
                    cswStallPending = false
                    halted += inEndpoint
                    return -1
                }
                val residue = expected - if (status == 0) pendingData.size else 0
                csw(residue).copyInto(buffer, offset)
                phase = Phase.CBW
                return BotTransport.CSW_LENGTH
            }
            else -> {
                halted += inEndpoint
                return -1
            }
        }
    }

    private fun csw(residue: Int): ByteArray {
        val b = ByteArray(BotTransport.CSW_LENGTH)
        put(b, 0, BotTransport.CSW_SIGNATURE)
        put(b, 4, if (currentFault == Fault.WRONG_TAG) tag + 1 else tag)
        put(b, 8, residue)
        b[12] = (if (currentFault == Fault.PHASE_ERROR) 2 else status).toByte()
        return b
    }

    override fun clearHalt(endpoint: Int): Int {
        clock += 1
        clearHaltCalls += endpoint
        if (!attached) return -19
        halted -= endpoint
        return 0
    }

    override fun massStorageReset(): Int {
        clock += 1
        resetCalls++
        if (!attached) return -1
        phase = Phase.CBW
        // 真实设备在 BOT 复位后通常会报告 UNIT ATTENTION 29h/03h（BUS DEVICE RESET FUNCTION OCCURRED）
        mmc.pendingUnitAttention.addLast(0x29 to 0x03)
        return 0
    }

    private fun put(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v ushr 8).toByte()
        b[at + 2] = (v ushr 16).toByte()
        b[at + 3] = (v ushr 24).toByte()
    }
}

/** 直接在 SCSI 层对接 [FakeMmc]（绕过 BOT），用于测试上层逻辑。 */
class FakeMmcTransport(val mmc: FakeMmc = FakeMmc()) : ScsiTransport {
    override val description = "FakeMmc"
    var disconnected = false

    override fun execute(cmd: ScsiCommand): ScsiResult {
        if (disconnected) {
            return ScsiResult.TransportError(0, io.github.felixchang67.diskandroid.core.scsi.TransportErrorKind.DEVICE_GONE, "模拟拔出")
        }
        val reply = mmc.handle(cmd.cdb, if (cmd.direction == DataDirection.OUT) cmd.buffer?.copyOfRange(cmd.offset, cmd.offset + cmd.length) else null)
        if (reply.status == FakeMmc.Reply.GOOD) {
            var n = 0
            if (cmd.direction == DataDirection.IN) {
                n = minOf(cmd.length, reply.data.size)
                reply.data.copyInto(cmd.buffer!!, cmd.offset, 0, n)
            }
            return ScsiResult.Good(n, cmd.length - n)
        }
        val senseReply = mmc.handle(byteArrayOf(0x03, 0, 0, 0, 18, 0))
        return ScsiResult.CheckCondition(0, SenseData.parse(senseReply.data))
    }

    override fun close() {}
}
