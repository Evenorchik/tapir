package com.sleeptalk.app

import java.io.File
import java.io.RandomAccessFile

/**
 * Streams 16-bit PCM mono samples into a WAV file. A 44-byte placeholder header is
 * written up front and patched with the real sizes in [finish].
 */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int = 1) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val bitsPerSample = 16

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // placeholder, patched in finish()
    }

    /** Appends [len] samples from [buf]. */
    fun writeFrame(buf: ShortArray, len: Int) {
        val bytes = ByteArray(len * 2)
        var i = 0
        var j = 0
        while (i < len) {
            val s = buf[i].toInt()
            bytes[j] = (s and 0xFF).toByte()
            bytes[j + 1] = ((s shr 8) and 0xFF).toByte()
            i++
            j += 2
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    fun finish() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        fun putStr(off: Int, s: String) {
            for (k in s.indices) header[off + k] = s[k].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        putStr(0, "RIFF")
        putInt(4, (36 + dataBytes).toInt())
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putStr(36, "data")
        putInt(40, dataBytes.toInt())

        raf.seek(0)
        raf.write(header)
        raf.close()
    }
}
