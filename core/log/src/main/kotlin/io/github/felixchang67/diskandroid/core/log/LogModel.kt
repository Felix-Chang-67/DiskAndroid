package io.github.felixchang67.diskandroid.core.log

/** 日志级别，按严重程度递增（可直接用 `<` / `>=` 比较）。 */
enum class LogLevel(val letter: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * 一条日志记录。
 *
 * @property fields 结构化字段（键值对），文本格式里追加在消息后面，JSONL 里单独成对象。
 * @property data 原始字节（例如 USB 描述符、SCSI 数据），文本格式里输出 hex dump。
 */
class LogRecord(
    val seq: Long,
    val epochMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val fields: List<Pair<String, String>>,
    val data: ByteArray?,
    val stackTrace: String?,
    val thread: String,
)

/** 日志输出目标。实现必须线程安全。 */
interface LogSink {
    fun write(record: LogRecord)
    fun flush() {}
    fun close() {}
}

/** 统一的日志标签，方便过滤。 */
object LogTags {
    const val APP = "APP"
    const val USB = "USB"
    const val PROBE = "PROBE"
    const val CRASH = "CRASH"
    const val UI = "UI"
}
