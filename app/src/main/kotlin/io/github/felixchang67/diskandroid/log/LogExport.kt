package io.github.felixchang67.diskandroid.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出（SAF 保存为 zip）与分享（系统分享面板）日志。 */
object LogExport {
    fun suggestedZipName(): String =
        "DiskAndroid-log-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".zip"

    /** 把会话日志文件和诊断信息打包写到用户选择的位置。返回写入的字节数。 */
    fun writeZip(context: Context, target: Uri, files: List<File>, diagnostics: String): Long {
        var total = 0L
        val out = context.contentResolver.openOutputStream(target) ?: error("无法打开输出位置")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.txt"))
            val diagBytes = diagnostics.toByteArray(Charsets.UTF_8)
            zip.write(diagBytes)
            total += diagBytes.size
            zip.closeEntry()
            for (f in files) {
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { total += it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return total
    }

    /** 把诊断信息 + 当前会话日志合成一个 txt（在 IO 线程调用）。 */
    fun prepareShareFile(context: Context, textLogs: List<File>, diagnostics: String): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "DiskAndroid-log-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".txt")
        file.bufferedWriter().use { w ->
            w.write(diagnostics)
            for (log in textLogs) {
                if (!log.exists()) continue
                w.write("\n===== 会话日志 ${log.name} =====\n")
                log.bufferedReader().use { it.copyTo(w) }
            }
        }
        return file
    }

    /** 通过系统分享面板发出（在主线程调用）。 */
    fun startShare(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DiskAndroid 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
