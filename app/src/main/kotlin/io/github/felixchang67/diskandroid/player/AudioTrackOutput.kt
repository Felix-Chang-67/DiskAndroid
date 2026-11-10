package io.github.felixchang67.diskandroid.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.felixchang67.diskandroid.core.cdda.AudioOutput
import io.github.felixchang67.diskandroid.core.log.DiskLog

/**
 * 用 AudioTrack（流模式，44.1 kHz / 16 位 / 立体声）实现 [AudioOutput]。写入用非阻塞模式，
 * 播放器线程不会卡在 write() 里，暂停 / 跳转 / 停止随时可以响应。缓冲约 0.2 秒。
 */
class AudioTrackOutput(log: DiskLog) : AudioOutput {
    private val track: AudioTrack

    init {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        // 约 0.2 秒：自己的读盘缓冲已有约 10 秒，这里只需扛住线程调度抖动；越小，手动切歌时新歌接上得越快
        val size = maxOf(min * 2, SAMPLE_RATE * 4 / 5)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(size)
            .build()
        log.i(TAG, "AudioTrack 已创建", "缓冲字节" to track.bufferSizeInFrames * 4, "最小缓冲" to min, "输出采样率" to track.sampleRate)
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int =
        track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)

    override fun play() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    override fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    /** 调用前已经 pause()；flush 之后播放头位置归零（AudioTrack 文档）。 */
    override fun flush() = track.flush()

    /** 流模式下已写入的数据在播放状态下会自然播完，不需要 stop()（stop() 还会把播放头位置清零）。 */
    override fun drain() = Unit

    override val playedFrames: Long get() = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }

    override fun release() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
    }

    companion object {
        const val TAG = "AUDIO"
        const val SAMPLE_RATE = 44_100
    }
}
