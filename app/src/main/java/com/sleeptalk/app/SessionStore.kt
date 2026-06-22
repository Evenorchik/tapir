package com.sleeptalk.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** A finished recording session: the glued WAV plus its metadata. */
data class Session(
    val file: File,
    val dateMs: Long,
    val segments: Int,
    val durationMs: Long,
    val sizeBytes: Long
)

object SessionStore {

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "sessions")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<Session> {
        val d = dir(ctx)
        val wavs = d.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return emptyList()
        return wavs.map { f ->
            val meta = File(d, f.nameWithoutExtension + ".json")
            var dateMs = f.lastModified()
            var segments = 0
            var durationMs = 0L
            if (meta.exists()) {
                try {
                    val j = JSONObject(meta.readText())
                    dateMs = j.optLong("dateMs", dateMs)
                    segments = j.optInt("segments", 0)
                    durationMs = j.optLong("durationMs", 0L)
                } catch (_: Exception) {
                }
            }
            Session(f, dateMs, segments, durationMs, f.length())
        }.sortedByDescending { it.dateMs }
    }

    fun writeMeta(wav: File, dateMs: Long, segments: Int, durationMs: Long) {
        val meta = File(wav.parentFile, wav.nameWithoutExtension + ".json")
        val j = JSONObject()
        j.put("dateMs", dateMs)
        j.put("segments", segments)
        j.put("durationMs", durationMs)
        meta.writeText(j.toString())
    }

    fun delete(s: Session) {
        s.file.delete()
        File(s.file.parentFile, s.file.nameWithoutExtension + ".json").delete()
    }
}
