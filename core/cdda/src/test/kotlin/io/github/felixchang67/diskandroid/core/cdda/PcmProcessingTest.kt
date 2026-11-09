package io.github.felixchang67.diskandroid.core.cdda

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmProcessingTest {
    private fun sine(hz: Double, frames: Int, amplitude: Double = 8000.0): ByteArray {
        val out = ByteArray(frames * 4)
        for (n in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * n / 44_100)).toInt()
            for (c in 0 until 2) {
                out[n * 4 + c * 2] = v.toByte()
                out[n * 4 + c * 2 + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    private fun rms(pcm: ByteArray, fromFrame: Int): Double {
        var sum = 0.0
        var n = 0
        for (f in fromFrame until pcm.size / 4) {
            val v = (pcm[f * 4 + 1].toInt() shl 8) or (pcm[f * 4].toInt() and 0xFF)
            sum += v.toDouble() * v
            n++
        }
        return sqrt(sum / n)
    }

    @Test
    fun deemphasisCurveMatchesAnalog50Over15Microseconds() {
        val d = Deemphasis()
        var worst = 0.0
        var f = 20.0
        while (f <= 20_000.0) {
            worst = maxOf(worst, abs(d.responseDb(f) - Deemphasis.analogDb(f)))
            f *= 10.0.pow(1.0 / 200)
        }
        assertTrue(worst < 0.07, "最大偏差 $worst dB")
        // 几个熟悉的点：1 kHz 约 −0.37 dB，10 kHz 约 −7.6 dB，20 kHz 约 −9.5 dB
        assertEquals(-0.37, Deemphasis.analogDb(1000.0), 0.01)
        assertEquals(-7.60, Deemphasis.analogDb(10_000.0), 0.01)
        assertEquals(-9.49, Deemphasis.analogDb(20_000.0), 0.01)
    }

    @Test
    fun deemphasisAttenuatesSinesAsPredicted() {
        for (hz in listOf(1000.0, 5000.0, 10_000.0)) {
            val pcm = sine(hz, 44_100)
            val before = rms(pcm, 22_050)
            val d = Deemphasis()
            d.process(pcm)
            val gainDb = 20 * log10(rms(pcm, 22_050) / before)
            assertEquals(d.responseDb(hz), gainDb, 0.05, "$hz Hz")
        }
    }

    @Test
    fun byteOrderDetectionRecognisesMusicLikeSignals() {
        val pcm = sine(440.0, 14_112)
        assertEquals(false, ByteOrderDetector.detect(pcm))
        ByteOrderDetector.swap(pcm)
        assertEquals(true, ByteOrderDetector.detect(pcm))
        ByteOrderDetector.swap(pcm)
        assertEquals(sine(440.0, 14_112).toList(), pcm.toList())
    }

    @Test
    fun byteOrderDetectionAbstainsOnSilenceAndNoise() {
        assertNull(ByteOrderDetector.detect(ByteArray(14_112 * 4)))
        val noise = Random(1).nextBytes(14_112 * 4)
        assertNull(ByteOrderDetector.detect(noise))
    }
}
