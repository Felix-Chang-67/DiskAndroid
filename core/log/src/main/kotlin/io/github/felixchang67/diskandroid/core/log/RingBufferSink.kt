package io.github.felixchang67.diskandroid.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 内存环形缓冲，供 App 内日志面板显示。
 *
 * 不直接暴露列表的 Flow（每条日志都复制整个列表太贵），而是暴露一个递增的 [version]，
 * UI 观察到变化后再调用 [snapshot]。
 */
class RingBufferSink(
    private val capacity: Int = 8000,
    /** 只收下返回 true 的记录（例如不收播放时每秒几条的 READ CD 流水，免得把有用的日志挤掉；文件里照常完整记录）。 */
    private val accept: (LogRecord) -> Boolean = { true },
) : LogSink {
    init {
        require(capacity > 0)
    }

    private val buffer = ArrayDeque<LogRecord>(capacity)
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    override fun write(record: LogRecord) {
        if (!accept(record)) return
        synchronized(buffer) {
            if (buffer.size == capacity) buffer.removeFirst()
            buffer.addLast(record)
        }
        _version.update { it + 1 }
    }

    fun snapshot(): List<LogRecord> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _version.update { it + 1 }
    }
}
