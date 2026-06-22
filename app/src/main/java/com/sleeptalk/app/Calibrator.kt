package com.sleeptalk.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Short guided "whisper test". The user holds the phone ~0.5 m away: we measure
 * the room's silence, then their whisper, and derive a detection threshold a
 * safe margin BELOW the whisper so even quieter sleep-talk is caught.
 *
 * All callbacks are posted to [main] so the UI can touch views directly.
 */
class Calibrator(
    private val main: Handler,
    private val onPhase: (Phase, secondsLeft: Int) -> Unit,
    private val onLevel: (level: Int) -> Unit,
    private val onDone: (Result) -> Unit,
    private val onError: (String) -> Unit
) {
    enum class Phase { AMBIENT, WHISPER }

    data class Result(val thresholdRms: Double, val whisperRms: Double, val floorRms: Double)

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(start = true, name = "unsleep-calib") { run() }
    }

    fun cancel() {
        running = false
    }

    private fun run() {
        val sr = Audio.SAMPLE_RATE
        val frame = sr / 10
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, frame * 2 * 4)
            )
        } catch (e: SecurityException) {
            main.post { onError("Нет доступа к микрофону") }; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); main.post { onError("Микрофон недоступен") }; return
        }

        val ambientFrames = 25 // 2.5 s
        val whisperFrames = 45 // 4.5 s
        val ambient = ArrayList<Double>()
        val whisper = ArrayList<Double>()
        val buf = ShortArray(frame)

        rec.startRecording()
        try {
            for (i in 0 until ambientFrames) {
                if (!running) return
                val rms = readRms(rec, buf, frame)
                ambient.add(rms)
                val left = (ambientFrames - i + 9) / 10
                main.post { onLevel(Audio.levelFromRms(rms)); onPhase(Phase.AMBIENT, left) }
            }
            for (i in 0 until whisperFrames) {
                if (!running) return
                val rms = readRms(rec, buf, frame)
                whisper.add(rms)
                val left = (whisperFrames - i + 9) / 10
                main.post { onLevel(Audio.levelFromRms(rms)); onPhase(Phase.WHISPER, left) }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (!running) return

        val floor = percentile(ambient, 0.5)
        val whisperLevel = percentile(whisper, 0.6)
        // Margin: trigger at 60% of a held-phone whisper, but always clearly
        // above the room's noise floor.
        val threshold = max(floor * 1.6, whisperLevel * 0.6)
        running = false
        main.post { onDone(Result(threshold, whisperLevel, floor)) }
    }

    private fun readRms(rec: AudioRecord, buf: ShortArray, frame: Int): Double {
        var read = 0
        while (read < frame) {
            val r = rec.read(buf, read, frame - read)
            if (r <= 0) break
            read += r
        }
        if (read <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until read) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / read)
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
