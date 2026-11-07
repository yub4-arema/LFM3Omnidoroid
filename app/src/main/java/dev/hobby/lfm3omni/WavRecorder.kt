package dev.hobby.lfm3omni

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records 16 kHz mono 16-bit PCM (what LFM3-Omni expects) into a WAV file. */
class WavRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // checked by the caller
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE),
        )
        record = rec
        pcm.reset()
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(4096)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }.also { it.start() }
    }

    /** Stops recording and writes [out]. Returns the duration in seconds. */
    fun stop(out: File): Float {
        running = false
        thread?.join()
        record?.run { stop(); release() }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        writeWav(out, data)
        return data.size / 2f / RATE
    }

    private fun writeWav(out: File, data: ByteArray) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        out.outputStream().use { it.write(h.array()); it.write(data) }
    }

    companion object { const val RATE = 16_000 }
}
