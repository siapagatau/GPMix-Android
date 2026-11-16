package com.gpaudio.gpmix.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parser WAV minimal: PCM 16/24/32-bit dan float32. Hasil: float interleaved. */
object WavLoader {
    class Sample(val data: FloatArray, val channels: Int, val rate: Int)

    fun parse(b: ByteArray): Sample {
        require(b.size > 44 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(b, 8, 4, Charsets.US_ASCII) == "WAVE") { "Bukan file WAV (hanya WAV yang didukung)" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var fmt = 0; var ch = 0; var rate = 0; var bits = 0
        var dStart = -1; var dLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (len < 0) break
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                fmt = bb.getShort(body).toInt() and 0xFFFF
                ch = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                dStart = body
                dLen = minOf(len, b.size - body)
                break
            }
            pos = body + len + (len and 1)
        }
        require(dStart >= 0 && ch > 0 && rate > 0) { "Header WAV tidak lengkap" }
        require(fmt == 1 || fmt == 3 || fmt == 0xFFFE) { "Format WAV terkompresi tidak didukung" }
        val bytes = bits / 8
        require(bytes in 2..4) { "Bit depth $bits tidak didukung" }
        val frames = dLen / (bytes * ch)
        require(frames > 0) { "WAV kosong" }
        val n = frames * ch
        val out = FloatArray(n)
        when {
            fmt == 3 && bits == 32 -> for (i in 0 until n) out[i] = bb.getFloat(dStart + i * 4)
            bits == 16 -> for (i in 0 until n) out[i] = bb.getShort(dStart + i * 2) / 32768f
            bits == 24 -> for (i in 0 until n) {
                val o = dStart + i * 3
                val v = (b[o + 2].toInt() shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
                out[i] = v / 8388608f
            }
            bits == 32 -> for (i in 0 until n) out[i] = bb.getInt(dStart + i * 4) / 2147483648f
            else -> throw IllegalArgumentException("Format WAV tidak didukung")
        }
        return Sample(out, ch, rate)
    }
}
