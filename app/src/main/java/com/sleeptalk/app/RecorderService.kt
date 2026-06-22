package com.sleeptalk.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Foreground service that records the microphone all night, keeps only the
 * chunks louder than the ambient noise floor (with lead-in / lead-out padding)
 * and writes them, concatenated, into a single WAV. It also records per-segment
 * metadata (when, how long, how loud, mini-waveform) for the morning summary.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.sleeptalk.app.START"
        const val ACTION_STOP = "com.sleeptalk.app.STOP"
        const val ACTION_SET_SENSITIVITY = "com.sleeptalk.app.SET_SENSITIVITY"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val BROADCAST_SAVED = "com.sleeptalk.app.SESSION_SAVED"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        @Volatile var sensitivity = 50
    }

    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, 50)
                startRecording()
            }
            ACTION_SET_SENSITIVITY ->
                sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, sensitivity)
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (running) return
        createChannel()
        startInForeground()
        acquireWakeLock()
        running = true
        RecorderState.reset()
        RecorderState.isRecording = true
        RecorderState.startElapsedMs = SystemClock.elapsedRealtime()
        RecorderState.startWallMs = System.currentTimeMillis()
        recordThread = thread(start = true, name = "unsleep-rec") { recordLoop() }
    }

    private fun stopRecording() {
        running = false
        RecorderState.isRecording = false
        try {
            recordThread?.join(4000)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recordLoop() {
        val sampleRate = Audio.SAMPLE_RATE
        val frameSamples = sampleRate / 10 // 100 ms frames
        val frameMs = 100L
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, frameSamples * 2 * 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            stopSelf(); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); stopSelf(); return
        }

        val calThreshold = getSharedPreferences("unsleep", Context.MODE_PRIVATE)
            .getFloat("calThreshold", 0f).toDouble()

        val dir = SessionStore.dir(this)
        val sessionStart = RecorderState.startWallMs
        val wavFile = File(dir, "session_$sessionStart.wav")
        val writer = WavWriter(wavFile, sampleRate)

        val prerollFrames = 8   // 0.8 s lead-in
        val hangoverFrames = 15 // 1.5 s lead-out
        val preroll = ArrayDeque<ShortArray>()

        var isActive = false
        var framesSinceLoud = 0
        var noiseFloor = -1.0

        val moments = mutableListOf<Moment>()
        var segPeaks = mutableListOf<Int>()
        var segSamples = 0L
        var segStartWall = 0L

        val buf = ShortArray(frameSamples)
        recorder.startRecording()
        try {
            while (running) {
                var read = 0
                while (read < frameSamples) {
                    val r = recorder.read(buf, read, frameSamples - read)
                    if (r <= 0) break
                    read += r
                }
                if (read <= 0) continue

                val rms = computeRms(buf, read)
                val level = Audio.levelFromRms(rms)
                if (noiseFloor < 0) noiseFloor = rms

                val s = sensitivity.coerceIn(0, 100)
                val sensScale = 2.0.pow((50 - s) / 50.0) // s50→1.0, s0→2.0 (less), s100→0.5 (more)
                val base = if (calThreshold > 0) calThreshold else max(noiseFloor * 3.2, 170.0)
                val threshold = max(noiseFloor * 1.4, base * sensScale)

                RecorderState.currentRms = rms
                RecorderState.currentLevel = level
                RecorderState.threshold = threshold
                RecorderState.triggered = rms > threshold

                if (rms > threshold) {
                    if (!isActive) {
                        // open a new segment: flush the pre-roll lead-in
                        segPeaks = mutableListOf()
                        segSamples = 0L
                        segStartWall = System.currentTimeMillis() - preroll.size * frameMs
                        for (f in preroll) {
                            writer.writeFrame(f, f.size)
                            segPeaks.add(Audio.levelFromRms(computeRms(f, f.size)))
                            segSamples += f.size
                            RecorderState.capturedSamples += f.size
                        }
                        preroll.clear()
                        isActive = true
                        RecorderState.segmentCount = moments.size + 1
                    }
                    framesSinceLoud = 0
                    writer.writeFrame(buf, read)
                    segPeaks.add(level)
                    segSamples += read
                    RecorderState.capturedSamples += read
                } else {
                    if (isActive) {
                        framesSinceLoud++
                        writer.writeFrame(buf, read)
                        segPeaks.add(level)
                        segSamples += read
                        RecorderState.capturedSamples += read
                        if (framesSinceLoud >= hangoverFrames) {
                            moments.add(finishSegment(segStartWall, segSamples, segPeaks, sampleRate))
                            isActive = false
                        }
                    } else {
                        noiseFloor = noiseFloor * 0.98 + rms * 0.02
                        preroll.addLast(buf.copyOf(read))
                        while (preroll.size > prerollFrames) preroll.removeFirst()
                    }
                }
            }
            if (isActive) {
                moments.add(finishSegment(segStartWall, segSamples, segPeaks, sampleRate))
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            writer.finish()
            val endWall = System.currentTimeMillis()
            if (moments.isEmpty() || RecorderState.capturedSamples == 0L) {
                wavFile.delete()
            } else {
                val loudest = moments.maxByOrNull { it.peak }
                val player = Audio.resample(moments.flatMap { it.w.toList() }, 40)
                SessionStore.writeMeta(
                    wavFile, sessionStart, endWall, RecorderState.capturedMs,
                    loudest?.startMs ?: 0L, player, moments
                )
                sendBroadcast(Intent(BROADCAST_SAVED).setPackage(packageName))
            }
        }
    }

    private fun finishSegment(
        startWall: Long, samples: Long, peaks: List<Int>, sampleRate: Int
    ): Moment {
        val durMs = samples * 1000 / sampleRate
        val peak = peaks.maxOrNull() ?: 0
        return Moment(startWall, durMs, peak, Audio.resample(peaks, 20))
    }

    private fun computeRms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_short), stopIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unsleep:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        RecorderState.isRecording = false
        releaseWakeLock()
    }
}
