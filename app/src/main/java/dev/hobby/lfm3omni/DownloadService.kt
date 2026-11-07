package dev.hobby.lfm3omni

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Progress shared between the download service and the UI. */
object DownloadState {
    data class S(
        val running: Boolean = false,
        val done: Long = 0,
        val total: Long = 0,
        val finished: Boolean = false,
        val error: String? = null,
    )

    val flow = MutableStateFlow(S())
}

/** Foreground service so the model download keeps going while the app is in the background. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(
            NOTIF_ID, notification("Preparing…", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_NOT_STICKY

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lfm3omni:download")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        DownloadState.flow.value = DownloadState.S(running = true)
        val nm = getSystemService(NotificationManager::class.java)
        job = scope.launch {
            try {
                ModelStore(applicationContext).download { done, total, _ ->
                    DownloadState.flow.value = DownloadState.S(running = true, done = done, total = total)
                    nm.notify(NOTIF_ID, notification("%.0f / %.0f MB".format(done / 1e6, total / 1e6), done, total))
                }
                DownloadState.flow.value = DownloadState.S(finished = true)
                nm.notify(DONE_ID, doneNotification("Download complete"))
            } catch (e: Exception) {
                DownloadState.flow.value = DownloadState.S(error = e.message ?: e.javaClass.simpleName)
                nm.notify(DONE_ID, doneNotification("Download failed: ${e.message}"))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, done: Long, total: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .build()

    private fun doneNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("LFM3 Omni")
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val CHANNEL = "download"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
