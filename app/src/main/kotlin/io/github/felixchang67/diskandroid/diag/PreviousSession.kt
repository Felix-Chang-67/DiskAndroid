package io.github.felixchang67.diskandroid.diag

import java.io.File

/**
 * 从上一个会话的 .log 文件里摘出警告和错误（连同缩进的续行）。
 * HyperOS 上划掉后台会强行停止 App，下次打开就是新会话；有了这个，复制诊断信息时仍能看到上次的问题。
 */
object PreviousSession {
    class Issues(val sessionId: String, val lines: List<String>)

    fun extract(dir: File, currentSessionId: String, maxLines: Int = 150): Issues? {
        val logs = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") && !f.name.startsWith(currentSessionId) }
            ?: return null
        val newest = logs.maxByOrNull { it.lastModified() } ?: return null
        val sessionId = newest.name.substringBefore(".log").substringBefore("-part")
        val out = ArrayDeque<String>()
        var inIssue = false
        runCatching {
            newest.useLines { lines ->
                for (line in lines) {
                    // 记录行的格式："HH:mm:ss.SSS L/TAG: ..."，第 13 个字符是级别
                    val isRecord = line.length > 14 && line[2] == ':' && line[5] == ':' && line[8] == '.' && line[12] == ' ' && line[14] == '/'
                    if (isRecord) {
                        inIssue = line[13] == 'W' || line[13] == 'E'
                        if (inIssue) out.addLast(line)
                    } else if (inIssue && line.startsWith("    ")) {
                        out.addLast(line)
                    }
                    while (out.size > maxLines) out.removeFirst()
                }
            }
        }
        return Issues(sessionId, out.toList())
    }
}
