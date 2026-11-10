package io.github.felixchang67.diskandroid.core.rip

import io.github.felixchang67.diskandroid.core.fs.DiscText
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.scsi.SenseKey
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32

/** 镜像输出：支持按位置写（第 2 遍补读的扇区要写回原位置）和读（写完后重算校验值）。 */
interface RipOutput : Closeable {
    fun write(position: Long, data: ByteArray, offset: Int, length: Int)

    fun read(position: Long, data: ByteArray, offset: Int, length: Int): Int

    fun flush()
}

/** ISO 9660 主卷描述符（LBA 16）里的卷标与卷大小。 */
class IsoVolume(val volumeId: String?, val volumeSpaceSize: Long?) {
    companion object {
        fun parse(sector: ByteArray): IsoVolume? {
            if (sector.size < 2048) return null
            if (sector[0].toInt() != 1 || String(sector, 1, 5, Charsets.US_ASCII) != "CD001") return null
            // 卷标按 UTF-8 / Shift-JIS 猜编码（N1.6 真机：UTF-8 日文卷标被当成 ASCII，变成 9 个下划线）
            val id = DiscText.field(sector, 40, 32).ifEmpty { null }
            val size = (0 until 4).fold(0L) { acc, k -> acc or ((sector[80 + k].toLong() and 0xFF) shl (8 * k)) }
            return IsoVolume(id, size.takeIf { it > 0 })
        }
    }
}

data class RipOptions(
    /** 第 1 遍每条 READ(10) 读多少扇区（16 / 32 / 64 / 128）。 */
    val blockSectors: Int = 32,
    /** 第 2 遍每个扇区最多读几次。 */
    val retries: Int = 3,
    /** 可重写盘（DVD+RW、DVD-RW 限制性覆写、DVD-RAM、BD-RE）只读到文件系统结尾。 */
    val trimRewritable: Boolean = true,
)

data class RipProgress(
    val pass: Int,
    /** 已处理的扇区（读成功、补零、确认坏都算）。 */
    val doneSectors: Long,
    val totalSectors: Long,
    val badSectors: Int,
    val pendingSectors: Long,
    val bytesPerSecond: Double,
    val currentLba: Long,
    val etaSeconds: Long?,
    val phase: String,
)

class RipResult(
    val status: Status,
    val message: String?,
    val badSectors: List<Long>,
    val runOutSectors: List<Long>,
    val recoveredSectors: Int,
    val readSectors: Long,
    val elapsedMs: Long,
    val md5: String?,
    val sha1: String?,
    val crc32: String?,
    val volume: IsoVolume?,
    val mapfile: String,
) {
    enum class Status(val label: String) {
        COMPLETED("完成"),
        COMPLETED_WITH_ERRORS("完成，但有读不出的扇区（已补零）"),
        CANCELLED("已取消（镜像不完整）"),
        MEDIUM_REMOVED("盘片被取出（镜像不完整）"),
        DRIVE_LOST("光驱断开（镜像不完整）"),
        PROTECTED("遇到需要认证的加密扇区，已停止"),
        OUTPUT_ERROR("写入失败（镜像不完整）"),
    }

    val complete: Boolean get() = status == Status.COMPLETED || status == Status.COMPLETED_WITH_ERRORS
}

/**
 * 读成 ISO（思路同 ddrescue 的两遍）：
 * - 第 1 遍：按块（默认 32 扇区）顺序读，读不出的块先补零、记下来，继续往后读，不在坏区上耗时间；
 * - 第 2 遍：对记下的块逐扇区重读（每扇区最多 [RipOptions.retries] 次），读出来就写回原位置，仍然不行就保持补零并记为坏扇区。
 * 校验值（MD5 / SHA-1 / CRC32）在第 1 遍顺序写入时计算；第 2 遍有扇区被补读回来时，最后把整个镜像读回来重算。
 * 盘片被取出、光驱断开、用户取消都会马上停下并给出结果（镜像标记为不完整，mapfile 记录哪些没读）。
 */
class RipEngine(
    private val exec: suspend (ScsiCommand) -> ScsiResult?,
    private val output: RipOutput,
    private val log: DiskLog,
    private val options: RipOptions = RipOptions(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private sealed interface Read {
        data object Ok : Read
        class Failed(val detail: String) : Read
        class Stop(val status: RipResult.Status, val detail: String) : Read
    }

    private class Interval(val start: Long, val end: Long, var status: Char)

    private val intervals = mutableListOf<Interval>()
    private val bad = sortedSetOf<Long>()
    private val runOut = sortedSetOf<Long>()
    private var readSectors = 0L
    private var recovered = 0
    private var volume: IsoVolume? = null
    private val speedSamples = ArrayDeque<Pair<Long, Long>>()

    /** 读 LBA 16，取 ISO 9660 卷标（用来给文件起名）；不是 ISO 9660（例如只有 UDF 的 BD）返回 null。 */
    suspend fun probeVolume(): IsoVolume? {
        val buf = ByteArray(RipPlan.SECTOR)
        val r = exec(MmcCommands.read10(16, 1, buf, 0)) ?: return null
        return if (r is ScsiResult.Good && r.transferred >= RipPlan.SECTOR) IsoVolume.parse(buf) else null
    }

    suspend fun run(plan: RipPlan, cancelRequested: () -> Boolean, onProgress: (RipProgress) -> Unit): RipResult {
        val t0 = clock()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val crc = CRC32()
        fun hash(b: ByteArray, off: Int, len: Int) {
            md5.update(b, off, len)
            sha1.update(b, off, len)
            crc.update(b, off, len)
        }
        val block = options.blockSectors.coerceIn(1, 128)
        val buf = ByteArray(block * RipPlan.SECTOR)
        val zeros = ByteArray(ZERO_CHUNK)
        val pending = mutableListOf<Interval>()
        var pendingLeft = 0L
        var done = 0L
        var stop: Read.Stop? = null
        var lastProgress = 0L
        log.i(TAG, "开始读成 ISO", "盘片" to plan.profileName, "扇区" to plan.totalSectors, "块大小" to block, "重试" to options.retries)

        fun progress(pass: Int, lba: Long, phase: String, force: Boolean = false) {
            val now = clock()
            if (!force && now - lastProgress < PROGRESS_INTERVAL_MS) return
            lastProgress = now
            speedSamples.addLast(now to readSectors)
            while (speedSamples.size > 2 && now - speedSamples.first().first > SPEED_WINDOW_MS) speedSamples.removeFirst()
            val (t1, s1) = speedSamples.first()
            val bps = if (now > t1) (readSectors - s1) * RipPlan.SECTOR * 1000.0 / (now - t1) else 0.0
            val remaining = plan.totalSectors - done + pendingLeft
            val eta = if (bps > 1) (remaining * RipPlan.SECTOR / bps).toLong() else null
            onProgress(RipProgress(pass, done, plan.totalSectors, bad.size, pendingLeft, bps, lba, eta, phase))
        }

        try {
            // ---------------- 第 1 遍 ----------------
            loop@ for (area in plan.areas) {
                if (area.kind == RipArea.Kind.GAP) {
                    var pos = area.start
                    while (pos < area.end) {
                        if (cancelRequested()) {
                            stop = Read.Stop(RipResult.Status.CANCELLED, "用户取消")
                            break@loop
                        }
                        val n = minOf((area.end - pos) * RipPlan.SECTOR, zeros.size.toLong()).toInt()
                        output.write(pos * RipPlan.SECTOR, zeros, 0, n)
                        hash(zeros, 0, n)
                        pos += n / RipPlan.SECTOR
                        done += n / RipPlan.SECTOR
                        progress(1, pos, "补零：${area.note}")
                    }
                    intervals += Interval(area.start, area.end, '+')
                    continue
                }
                var lba = area.start
                while (lba < area.end) {
                    if (cancelRequested()) {
                        stop = Read.Stop(RipResult.Status.CANCELLED, "用户取消")
                        break@loop
                    }
                    val n = minOf(block.toLong(), area.end - lba).toInt()
                    val bytes = n * RipPlan.SECTOR
                    when (val r = readWithTransportRetry(lba, n, buf)) {
                        Read.Ok -> {
                            if (lba <= 16 && lba + n > 16 && volume == null) {
                                volume = IsoVolume.parse(buf.copyOfRange((16 - lba).toInt() * RipPlan.SECTOR, (17 - lba).toInt() * RipPlan.SECTOR))
                            }
                            output.write(lba * RipPlan.SECTOR, buf, 0, bytes)
                            hash(buf, 0, bytes)
                            readSectors += n
                            intervals += Interval(lba, lba + n, '+')
                        }
                        is Read.Failed -> {
                            // 先补零占位，第 2 遍再逐扇区重读
                            buf.fill(0, 0, bytes)
                            output.write(lba * RipPlan.SECTOR, buf, 0, bytes)
                            hash(buf, 0, bytes)
                            val iv = Interval(lba, lba + n, '*')
                            intervals += iv
                            pending += iv
                            pendingLeft += n
                            log.w(TAG, "第 1 遍读不出，留到第 2 遍", "LBA" to "$lba–${lba + n - 1}", "错误" to r.detail)
                        }
                        is Read.Stop -> {
                            stop = r
                            break@loop
                        }
                    }
                    lba += n
                    done += n
                    progress(1, lba, area.note)
                }
            }
            if (stop != null) {
                val resumeAt = intervals.lastOrNull()?.end ?: 0L
                if (resumeAt < plan.totalSectors) intervals += Interval(resumeAt, plan.totalSectors, '?')
            }

            // ---------------- 第 2 遍 ----------------
            if (stop == null && pending.isNotEmpty()) {
                log.i(TAG, "第 2 遍：逐扇区重读 $pendingLeft 个扇区")
                val one = ByteArray(RipPlan.SECTOR)
                outer@ for (iv in pending.toList()) {
                    val results = CharArray((iv.end - iv.start).toInt()) { '*' }
                    for (lba in iv.start until iv.end) {
                        if (cancelRequested()) {
                            stop = Read.Stop(RipResult.Status.CANCELLED, "用户取消")
                        }
                        if (stop == null) {
                            var ok = false
                            var tries = 0
                            var last = ""
                            while (!ok && tries < options.retries.coerceAtLeast(1)) {
                                tries++
                                when (val r = readWithTransportRetry(lba, 1, one)) {
                                    Read.Ok -> ok = true
                                    is Read.Failed -> last = r.detail
                                    is Read.Stop -> {
                                        stop = r
                                        break
                                    }
                                }
                            }
                            if (stop == null) {
                                results[(lba - iv.start).toInt()] = when {
                                    ok -> {
                                        output.write(lba * RipPlan.SECTOR, one, 0, RipPlan.SECTOR)
                                        readSectors++
                                        recovered++
                                        '+'
                                    }
                                    plan.isRunOut(lba) -> {
                                        runOut += lba
                                        '+'
                                    }
                                    else -> {
                                        bad += lba
                                        log.w(TAG, "坏扇区（已补零）", "LBA" to lba, "重试" to tries, "错误" to last)
                                        '-'
                                    }
                                }
                                pendingLeft--
                            }
                        }
                        progress(2, lba, "第 2 遍：逐扇区重读")
                    }
                    replace(iv, results)
                    pending.remove(iv)
                    if (stop != null) break@outer
                }
            }
        } catch (e: IOException) {
            val pos = intervals.lastOrNull()?.end ?: 0L
            val fat32 = pos * RipPlan.SECTOR >= FOUR_GB - RipPlan.SECTOR * 128L
            stop = Read.Stop(
                RipResult.Status.OUTPUT_ERROR,
                "写入失败：${e.message}" + if (fat32) "（正好在 4 GB 附近：目标很可能是 FAT32 格式，单个文件最大 4 GB）" else "",
            )
            if (pos < plan.totalSectors) intervals += Interval(pos, plan.totalSectors, '?')
        }
        runCatching { output.flush() }

        // ---------------- 校验值 ----------------
        val stopNow = stop
        var hashes: Triple<String, String, String>? = null
        if (stopNow == null) {
            hashes = if (recovered == 0) {
                Triple(hex(md5.digest()), hex(sha1.digest()), "%08x".format(crc.value))
            } else {
                log.i(TAG, "第 2 遍补读回 $recovered 个扇区，把镜像读回来重算校验值")
                runCatching { rehash(plan) { pos -> progress(3, pos, "重新计算校验值") } }.getOrNull()
            }
        }
        val status = when {
            stopNow != null -> stopNow.status
            bad.isNotEmpty() -> RipResult.Status.COMPLETED_WITH_ERRORS
            else -> RipResult.Status.COMPLETED
        }
        val elapsed = clock() - t0
        progress(if (pending.isEmpty()) 2 else 1, plan.totalSectors, status.label, force = true)
        log.i(
            TAG, "读成 ISO 结束：${status.label}",
            "读出扇区" to readSectors, "坏扇区" to bad.size, "补读回来" to recovered, "run-out" to runOut.size,
            "用时 s" to elapsed / 1000, "MD5" to hashes?.first, "原因" to stopNow?.detail,
        )
        return RipResult(
            status, stopNow?.detail, bad.toList(), runOut.toList(), recovered, readSectors, elapsed,
            hashes?.first, hashes?.second, hashes?.third, volume, mapfile(plan, status),
        )
    }

    /** 读一块；传输层出错（BOT 已复位恢复）时整块再读一次。 */
    private suspend fun readWithTransportRetry(lba: Long, count: Int, buf: ByteArray): Read {
        var notReadyWaits = 0
        var transportRetries = 0
        while (true) {
            val r = exec(MmcCommands.read10(lba, count, buf, 0)) ?: return Read.Stop(RipResult.Status.DRIVE_LOST, "光驱已断开或不可用")
            when (r) {
                is ScsiResult.Good -> return if (r.transferred >= count * RipPlan.SECTOR) Read.Ok else Read.Failed("只传回 ${r.transferred} 字节")
                is ScsiResult.CheckCondition -> {
                    val s = r.sense ?: return Read.Failed("CHECK CONDITION（无 sense）")
                    when {
                        s.senseKey == SenseKey.NOT_READY && s.asc == 0x3A -> return Read.Stop(RipResult.Status.MEDIUM_REMOVED, "盘片被取出（${s.code()}）")
                        s.senseKey == SenseKey.NOT_READY && s.asc == 0x04 && notReadyWaits < 20 -> {
                            notReadyWaits++
                            sleep(500)
                        }
                        s.senseKey == SenseKey.ILLEGAL_REQUEST && s.asc == 0x6F ->
                            return Read.Stop(RipResult.Status.PROTECTED, "光驱拒绝读取加密扇区（${s.describe()}）：需要阶段 3 的认证")
                        else -> return Read.Failed(s.describe())
                    }
                }
                is ScsiResult.TransportError -> {
                    if (transportRetries >= 1) return Read.Failed(r.toString())
                    transportRetries++
                }
                is ScsiResult.Refused -> return Read.Stop(RipResult.Status.DRIVE_LOST, "命令被只读守卫拒绝：${r.reason}")
            }
        }
    }

    private fun replace(iv: Interval, results: CharArray) {
        val idx = intervals.indexOf(iv)
        if (idx < 0) return
        val parts = mutableListOf<Interval>()
        var runStart = 0
        for (i in 1..results.size) {
            if (i == results.size || results[i] != results[runStart]) {
                parts += Interval(iv.start + runStart, iv.start + i, results[runStart])
                runStart = i
            }
        }
        intervals.removeAt(idx)
        intervals.addAll(idx, parts)
    }

    private suspend fun rehash(plan: RipPlan, onPos: (Long) -> Unit): Triple<String, String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val crc = CRC32()
        val buf = ByteArray(1 shl 20)
        var pos = 0L
        val total = plan.bytes
        while (pos < total) {
            val n = output.read(pos, buf, 0, minOf(buf.size.toLong(), total - pos).toInt())
            if (n <= 0) throw IOException("读回镜像失败（位置 $pos）")
            md5.update(buf, 0, n)
            sha1.update(buf, 0, n)
            crc.update(buf, 0, n)
            pos += n
            onPos(pos / RipPlan.SECTOR)
        }
        return Triple(hex(md5.digest()), hex(sha1.digest()), "%08x".format(crc.value))
    }

    /** GNU ddrescue 格式的 mapfile：位置、长度（字节，十六进制）和状态（+ 读到，- 坏扇区，* 没来得及重读，? 没读）。 */
    private fun mapfile(plan: RipPlan, status: RipResult.Status): String {
        val merged = mutableListOf<Interval>()
        for (iv in intervals.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.status == iv.status && last.end == iv.start) {
                merged[merged.size - 1] = Interval(last.start, iv.end, iv.status)
            } else {
                merged += Interval(iv.start, iv.end, iv.status)
            }
        }
        val sb = StringBuilder()
        sb.append("# Mapfile. Created by DiskAndroid (GNU ddrescue format)\n")
        sb.append("# Disc: ${plan.profileName}, ${plan.totalSectors} sectors of ${RipPlan.SECTOR} bytes; result: ${status.name}\n")
        sb.append("# current_pos  current_status  current_pass\n")
        val pos = merged.firstOrNull { it.status != '+' }?.start ?: plan.totalSectors
        sb.append("0x%08X     %c               1\n".format(pos * RipPlan.SECTOR, if (status == RipResult.Status.COMPLETED || status == RipResult.Status.COMPLETED_WITH_ERRORS) '+' else '?'))
        sb.append("#      pos        size  status\n")
        for (iv in merged) {
            sb.append("0x%08X  0x%08X  %c\n".format(iv.start * RipPlan.SECTOR, (iv.end - iv.start) * RipPlan.SECTOR, iv.status))
        }
        return sb.toString()
    }

    companion object {
        const val TAG = "RIP"
        private const val ZERO_CHUNK = 1 shl 20
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val SPEED_WINDOW_MS = 5_000L
        private const val FOUR_GB = 4L * 1024 * 1024 * 1024

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        /** 速度换算成倍速：CD 1x = 75 × 2048 字节/秒，DVD 1x = 1 385 000，BD 1x = 4 495 500。 */
        fun speedFactor(bytesPerSecond: Double, family: MmcNames.DiscFamily): Double = bytesPerSecond / when (family) {
            MmcNames.DiscFamily.CD -> 153_600.0
            MmcNames.DiscFamily.BD -> 4_495_500.0
            else -> 1_385_000.0
        }
    }
}
