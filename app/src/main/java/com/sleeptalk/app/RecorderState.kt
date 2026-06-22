package com.sleeptalk.app

/**
 * Shared, in-memory snapshot of the recorder's live state. The service writes to it
 * from the recording thread; the UI polls it a few times per second. Fields are
 * volatile so the UI thread always sees fresh values.
 */
object RecorderState {
    const val SAMPLE_RATE = 16000

    @Volatile var isRecording = false
    @Volatile var startElapsedMs = 0L
    @Volatile var currentRms = 0.0
    @Volatile var threshold = 0.0
    @Volatile var noiseFloor = 0.0
    @Volatile var segmentCount = 0
    @Volatile var capturedSamples = 0L

    val capturedMs: Long
        get() = capturedSamples * 1000 / SAMPLE_RATE

    fun reset() {
        currentRms = 0.0
        threshold = 0.0
        noiseFloor = 0.0
        segmentCount = 0
        capturedSamples = 0L
    }
}
