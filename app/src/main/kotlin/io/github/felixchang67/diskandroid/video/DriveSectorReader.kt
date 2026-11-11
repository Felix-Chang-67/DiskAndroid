package io.github.felixchang67.diskandroid.video

import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.fs.SECTOR_SIZE
import io.github.felixchang67.diskandroid.core.fs.ScsiSectorSource
import io.github.felixchang67.diskandroid.core.fs.SectorReadException
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.player.SectorReader
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 原生播放器的读盘回调：在原生导航线程上同步读（READ(10)，经光驱会话的命令线程，与状态轮询串行）。
 * 盘片取出、光驱断开、光驱拒绝读加密扇区、播放器正在关闭时返回 [SectorReader.FATAL]，让原生层停下来，不再逐扇区重试。
 */
class DriveSectorReader(session: DriveSession, sectors: Long?, private val log: DiskLog) : SectorReader {
    private val source = ScsiSectorSource(session::read, sectors)
    private var buffer = ByteArray(0)

    @Volatile
    var cancelled = false

    /** 第一次致命错误的原因（给界面看）。 */
    @Volatile
    var fatalReason: String? = null
        private set

    /** 光驱拒绝读加密扇区（CSS 盘没认证）。 */
    @Volatile
    var protectedHit = false
        private set

    val commands = AtomicLong()
    val bytes = AtomicLong()
    val errors = AtomicLong()
    val millis = AtomicLong()

    override fun read(offset: Long, dst: ByteBuffer, length: Int): Int {
        if (cancelled) return SectorReader.FATAL
        if (length <= 0 || offset % SECTOR_SIZE != 0L || length % SECTOR_SIZE != 0) return -1
        if (buffer.size < length) buffer = ByteArray(length)
        val t0 = System.nanoTime()
        return try {
            runBlocking { source.read(offset / SECTOR_SIZE, length / SECTOR_SIZE, buffer, 0) }
            dst.clear()
            dst.put(buffer, 0, length)
            commands.incrementAndGet()
            bytes.addAndGet(length.toLong())
            length
        } catch (e: SectorReadException) {
            when {
                e.fatal -> {
                    if (fatalReason == null) {
                        fatalReason = e.message
                        log.w(VideoPlaybackController.TAG, "读盘出错，停止播放：${e.message}")
                    }
                    SectorReader.FATAL
                }
                e.protected -> {
                    if (!protectedHit) {
                        protectedHit = true
                        log.w(VideoPlaybackController.TAG, "光驱拒绝读取加密扇区：${e.message}")
                    }
                    SectorReader.FATAL
                }
                else -> {
                    errors.incrementAndGet()
                    log.w(VideoPlaybackController.TAG, "读盘出错（扇区 ${e.lba}，${e.count} 个）：${e.message}")
                    -1
                }
            }
        } catch (e: Exception) {
            errors.incrementAndGet()
            log.w(VideoPlaybackController.TAG, "读盘异常：${e.message}")
            -1
        } finally {
            millis.addAndGet((System.nanoTime() - t0) / 1_000_000)
        }
    }

    fun summary(): String {
        val mb = bytes.get() / 1_048_576.0
        val ms = millis.get().coerceAtLeast(1)
        return "读盘 %.1f MB（%d 次读取，平均 %.1f MB/s，出错 %d 次）".format(mb, commands.get(), bytes.get() / 1_048_576.0 / (ms / 1000.0), errors.get())
    }
}
