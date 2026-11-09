package io.github.felixchang67.diskandroid.core.log

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * App 全局日志入口。各模块通过它记录事件，记录会分发给所有 [LogSink]
 * （内存环形缓冲、会话文件、logcat）。
 *
 * 线程安全；任何 sink 抛出的异常都会被吞掉——日志永远不能影响业务流程。
 */
class DiskLog(private val clock: () -> Long = System::currentTimeMillis) {
    private val seq = AtomicLong(0)
    private val sinks = CopyOnWriteArrayList<LogSink>()

    @Volatile
    var minLevel: LogLevel = LogLevel.VERBOSE

    fun addSink(sink: LogSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>> = emptyList(),
        data: ByteArray? = null,
        throwable: Throwable? = null,
    ) {
        if (level < minLevel) return
        val record = LogRecord(
            seq = seq.incrementAndGet(),
            epochMillis = clock(),
            level = level,
            tag = tag,
            message = message,
            fields = fields.map { (k, v) -> k to v.toString() },
            data = data?.copyOf(),
            stackTrace = throwable?.stackTraceToString(),
            thread = Thread.currentThread().name,
        )
        for (sink in sinks) {
            try {
                sink.write(record)
            } catch (_: Throwable) {
                // 日志失败不能影响业务
            }
        }
    }

    fun v(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.VERBOSE, tag, message, fields.asList())

    fun d(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.DEBUG, tag, message, fields.asList())

    fun i(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.INFO, tag, message, fields.asList())

    fun w(tag: String, message: String, vararg fields: Pair<String, Any?>, throwable: Throwable? = null) =
        log(LogLevel.WARN, tag, message, fields.asList(), throwable = throwable)

    fun e(tag: String, message: String, vararg fields: Pair<String, Any?>, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, fields.asList(), throwable = throwable)

    /** 记录一段原始字节（文本日志里会输出 hex dump）。 */
    fun data(level: LogLevel, tag: String, message: String, data: ByteArray, vararg fields: Pair<String, Any?>) =
        log(level, tag, message, fields.asList(), data = data)

    fun flush() {
        for (sink in sinks) runCatching { sink.flush() }
    }
}
