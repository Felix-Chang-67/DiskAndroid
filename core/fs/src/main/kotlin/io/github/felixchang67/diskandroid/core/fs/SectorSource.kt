package io.github.felixchang67.diskandroid.core.fs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** 数据扇区大小（ISO 9660 / UDF 在光盘上都用 2048 字节的逻辑块）。 */
const val SECTOR_SIZE = 2048

/** 按 2048 字节扇区读盘的数据源（真机上是 READ(10)，测试里是内存镜像）。 */
interface SectorSource {
    /** 扇区总数（READ CAPACITY），不知道为 null。 */
    val sectorCount: Long?

    /** 读 [count] 个扇区到 dst[offset ...]；读不出抛 [SectorReadException]。 */
    suspend fun read(lba: Long, count: Int, dst: ByteArray, offset: Int = 0)
}

/**
 * 读扇区失败。
 * @param fatal 盘片已取出、光驱已断开之类，继续读没有意义
 * @param protected 光驱拒绝读取加密扇区（05/6F/xx，需要认证）
 */
class SectorReadException(
    val lba: Long,
    val count: Int,
    message: String,
    val fatal: Boolean = false,
    val protected: Boolean = false,
) : IOException(message)

/** 文件系统结构有问题（描述符缺失、校验和不对、地址越界等）。 */
class DiscFsException(message: String) : IOException(message)

/**
 * 带缓存的扇区源：读文件系统的元数据（卷描述符、目录、文件项）时一次预读 [readAhead] 个扇区并缓存，
 * 光盘寻道很慢，逐个扇区读一个目录下几十个文件项会很慢。文件内容走 [read]，不进缓存。
 */
class CachedSectorSource(
    private val inner: SectorSource,
    private val capacity: Int = 2048,
    private val readAhead: Int = 16,
) : SectorSource {
    private val cache = object : LinkedHashMap<Long, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > capacity
    }
    private val mutex = Mutex()

    var commands = 0L
        private set
    var hits = 0L
        private set

    override val sectorCount: Long? get() = inner.sectorCount

    /** 读一个扇区（经缓存）。返回的数组是缓存里的，调用方不要修改。 */
    suspend fun sector(lba: Long): ByteArray = mutex.withLock {
        cache[lba]?.let {
            hits++
            return@withLock it
        }
        val end = sectorCount
        if (lba < 0 || (end != null && lba >= end)) throw SectorReadException(lba, 1, "扇区 $lba 超出盘片范围（共 ${end ?: "?"} 扇区）")
        var n = if (end != null) minOf(readAhead.toLong(), end - lba).toInt() else readAhead
        n = n.coerceAtLeast(1)
        val buf = ByteArray(n * SECTOR_SIZE)
        try {
            commands++
            inner.read(lba, n, buf, 0)
        } catch (e: SectorReadException) {
            // 预读范围里可能有读不出的扇区（多区段盘的区段间隙、混合模式盘的音轨），退回只读这一个
            if (n == 1 || e.fatal) throw e
            n = 1
            commands++
            inner.read(lba, 1, buf, 0)
        }
        for (i in 0 until n) cache[lba + i] = buf.copyOfRange(i * SECTOR_SIZE, (i + 1) * SECTOR_SIZE)
        cache.getValue(lba)
    }

    /** 读一个扇区；读不出返回 null（探测用）。盘片取出等致命错误照样抛出。 */
    suspend fun sectorOrNull(lba: Long): ByteArray? = try {
        sector(lba)
    } catch (e: SectorReadException) {
        if (e.fatal) throw e
        null
    }

    /** 读一段字节（经缓存，给目录数据之类的元数据用）。 */
    suspend fun bytes(lba: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        var s = lba
        while (pos < length) {
            val n = minOf(SECTOR_SIZE, length - pos)
            System.arraycopy(sector(s), 0, out, pos, n)
            pos += n
            s++
        }
        return out
    }

    override suspend fun read(lba: Long, count: Int, dst: ByteArray, offset: Int) {
        commands++
        inner.read(lba, count, dst, offset)
    }
}

/** 内存里的镜像（测试、以及将来浏览已经读出来的 ISO 用）。 */
class ByteArraySectorSource(private val image: ByteArray) : SectorSource {
    override val sectorCount: Long = (image.size / SECTOR_SIZE).toLong()
    var reads = 0
        private set

    override suspend fun read(lba: Long, count: Int, dst: ByteArray, offset: Int) {
        reads++
        if (lba < 0 || lba + count > sectorCount) throw SectorReadException(lba, count, "LBA 超出范围")
        System.arraycopy(image, (lba * SECTOR_SIZE).toInt(), dst, offset, count * SECTOR_SIZE)
    }
}
