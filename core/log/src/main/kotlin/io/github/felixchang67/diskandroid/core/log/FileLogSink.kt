package io.github.felixchang67.diskandroid.core.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * 会话日志文件：每次进程启动一个会话，同时写 `<session>.log`（文本）和 `<session>.jsonl`（JSON 行）。
 *
 * - WARN 及以上立即 flush；其余由后台线程每秒 flush 一次。崩溃处理器会调用 [flush]。
 * - 单个文件超过 [maxBytesPerFile] 时滚动到 `<session>-part2.*`。
 * - 启动时只保留最近 [keepSessions] 个会话的文件。
 */
class FileLogSink(
    val dir: File,
    val sessionId: String,
    private val maxBytesPerFile: Long = 16L * 1024 * 1024,
    private val keepSessions: Int = 20,
) : LogSink {
    private val lock = Any()
    private var part = 1
    private var textWriter: Writer? = null
    private var jsonWriter: Writer? = null
    private var bytesInPart = 0L

    @Volatile
    private var dirty = false

    @Volatile
    private var closed = false

    init {
        dir.mkdirs()
        pruneOldSessions()
        openPart()
        Thread({
            while (!closed) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (dirty) flush()
            }
        }, "log-flusher").apply {
            isDaemon = true
            start()
        }
    }

    /** 本会话已经写出的所有文件（按顺序）。 */
    fun sessionFiles(): List<File> = synchronized(lock) {
        (1..part).flatMap { p -> listOf(File(dir, fileName(p, "log")), File(dir, fileName(p, "jsonl"))) }
            .filter { it.exists() }
    }

    fun textFiles(): List<File> = sessionFiles().filter { it.name.endsWith(".log") }

    override fun write(record: LogRecord) {
        val text = LogFormat.text(record)
        val json = LogFormat.json(record)
        synchronized(lock) {
            val tw = textWriter ?: return
            tw.write(text)
            tw.write("\n")
            jsonWriter?.let {
                it.write(json)
                it.write("\n")
            }
            bytesInPart += text.length + json.length + 2
            dirty = true
            if (record.level >= LogLevel.WARN) flushLocked()
            if (bytesInPart > maxBytesPerFile) {
                closeLocked()
                part++
                bytesInPart = 0
                openPart()
            }
        }
    }

    override fun flush() {
        synchronized(lock) { flushLocked() }
    }

    override fun close() {
        closed = true
        synchronized(lock) { closeLocked() }
    }

    private fun fileName(p: Int, ext: String) = if (p == 1) "$sessionId.$ext" else "$sessionId-part$p.$ext"

    private fun openPart() {
        textWriter = openWriter(File(dir, fileName(part, "log")))
        jsonWriter = openWriter(File(dir, fileName(part, "jsonl")))
    }

    private fun openWriter(file: File): Writer? = runCatching {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 32 * 1024)
    }.getOrNull()

    private fun flushLocked() {
        runCatching { textWriter?.flush() }
        runCatching { jsonWriter?.flush() }
        dirty = false
    }

    private fun closeLocked() {
        flushLocked()
        runCatching { textWriter?.close() }
        runCatching { jsonWriter?.close() }
        textWriter = null
        jsonWriter = null
    }

    private fun pruneOldSessions() {
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".log") || f.name.endsWith(".jsonl")) }
            ?: return
        val sessions = files.groupBy { it.name.substringBefore('.').substringBefore("-part") }
        val ordered = sessions.entries.sortedByDescending { (_, fs) -> fs.maxOf { it.lastModified() } }
        ordered.drop(keepSessions).forEach { (_, fs) -> fs.forEach { it.delete() } }
    }
}
