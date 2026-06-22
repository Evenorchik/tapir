package com.sleeptalk.app

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Audio helpers shared by the recorder, the calibrator and the UI. */
object Audio {

    const val SAMPLE_RATE = 16000

    /** Map a raw RMS (0..32767) to a perceptual 0..100 level for meters/waveforms. */
    fun levelFromRms(rms: Double): Int {
        if (rms <= 1.0) return 0
        val db = 20.0 * log10(rms)          // ~30 dB quiet … ~80 dB loud
        val level = (db - 30.0) / 50.0 * 100.0
        return level.coerceIn(0.0, 100.0).roundToInt()
    }

    /** Resample a list of 0..100 values to exactly [n] bars (max-pooling). */
    fun resample(src: List<Int>, n: Int): IntArray {
        if (n <= 0) return IntArray(0)
        if (src.isEmpty()) return IntArray(0)
        if (src.size == n) return src.toIntArray()
        val out = IntArray(n)
        for (i in 0 until n) {
            val start = (i.toLong() * src.size / n).toInt()
            val end = max(start + 1, ((i + 1).toLong() * src.size / n).toInt())
            var m = 0
            for (j in start until min(end, src.size)) m = max(m, src[j])
            out[i] = m
        }
        return out
    }
}
