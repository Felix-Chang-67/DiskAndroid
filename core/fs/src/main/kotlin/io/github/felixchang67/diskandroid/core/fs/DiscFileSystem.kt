package io.github.felixchang67.diskandroid.core.fs

/** 盘上的一个文件或目录。 */
class DiscEntry internal constructor(
    val name: String,
    /** 从根目录开始的路径，例如 "/VIDEO_TS/VIDEO_TS.IFO"（根目录是 "/"）。 */
    val path: String,
    val isDirectory: Boolean,
    /** 文件大小（字节）；目录是目录数据本身的大小。 */
    val size: Long,
    val modifiedMillis: Long?,
    val hidden: Boolean,
    internal val handle: Any,
) {
    override fun toString(): String = path

    companion object {
        fun childPath(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"
    }
}

/** 文件内容在盘上的一段：文件内 [fileOffset] 起的 [length] 字节，从扇区 [sector] 起连续存放（null 表示稀疏，读出全零）。 */
class DataRun(val fileOffset: Long, val length: Long, val sector: Long?) {
    override fun toString() = "DataRun(@$fileOffset +$length → ${sector ?: "零"})"
}

/** 只读的盘片文件系统（ISO 9660 / Joliet / Rock Ridge / UDF）。 */
interface DiscFileSystem {
    /** "UDF 2.50"、"ISO 9660 + Joliet" 之类。 */
    val kind: String
    val label: String?
    val root: DiscEntry

    /** 列出目录（不含 . 和 ..），按名字排序，目录在前。 */
    suspend fun list(dir: DiscEntry): List<DiscEntry>

    /** 文件内容在盘上的位置。 */
    suspend fun runs(file: DiscEntry): List<DataRun>

    /** 打开文件按偏移读取。 */
    suspend fun open(file: DiscEntry): DiscFileReader
}

/** 按路径找文件（不分大小写，"/" 分隔）。 */
suspend fun DiscFileSystem.find(path: String): DiscEntry? {
    var cur = root
    for (part in path.split('/').filter { it.isNotEmpty() }) {
        if (!cur.isDirectory) return null
        cur = list(cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
    }
    return cur
}

/** 把一个不大的文件整个读出来（IFO、index.bdmv 之类）。 */
suspend fun DiscFileSystem.readAll(file: DiscEntry, limit: Int = 16 shl 20): ByteArray {
    if (file.size > limit) throw DiscFsException("文件太大（${file.size} 字节）")
    val out = ByteArray(file.size.toInt())
    val reader = open(file)
    var pos = 0
    while (pos < out.size) {
        val n = reader.read(pos.toLong(), out, pos, out.size - pos)
        if (n <= 0) break
        pos += n
    }
    return out
}

/** 按文件内偏移读取：找到偏移所在的 [DataRun]，读需要的整扇区（每条命令最多 [maxSectors] 个），再拷出要的字节。 */
class DiscFileReader(private val src: SectorSource, val size: Long, private val runs: List<DataRun>, private val maxSectors: Int = 64) {
    private val buf = ByteArray(maxSectors * SECTOR_SIZE)

    /** 从 [position] 读最多 [len] 字节，返回实际读到的字节数；到文件末尾返回 -1。读不出抛 [SectorReadException]。 */
    suspend fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        if (len <= 0) return 0
        val run = runAt(position) ?: return zeros(position, dst, off, len)
        val inRun = position - run.fileOffset
        val want = minOf(len.toLong(), run.length - inRun, size - position).toInt()
        val sector = run.sector
        if (sector == null) {
            dst.fill(0, off, off + want)
            return want
        }
        val first = sector + inRun / SECTOR_SIZE
        val skip = (inRun % SECTOR_SIZE).toInt()
        val count = minOf(((skip + want + SECTOR_SIZE - 1) / SECTOR_SIZE).toLong(), maxSectors.toLong()).toInt()
        src.read(first, count, buf, 0)
        val n = minOf(want, count * SECTOR_SIZE - skip)
        System.arraycopy(buf, skip, dst, off, n)
        return n
    }

    /** 文件里没有被任何段覆盖的部分（不应出现，保险起见当作稀疏）。 */
    private fun zeros(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        val next = runs.firstOrNull { it.fileOffset > position }?.fileOffset ?: size
        val n = minOf(len.toLong(), next - position, size - position).toInt()
        dst.fill(0, off, off + n)
        return n
    }

    private fun runAt(position: Long): DataRun? {
        var lo = 0
        var hi = runs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = runs[mid]
            when {
                position < r.fileOffset -> hi = mid - 1
                position >= r.fileOffset + r.length -> lo = mid + 1
                else -> return r
            }
        }
        return null
    }
}

internal fun sortEntries(list: List<DiscEntry>): List<DiscEntry> =
    list.sortedWith(compareBy<DiscEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
