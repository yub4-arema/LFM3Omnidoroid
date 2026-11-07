package dev.hobby.lfm3omni

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

fun interface TokenListener {
    fun onToken(piece: String)
}

/** Thin wrapper around the native llama.cpp + mtmd bridge. All native work runs on one thread. */
class LfmEngine(private val context: Context) {
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "lfm-native", 16L * 1024 * 1024)
    }.asCoroutineDispatcher()

    @Volatile var loaded = false
        private set

    private external fun nativeInit(libDir: String)
    private external fun nativeLoad(model: String, mmproj: String, nCtx: Int, nThreads: Int): Int
    private external fun nativeUnload()
    private external fun nativeReset()
    private external fun nativeStop()
    private external fun nativeGenerate(
        system: String, text: String, media: Array<String>, maxTokens: Int, cb: TokenListener,
    ): Int

    suspend fun load(model: String, mmproj: String, nCtx: Int = 8192): Int = withContext(dispatcher) {
        nativeInit(context.applicationInfo.nativeLibraryDir)
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val rc = nativeLoad(model, mmproj, nCtx, threads)
        loaded = rc == 0
        rc
    }

    suspend fun generate(
        system: String, text: String, media: List<String>, maxTokens: Int = 1024, listener: TokenListener,
    ): Int = withContext(dispatcher) {
        nativeGenerate(system, text, media.toTypedArray(), maxTokens, listener)
    }

    /** Safe to call from any thread. */
    fun stop() = nativeStop()

    suspend fun reset() = withContext(dispatcher) { nativeReset() }

    suspend fun unload() = withContext(dispatcher) { nativeUnload(); loaded = false }

    companion object {
        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("lfm3omni")
        }
    }
}
