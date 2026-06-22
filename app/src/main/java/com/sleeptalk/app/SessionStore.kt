package com.sleeptalk.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One detected sound during the night. */
data class Moment(
    val startMs: Long,   // wall-clock time the sound began
    val durMs: Long,     // how long it lasted
    val peak: Int,       // loudness 0..100
    val w: IntArray      // mini-waveform (0..100)
)

/** A finished night: the glued WAV plus everything we show in the morning. */
data class Session(
    val file: File,
    val dateMs: Long,    // recording start
    val endMs: Long,     // recording stop
    val sleepMs: Long,   // endMs - dateMs
    val soundMs: Long,   // total kept audio
    val loudestMs: Long, // wall-clock of the loudest moment (0 if none)
    val sizeBytes: Long,
    val player: IntArray,        // waveform for the combined-track player (~40 bars)
    val moments: List<Moment>
) {
    val count: Int get() = moments.size
}

object SessionStore {

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "sessions")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<Session> {
        val d = dir(ctx)
        val wavs = d.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return emptyList()
        return wavs.mapNotNull { parse(it) }.sortedByDescending { it.dateMs }
    }

    fun latest(ctx: Context): Session? = list(ctx).firstOrNull()

    private fun parse(wav: File): Session {
        val meta = File(wav.parentFile, wav.nameWithoutExtension + ".json")
        var dateMs = wav.lastModified()
        var endMs = dateMs
        var sleepMs = 0L
        var soundMs = 0L
        var loudestMs = 0L
        var player = IntArray(0)
        val moments = mutableListOf<Moment>()
        if (meta.exists()) {
            try {
                val j = JSONObject(meta.readText())
                dateMs = j.optLong("dateMs", dateMs)
                endMs = j.optLong("endMs", dateMs)
                sleepMs = j.optLong("sleepMs", 0L)
                soundMs = j.optLong("soundMs", 0L)
                loudestMs = j.optLong("loudestMs", 0L)
                player = toIntArray(j.optJSONArray("player"))
                val arr = j.optJSONArray("moments")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        moments.add(
                            Moment(
                                m.optLong("startMs", dateMs),
                                m.optLong("durMs", 0L),
                                m.optInt("peak", 0),
                                toIntArray(m.optJSONArray("w"))
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        return Session(wav, dateMs, endMs, sleepMs, soundMs, loudestMs, wav.length(), player, moments)
    }

    fun writeMeta(
        wav: File, dateMs: Long, endMs: Long, soundMs: Long, loudestMs: Long,
        player: IntArray, moments: List<Moment>
    ) {
        val j = JSONObject()
        j.put("dateMs", dateMs)
        j.put("endMs", endMs)
        j.put("sleepMs", endMs - dateMs)
        j.put("soundMs", soundMs)
        j.put("loudestMs", loudestMs)
        j.put("player", fromIntArray(player))
        val arr = JSONArray()
        for (m in moments) {
            val o = JSONObject()
            o.put("startMs", m.startMs)
            o.put("durMs", m.durMs)
            o.put("peak", m.peak)
            o.put("w", fromIntArray(m.w))
            arr.put(o)
        }
        j.put("moments", arr)
        File(wav.parentFile, wav.nameWithoutExtension + ".json").writeText(j.toString())
    }

    fun delete(s: Session) {
        s.file.delete()
        File(s.file.parentFile, s.file.nameWithoutExtension + ".json").delete()
    }

    private fun toIntArray(arr: JSONArray?): IntArray {
        if (arr == null) return IntArray(0)
        return IntArray(arr.length()) { arr.optInt(it, 0) }
    }

    private fun fromIntArray(a: IntArray): JSONArray {
        val arr = JSONArray()
        for (v in a) arr.put(v)
        return arr
    }
}
