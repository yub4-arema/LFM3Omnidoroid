package dev.hobby.lfm3omni

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the GGUF model + multimodal projector from Hugging Face into app-private storage. */
class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    val model = File(dir, MODEL_FILE)
    val mmproj = File(dir, MMPROJ_FILE)

    fun isReady() = model.length() > 0 && mmproj.length() > 0

    /** [onProgress] gets (downloadedBytes, totalBytes, fileName) across both files. */
    suspend fun download(onProgress: (Long, Long, String) -> Unit) = withContext(Dispatchers.IO) {
        val targets = listOf(model, mmproj)
        val sizes = targets.map { remoteSize(it.name) }
        val total = sizes.sum()
        var done = 0L
        for ((i, f) in targets.withIndex()) {
            if (f.exists() && f.length() == sizes[i]) { done += sizes[i]; continue }
            val base = done
            fetch(f) { n -> onProgress(base + n, total, f.name) }
            done += sizes[i]
        }
    }

    private fun open(name: String, rangeFrom: Long = 0): HttpURLConnection {
        val c = URL("$BASE/$name").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        return c
    }

    private fun remoteSize(name: String): Long {
        val c = open(name)
        try {
            c.requestMethod = "HEAD"
            return c.contentLengthLong
        } finally { c.disconnect() }
    }

    /** Resumable download into "<file>.part", renamed on completion. */
    private fun fetch(dest: File, progress: (Long) -> Unit) {
        val part = File(dest.parentFile, dest.name + ".part")
        val start = if (part.exists()) part.length() else 0L
        val c = open(dest.name, start)
        try {
            val resumed = c.responseCode == 206
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} for ${dest.name}")
            var n = if (resumed) start else 0L
            java.io.FileOutputStream(part, resumed).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { progress(n); last = now }
                    }
                }
            }
            progress(n)
            if (!part.renameTo(dest)) error("rename failed")
        } finally { c.disconnect() }
    }

    companion object {
        const val MODEL_FILE = "LFM3-Omni-2B-Q4_K_M.gguf"
        const val MMPROJ_FILE = "mmproj-LFM3-Omni-2B-Q8_0.gguf"
        private const val BASE = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main"
    }
}
