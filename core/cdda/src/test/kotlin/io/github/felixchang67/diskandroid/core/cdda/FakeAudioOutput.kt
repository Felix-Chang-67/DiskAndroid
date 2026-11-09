package io.github.felixchang67.diskandroid.core.cdda

import java.io.ByteArrayOutputStream

/**
 * 模拟 AudioTrack：有限的缓冲区（默认 0.5 秒），播放时按虚拟时间以 44.1 kHz 消耗数据，数据不够时停住（欠载）。
 * [all] 记录自上次 flush 以来写入的全部字节，[history] 记录所有写入（含被 flush 掉的）。
 */
class FakeAudioOutput(private val clock: () -> Long, private val capacityFrames: Long = 22_050) : AudioOutput {
    var playing = false
        private set
    var released = false
        private set
    var volume = 1f
        private set
    var drained = false
        private set
    var flushes = 0
        private set
    private var written = 0L
    private var played = 0L
    private var lastMs = 0L
    val all = ByteArrayOutputStream()

    private fun advance() {
        val now = clock()
        if (playing) {
            val frames = (now - lastMs) * 44_100 / 1000
            played = minOf(written, played + frames)
        }
        lastMs = now
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        advance()
        val free = capacityFrames - (written - played)
        val n = (minOf(length.toLong(), free * BYTES_PER_FRAME) / BYTES_PER_FRAME * BYTES_PER_FRAME).toInt()
        if (n <= 0) return 0
        all.write(data, offset, n)
        written += n / BYTES_PER_FRAME
        drained = false
        return n
    }

    override fun play() {
        advance()
        playing = true
    }

    override fun pause() {
        advance()
        playing = false
    }

    override fun flush() {
        advance()
        written = 0
        played = 0
        all.reset()
        flushes++
    }

    override fun drain() {
        advance()
        drained = true
    }

    override val playedFrames: Long
        get() {
            advance()
            return played
        }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun release() {
        released = true
    }
}
