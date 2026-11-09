package io.github.felixchang67.diskandroid.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日志的两种序列化格式：人类可读文本（.log）与机器可读 JSON 行（.jsonl）。 */
object LogFormat {
    /** 文本格式里最多 dump 的数据字节数；JSONL 总是完整保留。 */
    const val DEFAULT_TEXT_DATA_LIMIT = 512

    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone)
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)

    fun time(epochMillis: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMillis))

    fun isoTime(epochMillis: Long): String = isoFormatter.format(Instant.ofEpochMilli(epochMillis))

    /** `13:24:58.484 I/USB: 设备插入  vid=0411  pid=0001`，后面跟 hex dump 与堆栈（缩进 4 格）。 */
    fun text(record: LogRecord, maxDataBytes: Int = DEFAULT_TEXT_DATA_LIMIT): String {
        val sb = StringBuilder(96)
        sb.append(time(record.epochMillis)).append(' ')
            .append(record.level.letter).append('/').append(record.tag).append(": ")
            .append(record.message)
        for ((k, v) in record.fields) {
            sb.append("  ").append(k).append('=').append(v)
        }
        record.data?.let { data ->
            val n = minOf(data.size, maxDataBytes)
            sb.append("\n    [").append(data.size).append(" 字节]")
            if (n > 0) {
                for (line in Hex.dump(data, 0, n).lineSequence()) sb.append("\n    ").append(line)
            }
            if (n < data.size) sb.append("\n    …（另有 ").append(data.size - n).append(" 字节未显示，完整内容见 .jsonl）")
        }
        record.stackTrace?.let { st ->
            for (line in st.trimEnd().lineSequence()) sb.append("\n    ").append(line)
        }
        return sb.toString()
    }

    /** 一行 JSON：`{"seq":1,"ts":"…","lvl":"I","tag":"USB","thread":"main","msg":"…","f":{…},"data":"…","err":"…"}` */
    fun json(record: LogRecord): String {
        val sb = StringBuilder(128)
        sb.append("{\"seq\":").append(record.seq)
        sb.append(",\"ts\":"); appendJsonString(sb, isoTime(record.epochMillis))
        sb.append(",\"ms\":").append(record.epochMillis)
        sb.append(",\"lvl\":\"").append(record.level.letter).append('"')
        sb.append(",\"tag\":"); appendJsonString(sb, record.tag)
        sb.append(",\"thread\":"); appendJsonString(sb, record.thread)
        sb.append(",\"msg\":"); appendJsonString(sb, record.message)
        if (record.fields.isNotEmpty()) {
            sb.append(",\"f\":{")
            record.fields.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                appendJsonString(sb, k)
                sb.append(':')
                appendJsonString(sb, v)
            }
            sb.append('}')
        }
        record.data?.let { sb.append(",\"data\":\"").append(Hex.compact(it)).append('"') }
        record.stackTrace?.let { sb.append(",\"err\":"); appendJsonString(sb, it) }
        sb.append('}')
        return sb.toString()
    }

    fun appendJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(Hex.hex(ch.code, 4))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }
}
