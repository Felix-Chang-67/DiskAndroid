package io.github.felixchang67.diskandroid.core.cdda

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.mmc.Msf
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.scsi.SenseKey
import kotlinx.coroutines.delay

/**
 * 读 CD-DA 扇区（READ CD），带重试与降级：
 * 1. 整块读，失败再整块读一次（很多光驱第二次就能读出来）；
 * 2. 还不行就逐扇区读，每个扇区最多 [SECTOR_TRIES] 次；
 * 3. 仍然失败的扇区填静音并记录（播放不中断）；
 * 4. 每块有时间预算 [budgetMs]，超时后剩下的扇区直接静音，保证播放不会卡死在一张划伤的盘上。
 * 盘被取出、光驱断开时立即停止。
 *
 * @param exec 在光驱会话线程上执行命令，返回 null 表示光驱已不在线（[io.github.felixchang67.diskandroid.core.drive.DriveSession.read]）。
 * @param useDap 光驱声明支持 DAP（GET CONFIGURATION 001Eh）时置位；光驱拒绝时自动关闭。
 */
class CddaReader(
    private val exec: suspend (ScsiCommand) -> ScsiResult?,
    private val log: DiskLog,
    var useDap: Boolean = false,
    private val budgetMs: Long = 6_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    enum class StopReason(val message: String) {
        NO_MEDIUM("盘片已取出"),
        DRIVE_GONE("光驱已断开或不可用"),
        REFUSED("命令被只读守卫拒绝"),
    }

    sealed interface Result {
        data object Ok : Result

        /** 读完了，但 [bad] 这些 LBA 读不出来，已填静音。 */
        class Partial(val bad: List<Long>, val lastError: String) : Result

        class Stop(val reason: StopReason, val detail: String) : Result
    }

    private sealed interface Attempt {
        data object Ok : Attempt
        class Failed(val detail: String) : Attempt

        /** 传输层错误（STALL / I/O 错误 / 超时，BOT 层已做过恢复），不是盘面问题。 */
        class Transport(val detail: String) : Attempt
        class Stop(val reason: StopReason, val detail: String) : Attempt
    }

    var commands = 0L
        private set
    var retries = 0L
        private set

    /** 从 [lba] 起读 [count] 个扇区到 [buffer]（从下标 0 开始，需要 count × 2352 字节）。 */
    suspend fun read(lba: Long, count: Int, buffer: ByteArray): Result {
        require(buffer.size >= count * SECTOR_BYTES)
        var deadline = clock() + budgetMs
        var lastError = ""
        var transportRetries = 0
        var round = 0
        while (round < 2) {
            when (val a = attempt(lba, count, buffer, 0)) {
                Attempt.Ok -> return Result.Ok
                is Attempt.Stop -> return Result.Stop(a.reason, a.detail)
                is Attempt.Transport -> {
                    // 传输层出错（已经复位恢复）：数据本身没问题，整块重读；复位耗的时间不算进坏扇区的时间预算
                    lastError = a.detail
                    if (transportRetries < MAX_TRANSPORT_RETRIES) {
                        transportRetries++
                        retries++
                        log.w(TAG, "传输出错后重读这一块（第 $transportRetries 次）", "起点" to "$lba（${Msf.format(lba)}）", "错误" to a.detail)
                        deadline = clock() + budgetMs
                        continue
                    }
                    round++
                }
                is Attempt.Failed -> {
                    lastError = a.detail
                    round++
                }
            }
            if (round == 1) retries++
            if (clock() > deadline) break
        }
        log.w(TAG, "整块读取失败，改为逐扇区读取", "起点" to "$lba（${Msf.format(lba)}）", "扇区数" to count, "错误" to lastError)

        val bad = mutableListOf<Long>()
        for (i in 0 until count) {
            var ok = false
            var tries = 0
            while (!ok && tries < SECTOR_TRIES && clock() <= deadline) {
                when (val a = attempt(lba + i, 1, buffer, i * SECTOR_BYTES)) {
                    Attempt.Ok -> ok = true
                    is Attempt.Stop -> return Result.Stop(a.reason, a.detail)
                    is Attempt.Transport -> {
                        lastError = a.detail
                        tries++
                        retries++
                        if (transportRetries < MAX_TRANSPORT_RETRIES) {
                            transportRetries++
                            deadline = clock() + budgetMs
                        }
                    }
                    is Attempt.Failed -> {
                        lastError = a.detail
                        tries++
                        retries++
                    }
                }
            }
            if (!ok) {
                buffer.fill(0, i * SECTOR_BYTES, (i + 1) * SECTOR_BYTES)
                bad += lba + i
            }
        }
        if (bad.isEmpty()) return Result.Ok
        val timedOut = clock() > deadline
        log.w(
            TAG, "${bad.size} 个扇区读不出来，已静音",
            "范围" to ranges(bad),
            "最后的错误" to lastError,
            "原因" to if (timedOut) "超过 ${budgetMs / 1000} 秒时间预算" else "重试 $SECTOR_TRIES 次仍失败",
        )
        return Result.Partial(bad, lastError)
    }

    /**
     * SET CD SPEED。播放时降到 4 倍速左右：噪音、功耗都更低，读一块 24 扇区只要约 80 ms，余量足够重试。
     * 只是建议值，光驱会取最接近的档位；失败不影响播放。
     */
    suspend fun setSpeed(kbps: Int): Boolean {
        val r = exec(MmcCommands.setCdSpeed(kbps)) ?: return false
        val ok = r is ScsiResult.Good
        log.i(TAG, if (kbps == 0xFFFF) "读取速度恢复为光驱最大值" else "设置读取速度 $kbps kB/s（约 ${kbps / 176} 倍速）", "结果" to r.toString())
        return ok
    }

    private suspend fun attempt(lba: Long, count: Int, buffer: ByteArray, offset: Int): Attempt {
        var dapRetry = false
        while (true) {
            commands++
            val cmd = MmcCommands.readCdda(lba, count, buffer, offset, useDap)
            val r = exec(cmd) ?: return Attempt.Stop(StopReason.DRIVE_GONE, "光驱不在线")
            when (r) {
                is ScsiResult.Good -> {
                    val expected = count * SECTOR_BYTES
                    if (r.transferred >= expected) return Attempt.Ok
                    return Attempt.Failed("只传回 ${r.transferred}/$expected 字节")
                }
                is ScsiResult.CheckCondition -> {
                    val s = r.sense ?: return Attempt.Failed("CHECK CONDITION（无 sense）")
                    when {
                        s.senseKey == SenseKey.NOT_READY && s.asc == 0x3A -> return Attempt.Stop(StopReason.NO_MEDIUM, s.describe())
                        s.senseKey == SenseKey.NOT_READY && s.asc == 0x04 -> {
                            // 正在就绪（例如休眠后重新转起来）：稍等再试
                            sleep(300)
                            return Attempt.Failed(s.describe())
                        }
                        s.senseKey == SenseKey.ILLEGAL_REQUEST && s.asc == 0x24 && useDap && !dapRetry -> {
                            useDap = false
                            dapRetry = true
                            log.w(TAG, "光驱不接受 READ CD 的 DAP 位，改为不带 DAP 读取", "sense" to s.code())
                        }
                        else -> return Attempt.Failed(s.describe())
                    }
                }
                is ScsiResult.TransportError -> return Attempt.Transport(r.toString())
                is ScsiResult.Refused -> return Attempt.Stop(StopReason.REFUSED, r.reason)
            }
        }
    }

    companion object {
        const val TAG = "CDDA"
        const val SECTOR_TRIES = 3

        /** 每块最多因传输错误重读几次（每次之前 BOT 层可能已经复位并等了 6 秒）。 */
        const val MAX_TRANSPORT_RETRIES = 2

        fun ranges(lbas: List<Long>): String {
            if (lbas.isEmpty()) return ""
            val out = mutableListOf<String>()
            var start = lbas.first()
            var prev = start
            for (l in lbas.drop(1) + Long.MIN_VALUE) {
                if (l != prev + 1) {
                    out += if (start == prev) "$start（${Msf.format(start)}）" else "$start–$prev（${Msf.format(start)}–${Msf.format(prev)}）"
                    start = l
                }
                prev = l
            }
            return out.joinToString("，")
        }
    }
}
