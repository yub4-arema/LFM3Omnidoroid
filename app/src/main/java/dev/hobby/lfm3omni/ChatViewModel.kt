package dev.hobby.lfm3omni

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { NeedDownload, Downloading, Loading, Ready, Error }
enum class MediaKind { Image, Audio }

data class Attachment(val file: File, val kind: MediaKind, val label: String)

class ChatMessage(val fromUser: Boolean, text: String, val attachments: List<Attachment> = emptyList()) {
    var text by mutableStateOf(text)
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ModelStore(app)
    private val engine = LfmEngine(app)
    private val recorder = WavRecorder()
    private val cacheDir = File(app.cacheDir, "media").apply { mkdirs() }

    var phase by mutableStateOf(if (store.isReady()) Phase.Loading else Phase.NeedDownload)
        private set
    var status by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var generating by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var systemPrompt by mutableStateOf("")

    val messages = mutableStateListOf<ChatMessage>()
    val pending = mutableStateListOf<Attachment>()

    private var job: Job? = null

    init { if (store.isReady()) loadModel() }

    fun download() {
        phase = Phase.Downloading
        job = viewModelScope.launch {
            try {
                store.download { done, total, name ->
                    progress = if (total > 0) done.toFloat() / total else 0f
                    status = "%.0f / %.0f MB".format(done / 1e6, total / 1e6)
                }
                loadModel()
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
                phase = Phase.Error
            }
        }
    }

    private fun loadModel() {
        phase = Phase.Loading
        status = "…"
        viewModelScope.launch {
            val rc = engine.load(store.model.absolutePath, store.mmproj.absolutePath)
            if (rc == 0) phase = Phase.Ready else {
                status = "Failed to load model (code $rc)"
                phase = Phase.Error
            }
        }
    }

    fun retry() { if (store.isReady()) loadModel() else download() }

    fun attachImage(uri: Uri) = attach(uri, MediaKind.Image, "jpg")
    fun attachAudio(uri: Uri) = attach(uri, MediaKind.Audio, "audio")

    private fun attach(uri: Uri, kind: MediaKind, ext: String) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val out = File(cacheDir, "${System.nanoTime()}.$ext")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
                out
            }
            pending += Attachment(f, kind, if (kind == MediaKind.Image) "Image" else "Audio file")
        }
    }

    fun removePending(a: Attachment) { pending.remove(a) }

    fun toggleRecording(): Boolean {
        if (!recording) {
            recorder.start()
            recording = true
        } else {
            recording = false
            val f = File(cacheDir, "${System.nanoTime()}.wav")
            val sec = recorder.stop(f)
            pending += Attachment(f, MediaKind.Audio, "Recording %.1fs".format(sec))
        }
        return recording
    }

    fun send(text: String) {
        if (generating || phase != Phase.Ready) return
        val atts = pending.toList()
        if (text.isBlank() && atts.isEmpty()) return
        // Audio-only turn: ask for a transcript, which is what the model is trained for.
        val prompt = if (text.isBlank() && atts.all { it.kind == MediaKind.Audio }) "Perform ASR." else text
        pending.clear()
        messages += ChatMessage(true, text, atts)
        val reply = ChatMessage(false, "")
        messages += reply
        generating = true
        job = viewModelScope.launch {
            val rc = engine.generate(systemPrompt, prompt, atts.map { it.file.absolutePath }) { piece ->
                reply.text += piece
            }
            if (rc != 0) reply.text += "\n[error $rc]"
            generating = false
        }
    }

    fun stop() = engine.stop()

    fun newChat() {
        if (generating) return
        messages.clear()
        viewModelScope.launch { engine.reset() }
    }

    override fun onCleared() {
        engine.stop()
    }
}
