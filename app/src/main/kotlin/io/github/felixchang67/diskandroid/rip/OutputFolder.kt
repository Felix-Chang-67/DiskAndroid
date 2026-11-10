package io.github.felixchang67.diskandroid.rip

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.felixchang67.diskandroid.core.log.DiskLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 保存位置（SAF 目录，持久授权）：读成 ISO 和从盘上复制文件共用。 */
class OutputFolder(private val app: Application, private val log: DiskLog) {
    private val prefs = app.getSharedPreferences("rip", Context.MODE_PRIVATE)
    private val _uri = MutableStateFlow(prefs.getString("folder", null)?.toUri())
    val uri: StateFlow<Uri?> = _uri.asStateFlow()

    fun set(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { log.w(TAG, "无法保存目录授权：${it.message}") }
        _uri.value = uri
        prefs.edit { putString("folder", uri.toString()) }
        log.i(TAG, "保存位置：${label(uri)}")
    }

    /** 目录的显示名（查不到就用 URI 里的路径）。 */
    fun label(uri: Uri): String = runCatching {
        app.contentResolver.query(documentUri(uri), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "存储根目录" }

    /** 可用空间（只对本机存储 / SD 卡能算出来），算不出返回 null。 */
    fun freeBytes(uri: Uri): Long? = runCatching {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
        val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
        StatFs(root.path).availableBytes
    }.getOrNull()

    /** 目录树根对应的文档 URI（在它下面建文件 / 子目录）。 */
    fun documentUri(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** 文档的实际文件名（重名时提供者会自动改名，例如加 " (1)"）。 */
    fun displayName(doc: Uri): String? = runCatching {
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val TAG = "RIP"

        /** 用 octet-stream：文件管理类的提供者不会因为类型给文件名乱加扩展名。 */
        const val MIME = "application/octet-stream"
    }
}
