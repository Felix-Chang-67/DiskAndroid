package io.github.felixchang67.diskandroid.core.fs

import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.scsi.SenseKey
import kotlinx.coroutines.delay

/**
 * 用 READ(10) 读盘的扇区源（经光驱会话的命令线程）。
 * 光驱正在就绪（2/04/xx）时等一会儿再读；传输层出错（BOT 已复位恢复）时重读一次；
 * 盘片取出、光驱断开、拒绝读加密扇区（05/6F/xx）都是致命错误。
 */
class ScsiSectorSource(
    private val exec: suspend (ScsiCommand) -> ScsiResult?,
    override val sectorCount: Long?,
    private val maxBlocks: Int = 64,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : SectorSource {
    override suspend fun read(lba: Long, count: Int, dst: ByteArray, offset: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(maxBlocks, count - done)
            readChunk(lba + done, n, dst, offset + done * SECTOR_SIZE)
            done += n
        }
    }

    private suspend fun readChunk(lba: Long, n: Int, dst: ByteArray, off: Int) {
        var notReady = 0
        var transport = 0
        while (true) {
            val r = exec(MmcCommands.read10(lba, n, dst, off)) ?: throw SectorReadException(lba, n, "光驱已断开或不可用", fatal = true)
            when (r) {
                is ScsiResult.Good -> {
                    if (r.transferred >= n * SECTOR_SIZE) return
                    throw SectorReadException(lba, n, "只传回 ${r.transferred} 字节")
                }
                is ScsiResult.CheckCondition -> {
                    val s = r.sense ?: throw SectorReadException(lba, n, "CHECK CONDITION（无 sense）")
                    when {
                        s.senseKey == SenseKey.NOT_READY && s.asc == 0x3A -> throw SectorReadException(lba, n, "盘片被取出", fatal = true)
                        s.senseKey == SenseKey.NOT_READY && s.asc == 0x04 && notReady < 20 -> {
                            notReady++
                            sleep(500)
                        }
                        s.senseKey == SenseKey.ILLEGAL_REQUEST && s.asc == 0x6F ->
                            throw SectorReadException(lba, n, "光驱拒绝读取加密扇区（${s.describe()}）", protected = true)
                        else -> throw SectorReadException(lba, n, s.describe())
                    }
                }
                is ScsiResult.TransportError -> {
                    if (transport++ >= 1) throw SectorReadException(lba, n, r.toString())
                }
                is ScsiResult.Refused -> throw SectorReadException(lba, n, "命令被只读守卫拒绝：${r.reason}", fatal = true)
            }
        }
    }
}
