package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogLevel
import java.util.zip.CRC32

/**
 * 给每条 SCSI 命令写 hex 日志：序号、命令名、CDB、方向/长度、结果、sense（原始 hex + 解码）、耗时，
 * 以及返回的数据（小数据全量；大数据只记前 64 字节 + CRC32，避免日志爆炸）。
 *
 * 标记为 quiet 的轮询命令：结果与上一次相同就不重复记录，只计数；结果变化时补一条"省略了 N 次"。
 */
class LoggingScsiTransport(
    private val inner: ScsiTransport,
    private val log: DiskLog,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ScsiTransport {
    companion object {
        const val TAG = "SCSI"

        /** 流式读取（播放、抓轨）成功时的精简日志用这个标签，方便内存日志面板过滤掉。 */
        const val STREAM_TAG = "SCSI-STREAM"
        const val FULL_DATA_LIMIT = 4096
        const val PREVIEW_BYTES = 64
    }

    override val description: String get() = inner.description
    override val resetCount: Long get() = inner.resetCount

    private var seq = 0L
    private val lastQuiet = HashMap<String, String>()
    private val suppressed = HashMap<String, Int>()

    override fun execute(cmd: ScsiCommand): ScsiResult {
        val id = ++seq
        val t0 = clock()
        val result = inner.execute(cmd)
        val ms = clock() - t0

        if (cmd.quiet) {
            val key = cmd.displayName + ":" + Hex.compact(cmd.cdb)
            val signature = signature(cmd, result)
            if (lastQuiet[key] == signature) {
                suppressed[key] = (suppressed[key] ?: 0) + 1
                return result
            }
            lastQuiet[key] = signature
            suppressed.remove(key)?.let { n ->
                log.v(TAG, "（之前省略了 $n 次结果相同的「${cmd.displayName}」轮询）")
            }
        }
        if (cmd.streaming && result is ScsiResult.Good) {
            log.v(STREAM_TAG, "#$id ${cmd.displayName}  CDB=${Hex.compact(cmd.cdb)}  IN ${cmd.length}  GOOD ${result.transferred}  ${ms}ms")
            return result
        }
        write(id, cmd, result, ms)
        return result
    }

    override fun close() = inner.close()

    private fun write(id: Long, cmd: ScsiCommand, result: ScsiResult, ms: Long) {
        val fields = mutableListOf<Pair<String, Any?>>(
            "CDB" to Hex.encode(cmd.cdb),
            "方向" to when (cmd.direction) {
                DataDirection.NONE -> "无数据"
                DataDirection.IN -> "IN ${cmd.length}"
                DataDirection.OUT -> "OUT ${cmd.length}"
            },
            "结果" to resultText(result),
            "耗时ms" to ms,
        )
        var level = if (cmd.quiet) LogLevel.VERBOSE else LogLevel.DEBUG
        var data: ByteArray? = null
        when (result) {
            is ScsiResult.Good -> {
                fields += "传输字节" to result.transferred
                if (result.residue != 0) fields += "residue" to result.residue
                if (cmd.direction == DataDirection.IN && result.transferred > 0) {
                    val buf = cmd.buffer!!
                    if (cmd.sensitive) {
                        fields += "数据" to "（密钥相关，已隐藏）"
                    } else if (result.transferred <= FULL_DATA_LIMIT) {
                        data = buf.copyOfRange(cmd.offset, cmd.offset + result.transferred)
                    } else {
                        data = buf.copyOfRange(cmd.offset, cmd.offset + PREVIEW_BYTES)
                        val crc = CRC32().apply { update(buf, cmd.offset, result.transferred) }.value
                        fields += "数据" to "只记录前 $PREVIEW_BYTES 字节，CRC32=${Hex.hex(crc.toInt(), 8)}"
                    }
                }
            }

            is ScsiResult.CheckCondition -> {
                val sense = result.sense
                if (sense != null) {
                    fields += "sense" to Hex.encode(sense.raw)
                    fields += "解码" to sense.describe()
                    level = when (sense.senseKey) {
                        SenseKey.NOT_READY, SenseKey.UNIT_ATTENTION, SenseKey.NO_SENSE, SenseKey.RECOVERED_ERROR ->
                            if (cmd.quiet) LogLevel.VERBOSE else LogLevel.INFO
                        else -> LogLevel.WARN
                    }
                } else {
                    fields += "sense" to "（获取失败）"
                    level = LogLevel.WARN
                }
                if (result.transferred > 0) fields += "传输字节" to result.transferred
            }

            is ScsiResult.TransportError -> {
                fields += "说明" to result.detail
                level = LogLevel.ERROR
            }

            is ScsiResult.Refused -> level = LogLevel.ERROR
        }
        log.log(level, TAG, "#$id ${cmd.displayName}", fields, data = data)
    }

    private fun resultText(result: ScsiResult): String = when (result) {
        is ScsiResult.Good -> "GOOD"
        is ScsiResult.CheckCondition -> "CHECK CONDITION ${result.sense?.code() ?: ""}".trim()
        is ScsiResult.TransportError -> "传输错误：${result.kind.label}"
        is ScsiResult.Refused -> "已拒绝：${result.reason}"
    }

    private fun signature(cmd: ScsiCommand, result: ScsiResult): String = when (result) {
        is ScsiResult.Good -> {
            val data = if (cmd.direction == DataDirection.IN) {
                Hex.compact(cmd.buffer!!, cmd.offset, minOf(result.transferred, 256))
            } else {
                ""
            }
            "G:$data"
        }
        is ScsiResult.CheckCondition -> "C:" + (result.sense?.code() ?: "-")
        is ScsiResult.TransportError -> "T:" + result.kind.name
        is ScsiResult.Refused -> "R:" + result.reason
    }
}
