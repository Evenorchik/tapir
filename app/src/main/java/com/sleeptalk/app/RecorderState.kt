package com.sleeptalk.app

/**
 * Shared, in-memory snapshot of the recorder's live state. The service writes to
 * it from the recording thread; the UI polls it a few times per second.
 */
object RecorderState {

    @Volatile var isRecording = false
    @Volatile var startElapsedMs = 0L
    @Volatile var startWallMs = 0L
    @Volatile var currentRms = 0.0
    @Volatile var currentLevel = 0      // 0..100 for the meter
    @Volatile var threshold = 0.0
    @Volatile var triggered = false     // is the current frame above threshold
    @Volatile var segmentCount = 0
    @Volatile var capturedSamples = 0L

    val capturedMs: Long
        get() = capturedSamples * 1000 / Audio.SAMPLE_RATE

    fun reset() {
        currentRms = 0.0
        currentLevel = 0
        threshold = 0.0
        triggered = false
        segmentCount = 0
        capturedSamples = 0L
    }
}
