package io.github.felixchang67.diskandroid.core.log

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFormatAndSinksTest {
    private fun record(
        seq: Long = 1,
        level: LogLevel = LogLevel.INFO,
        message: String = "msg",
        fields: List<Pair<String, String>> = emptyList(),
        data: ByteArray? = null,
        stack: String? = null,
    ) = LogRecord(seq, 1_700_000_000_000, level, "USB", message, fields, data, stack, "main")

    @Test
    fun jsonEscapesSpecialCharacters() {
        val json = LogFormat.json(record(message = "a\"b\\c\nd\u0001", fields = listOf("k" to "v\t")))
        assertTrue(json.contains("\"msg\":\"a\\\"b\\\\c\\nd\\u0001\""), json)
        assertTrue(json.contains("\"f\":{\"k\":\"v\\t\"}"), json)
        assertTrue(json.startsWith("{\"seq\":1,"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun jsonKeepsFullDataAsHex() {
        val json = LogFormat.json(record(data = ByteArray(600) { 0x5A }))
        assertTrue(json.contains("\"data\":\"" + "5A".repeat(600) + "\""))
    }

    @Test
    fun textContainsFieldsDumpAndTruncationNote() {
        val text = LogFormat.text(
            record(level = LogLevel.WARN, fields = listOf("vid" to "0411"), data = ByteArray(40) { it.toByte() }),
            maxDataBytes = 16,
        )
        val lines = text.lines()
        assertTrue(lines[0].contains(" W/USB: msg  vid=0411"), lines[0])
        assertEquals("    [40 字节]", lines[1])
        assertTrue(lines[2].startsWith("    0000: 00 01 02"), lines[2])
        assertTrue(lines.last().contains("另有 24 字节"), lines.last())
    }

    @Test
    fun ringBufferEvictsOldestAndBumpsVersion() {
        val ring = RingBufferSink(capacity = 3)
        repeat(5) { ring.write(record(seq = it.toLong() + 1)) }
        assertEquals(listOf(3L, 4L, 5L), ring.snapshot().map { it.seq })
        assertEquals(5L, ring.version.value)
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
        assertEquals(6L, ring.version.value)
    }

    @Test
    fun diskLogFiltersByLevelAndSurvivesFailingSink() {
        val log = DiskLog(clock = { 42L })
        val ring = RingBufferSink(10)
        log.addSink(object : LogSink {
            override fun write(record: LogRecord) = throw IllegalStateException("boom")
        })
        log.addSink(ring)
        log.minLevel = LogLevel.INFO
        log.d("T", "hidden")
        log.i("T", "shown", "n" to 1, "x" to null)
        val recs = ring.snapshot()
        assertEquals(1, recs.size)
        assertEquals(listOf("n" to "1", "x" to "null"), recs[0].fields)
        assertEquals(42L, recs[0].epochMillis)
    }

    @Test
    fun fileSinkWritesTextAndJsonAndPrunesOldSessions() {
        val dir = Files.createTempDirectory("logtest").toFile()
        try {
            // 造 3 个旧会话
            for (i in 1..3) {
                File(dir, "session-old$i.log").apply { writeText("x"); setLastModified(1000L * i) }
                File(dir, "session-old$i.jsonl").apply { writeText("x"); setLastModified(1000L * i) }
            }
            val sink = FileLogSink(dir, "session-new", keepSessions = 2)
            sink.write(record(message = "hello"))
            sink.flush()
            val names = dir.list()!!.sorted()
            // 保留最近 2 个旧会话（old2、old3），加上新会话
            assertEquals(
                listOf("session-new.jsonl", "session-new.log", "session-old2.jsonl", "session-old2.log", "session-old3.jsonl", "session-old3.log"),
                names,
            )
            assertTrue(File(dir, "session-new.log").readText().contains("I/USB: hello"))
            assertTrue(File(dir, "session-new.jsonl").readText().contains("\"msg\":\"hello\""))
            assertEquals(2, sink.sessionFiles().size)
            sink.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
