package io.github.felixchang67.diskandroid.video

import android.app.Application
import android.net.Uri
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.player.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 解密组件（阶段 3）：不随 App 提供，由用户导入到 App 的私有目录，只用于边读边播。
 * - DVD：libdvdcss（DiskAndroid 补丁版，导出 dvdcss_set_host）
 * - 蓝光：libaacs（DiskAndroid 补丁版，导出 aacs_set_host）+ 用户自己准备的 KEYDB.cfg
 *
 * 导入时检查：.so 必须是 64 位 ARM 的共享库、能加载、有对应的接口函数；KEYDB.cfg 必须是文本格式的密钥库。
 * 文件保存后设为只读（Android 14 起动态加载的代码文件必须只读）。
 */
class DecryptComponents(private val app: Application, private val log: DiskLog) {
    enum class Kind(val label: String, val detail: String, val symbol: String?) {
        CSS("DVD 解密组件", "libdvdcss（DiskAndroid 补丁版）", "dvdcss_set_host"),
        AACS("蓝光解密组件", "libaacs（DiskAndroid 补丁版）", "aacs_set_host"),
        KEYDB("AACS 密钥库", "KEYDB.cfg（需要你自己准备）", null),
    }

    data class Item(val kind: Kind, val file: File, val size: Long, val sha256: String, val modifiedMs: Long)

    private val root = File(app.filesDir, "plugins")

    /** libaacs 在 $XDG_CONFIG_HOME/aacs/KEYDB.cfg 找密钥库。 */
    val aacsConfigHome: File get() = File(app.filesDir, "aacs-config")

    /** libdvdcss 的标题密钥缓存（DVDCSS_CACHE）。 */
    val cssCacheDir: File get() = File(app.cacheDir, "dvdcss").apply { mkdirs() }

    private fun dirFor(kind: Kind): File = when (kind) {
        Kind.KEYDB -> File(aacsConfigHome, "aacs")
        else -> File(root, kind.name.lowercase())
    }

    private val _items = MutableStateFlow(scan())
    val items: StateFlow<Map<Kind, Item>> = _items.asStateFlow()

    private val _busy = MutableStateFlow<Kind?>(null)
    val busy: StateFlow<Kind?> = _busy.asStateFlow()

    fun file(kind: Kind): File? = _items.value[kind]?.file?.takeIf { it.exists() }

    private fun scan(): Map<Kind, Item> = Kind.entries.mapNotNull { k ->
        val f = dirFor(k).listFiles()?.filter { it.isFile && (k == Kind.KEYDB && it.name == KEYDB_NAME || k != Kind.KEYDB && it.name.endsWith(".so")) }
            ?.maxByOrNull { it.lastModified() } ?: return@mapNotNull null
        k to Item(k, f, f.length(), runCatching { sha256(f) }.getOrDefault("?"), f.lastModified())
    }.toMap()

    fun describe(): List<String> = Kind.entries.map { k ->
        val it = _items.value[k]
        "${k.label}：" + if (it == null) "未导入" else "已导入（${it.size} 字节，SHA-256 ${it.sha256.take(16)}…）"
    }

    /** 导入（复制进私有目录、检查、设为只读）。返回给用户看的错误，null = 成功。 */
    suspend fun import(kind: Kind, uri: Uri): String? = withContext(Dispatchers.IO) {
        _busy.value = kind
        try {
            importInternal(kind, uri)
        } finally {
            _busy.value = null
        }
    }

    private fun importInternal(kind: Kind, uri: Uri): String? {
        root.mkdirs()
        val tmp = File(root, "import-${System.nanoTime()}.tmp")
        try {
            val limit = if (kind == Kind.KEYDB) MAX_KEYDB_BYTES else MAX_SO_BYTES
            val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) return "文件太大（超过 ${limit / 1_048_576} MB），不像是${kind.label}"
                        out.write(buf, 0, n)
                    }
                    total
                }
            } ?: return "读不了这个文件"
            if (copied == 0L) return "文件是空的"
            tmp.setReadOnly()
            val problem = if (kind == Kind.KEYDB) checkKeydb(tmp) else checkElf(tmp) ?: NativeBridge.nativeCheckComponent(tmp.path, kind.symbol!!)
            if (problem != null) {
                log.w(TAG, "导入${kind.label}失败：$problem")
                return problem
            }
            val hash = sha256(tmp)
            val dir = dirFor(kind).apply { mkdirs() }
            val dest = File(dir, if (kind == Kind.KEYDB) KEYDB_NAME else "lib${kind.name.lowercase()}-${hash.take(12)}.so")
            // 同一个组件文件名带内容哈希：重新导入新版本时换一个路径，不用重启 App 就能加载新的
            dir.listFiles()?.forEach { old ->
                if (old != dest) {
                    old.setWritable(true)
                    old.delete()
                }
            }
            if (dest.exists()) {
                dest.setWritable(true)
                dest.delete()
            }
            if (!tmp.renameTo(dest)) return "保存失败"
            dest.setReadOnly()
            val item = Item(kind, dest, dest.length(), hash, dest.lastModified())
            _items.value = _items.value + (kind to item)
            log.i(TAG, "已导入${kind.label}", "文件" to dest.name, "字节" to item.size, "SHA-256" to hash)
            return null
        } catch (e: Exception) {
            log.e(TAG, "导入${kind.label}出错", throwable = e)
            return "导入出错：${e.message}"
        } finally {
            if (tmp.exists()) {
                tmp.setWritable(true)
                tmp.delete()
            }
        }
    }

    fun remove(kind: Kind) {
        dirFor(kind).listFiles()?.forEach {
            it.setWritable(true)
            it.delete()
        }
        _items.value = _items.value - kind
        if (kind == Kind.CSS && NativeBridge.isAvailable) runCatching { NativeBridge.nativeDetachCss() }
        log.i(TAG, "已删除${kind.label}")
    }

    /** 64 位 ARM（AArch64）的 ELF 共享库。 */
    private fun checkElf(f: File): String? {
        val h = ByteArray(20)
        RandomAccessFile(f, "r").use { if (it.read(h) != h.size) return "文件太短，不是共享库（.so）" }
        if (h[0] != 0x7F.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) {
            return "不是共享库（.so）文件"
        }
        if (h[4].toInt() != 2) return "是 32 位的库，这台平板要 64 位（arm64-v8a）的"
        if (h[5].toInt() != 1) return "字节序不对（不是小端）"
        val type = (h[16].toInt() and 0xFF) or ((h[17].toInt() and 0xFF) shl 8)
        val machine = (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
        if (type != 3) return "不是共享库（ELF 类型 $type）"
        if (machine != 183) return "不是给 ARM64（arm64-v8a）编的库（机器类型 $machine）"
        return null
    }

    /** KEYDB.cfg：文本，含 "| PK |"（处理密钥）、"| HC |"（主机证书）或 "0x… = …" 的光盘条目。 */
    private fun checkKeydb(f: File): String? {
        val head = f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            val n = input.read(buf).coerceAtLeast(0)
            buf.copyOf(n)
        }
        val printable = head.count { b -> val c = b.toInt() and 0xFF; c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 128 }
        if (head.isEmpty() || printable < head.size * 0.95) return "不是文本文件，不像是 KEYDB.cfg"
        val text = String(head, Charsets.UTF_8)
        val looksRight = text.contains("| PK |") || text.contains("| HC |") || text.contains("| DK |") || Regex("0x[0-9A-Fa-f]{40}\\s*=").containsMatchIn(text)
        return if (looksRight) null else "没找到 KEYDB.cfg 的条目（| PK |、| HC |、0x… = …），文件选对了吗？"
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "DECRYPT"
        const val KEYDB_NAME = "KEYDB.cfg"
        private const val MAX_SO_BYTES = 32L * 1024 * 1024
        private const val MAX_KEYDB_BYTES = 512L * 1024 * 1024
    }
}
