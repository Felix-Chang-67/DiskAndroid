package io.github.felixchang67.diskandroid.core.cdda

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 字节序设置：自动检测，或强制小端 / 大端。 */
enum class ByteOrderMode(val label: String) {
    AUTO("自动"),
    LITTLE("小端"),
    BIG("大端"),
}

/**
 * CD-DA 字节序检测。READ CD 返回的 16 位采样绝大多数光驱是小端（与 Android 的 PCM 一致），个别光驱是大端。
 * 思路与 cdparanoia 的 data_bigendianp 相同：真实音乐的能量集中在低频，按错误字节序解释时低字节变成高位，
 * 信号就成了接近白噪声的东西，高频能量（这里用一阶差分能量占总能量的比例衡量）明显偏高。
 */
object ByteOrderDetector {
    private const val MIN_SAMPLES = 2048
    private const val MARGIN = 1.5

    /** 返回 true = 大端，false = 小端，null = 判断不了（静音或信号太像噪声）。 */
    fun detect(pcm: ByteArray, length: Int = pcm.size): Boolean? {
        val frames = length / BYTES_PER_FRAME
        if (frames < 2) return null
        var nonZero = 0
        val le = DoubleArray(2)
        val be = DoubleArray(2)
        val prevLe = IntArray(2)
        val prevBe = IntArray(2)
        for (f in 0 until frames) {
            for (c in 0 until 2) {
                val i = f * BYTES_PER_FRAME + c * 2
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val vLe = (hi shl 8) or lo
                val vBe = ((pcm[i].toInt()) shl 8) or (pcm[i + 1].toInt() and 0xFF)
                if (vLe != 0) nonZero++
                if (f > 0) {
                    val dLe = (vLe - prevLe[c]).toDouble()
                    val dBe = (vBe - prevBe[c]).toDouble()
                    le[0] += dLe * dLe
                    be[0] += dBe * dBe
                }
                le[1] += vLe.toDouble() * vLe
                be[1] += vBe.toDouble() * vBe
                prevLe[c] = vLe
                prevBe[c] = vBe
            }
        }
        if (nonZero < MIN_SAMPLES || le[1] == 0.0 || be[1] == 0.0) return null
        val rLe = le[0] / le[1]
        val rBe = be[0] / be[1]
        return when {
            rLe * MARGIN < rBe -> false
            rBe * MARGIN < rLe -> true
            else -> null
        }
    }

    fun swap(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val t = pcm[i]
            pcm[i] = pcm[i + 1]
            pcm[i + 1] = t
            i += 2
        }
    }
}

/**
 * CD 去加重（50/15 µs，IEC 60908 / Red Book），44.1 kHz 立体声 16 位小端 PCM，原地处理。
 * 用一个高架滤波器（RBJ Audio EQ Cookbook 的 high shelf）拟合模拟去加重曲线，参数取自 SoX 的 deemph 效果器：
 * 增益 −9.477 dB、转折频率 5283 Hz、斜率 0.4845；与模拟曲线 H(s) = (1 + s·15µs) / (1 + s·50µs) 在
 * 20 Hz–20 kHz 内最大偏差约 0.06 dB（单元测试里按定义逐点核对）。
 */
class Deemphasis {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private val x1 = DoubleArray(2)
    private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2)
    private val y2 = DoubleArray(2)

    init {
        val a = 10.0.pow(GAIN_DB / 40)
        val w0 = 2 * PI * F0 / SAMPLE_RATE
        val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / SLOPE - 1) + 2)
        val c = cos(w0)
        val sa = sqrt(a)
        val a0 = (a + 1) - (a - 1) * c + 2 * sa * alpha
        b0 = a * ((a + 1) + (a - 1) * c + 2 * sa * alpha) / a0
        b1 = -2 * a * ((a - 1) + (a + 1) * c) / a0
        b2 = a * ((a + 1) + (a - 1) * c - 2 * sa * alpha) / a0
        a1 = 2 * ((a - 1) - (a + 1) * c) / a0
        a2 = ((a + 1) - (a - 1) * c - 2 * sa * alpha) / a0
    }

    fun reset() {
        x1.fill(0.0)
        x2.fill(0.0)
        y1.fill(0.0)
        y2.fill(0.0)
    }

    /** 处理 [offset] 起 [length] 字节（整帧）。 */
    fun process(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        val frames = length / BYTES_PER_FRAME
        for (f in 0 until frames) {
            for (c in 0 until 2) {
                val i = offset + f * BYTES_PER_FRAME + c * 2
                val x = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toDouble()
                val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                x2[c] = x1[c]
                x1[c] = x
                y2[c] = y1[c]
                y1[c] = y
                val v = Math.round(y).toInt().coerceIn(-32768, 32767)
                pcm[i] = v.toByte()
                pcm[i + 1] = (v shr 8).toByte()
            }
        }
    }

    /** 测试用：频率 [hz] 处的理论幅度响应（dB）。 */
    fun responseDb(hz: Double): Double {
        val w = 2 * PI * hz / SAMPLE_RATE
        // H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val ni = -(b1 * sin(w) + b2 * sin(2 * w))
        val dr = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val di = -(a1 * sin(w) + a2 * sin(2 * w))
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * kotlin.math.log10(mag)
    }

    companion object {
        const val SAMPLE_RATE = 44_100.0
        const val GAIN_DB = -9.477
        const val F0 = 5283.0
        const val SLOPE = 0.4845

        /** 模拟去加重曲线 (1 + jωτ2) / (1 + jωτ1)，τ1 = 50 µs，τ2 = 15 µs，单位 dB。 */
        fun analogDb(hz: Double): Double {
            val w = 2 * PI * hz
            val num = 1 + (w * 15e-6).pow(2)
            val den = 1 + (w * 50e-6).pow(2)
            return 10 * kotlin.math.log10(num / den)
        }
    }
}

/** 音量渐变（16 位立体声小端 PCM，原地处理）。 */
object PcmGain {
    /** 5 ms：读不出的扇区静音前后的淡出 / 淡入长度，避免爆音。 */
    const val SILENCE_FADE_FRAMES = 220

    /** 把从第 [fromFrame] 帧开始的 [frames] 帧乘上从 [g0] 线性变到 [g1] 的增益。超出缓冲区的部分忽略。 */
    fun ramp(pcm: ByteArray, fromFrame: Int, frames: Int, g0: Double, g1: Double) {
        if (frames <= 0) return
        for (f in 0 until frames) {
            val base = (fromFrame + f) * BYTES_PER_FRAME
            if (base < 0 || base + 3 >= pcm.size) continue
            val g = g0 + (g1 - g0) * f / frames
            for (c in 0 until 2) {
                val i = base + c * 2
                val v = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
                val o = Math.round(v * g).toInt().coerceIn(-32768, 32767)
                pcm[i] = o.toByte()
                pcm[i + 1] = (o shr 8).toByte()
            }
        }
    }

    /**
     * 读不出的扇区已经是 0：在每段静音前淡出、后淡入（各 [fadeFrames] 帧），只处理这一块缓冲区内的部分。
     * @param chunkLba 这块缓冲区第一个扇区的 LBA。
     */
    fun fadeAroundSilence(pcm: ByteArray, chunkLba: Long, bad: List<Long>, fadeFrames: Int = SILENCE_FADE_FRAMES) {
        val frames = pcm.size / BYTES_PER_FRAME
        val sectors = frames / FRAMES_PER_SECTOR
        val inChunk = bad.filter { it >= chunkLba && it < chunkLba + sectors }.sorted()
        var i = 0
        while (i < inChunk.size) {
            var j = i
            while (j + 1 < inChunk.size && inChunk[j + 1] == inChunk[j] + 1) j++
            val silenceStart = ((inChunk[i] - chunkLba) * FRAMES_PER_SECTOR).toInt()
            val silenceEnd = ((inChunk[j] - chunkLba + 1) * FRAMES_PER_SECTOR).toInt()
            val before = minOf(fadeFrames, silenceStart)
            ramp(pcm, silenceStart - before, before, 1.0, 0.0)
            val after = minOf(fadeFrames, frames - silenceEnd)
            ramp(pcm, silenceEnd, after, 0.0, 1.0)
            i = j + 1
        }
    }
}

