package io.github.felixchang67.diskandroid.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.log.LogTags

/**
 * 启动时把系统记录的"上次进程是怎么退出的"写进日志（崩溃、ANR、被系统杀掉……）。
 * 没有电脑看 logcat 时，这是排查闪退的主要手段。
 */
object ExitReasons {
    private const val PREFS = "diag"
    private const val KEY_LAST = "last_exit_timestamp"
    private const val MAX_TRACE_CHARS = 64 * 1024

    fun logRecent(context: Context, log: DiskLog) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST, 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }
            .onFailure { log.w(LogTags.APP, "读取历史退出原因失败", throwable = it) }
            .getOrNull() ?: return
        var newest = lastSeen
        for (info in infos.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }) {
            val serious = info.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            )
            log.log(
                if (serious) LogLevel.WARN else LogLevel.INFO,
                LogTags.APP,
                "上次进程退出：${reasonName(info.reason)}",
                listOf(
                    "时间" to LogFormat.isoTime(info.timestamp),
                    "描述" to (info.description ?: ""),
                    "状态码" to info.status,
                    "重要性" to info.importance,
                    "PSS_KB" to info.pss,
                ),
            )
            if (info.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    info.traceInputStream?.bufferedReader()?.use { reader ->
                        val buf = CharArray(MAX_TRACE_CHARS)
                        val n = reader.read(buf)
                        if (n > 0) log.w(LogTags.APP, "ANR 堆栈（前 $n 字符）：\n" + String(buf, 0, n))
                    }
                }
            }
            newest = maxOf(newest, info.timestamp)
        }
        prefs.edit { putLong(KEY_LAST, newest) }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号终止"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀"
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生代码崩溃"
        ApplicationExitInfo.REASON_ANR -> "无响应 (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户结束"
        ApplicationExitInfo.REASON_USER_STOPPED -> "用户强行停止"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出"
        ApplicationExitInfo.REASON_OTHER -> "系统其他原因"
        // 以下常量在 API 33/34 才有，这里直接用数值，避免版本检查
        14 -> "进程被冻结"
        15 -> "包状态变化"
        16 -> "App 被更新"
        else -> "未知（$reason）"
    }
}
