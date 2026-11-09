package io.github.felixchang67.diskandroid.log

import android.util.Log
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.log.LogRecord
import io.github.felixchang67.diskandroid.core.log.LogSink

/** 同步输出到 logcat（标签 DiskAndroid.<模块>），有电脑时可以用 adb logcat 查看。 */
class LogcatSink : LogSink {
    override fun write(record: LogRecord) {
        val priority = when (record.level) {
            LogLevel.VERBOSE -> Log.VERBOSE
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, "DiskAndroid." + record.tag, LogFormat.text(record, maxDataBytes = 256))
    }
}
