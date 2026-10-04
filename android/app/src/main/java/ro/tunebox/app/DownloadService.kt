package ro.tunebox.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Ține descărcările pornite și când aplicația nu e pe ecran
class DownloadService : Service() {
    companion object {
        const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // folosite doar pe firul principal
    private var running = false
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedTotal = 0
    private var failedTotal = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Se pregătește…", null, 0f), type)
        lastStartId = startId
        if (!running) {
            running = true
            scope.launch { work() }
        }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TuneBox:download")
            .apply { acquire(2 * 60 * 60 * 1000L) }
        try {
            val engine = Downloader.awaitEngine()
            if (engine is Engine.Failed) {
                Downloader.failQueued("Motorul de descărcare nu a pornit: ${engine.message}")
            } else {
                while (true) {
                    val job = Downloader.nextQueued() ?: break
                    Downloader.run(this, job) { show(it) }
                    Downloader.current(job.id)?.let {
                        savedTotal += it.saved
                        if (it.status == Status.ERROR) failedTotal++
                    }
                }
            }
        } finally {
            wakeLock?.takeIf { it.isHeld }?.release()
            // decidem pe firul principal, ca să nu pierdem un link adăugat chiar acum
            withContext(NonCancellable + Dispatchers.Main) {
                running = false
                if (Downloader.hasQueued() && Downloader.engine.value is Engine.Ready) {
                    running = true
                    scope.launch { work() }
                } else {
                    finish()
                }
            }
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (savedTotal > 0 || failedTotal > 0) {
            val text = buildString {
                if (savedTotal > 0) append("$savedTotal ${if (savedTotal == 1) "piesă salvată" else "piese salvate"} în Music/TuneBox")
                if (failedTotal > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("$failedTotal ${if (failedTotal == 1) "eroare" else "erori"}")
                }
            }
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Descărcare terminată")
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            notify(DONE_ID, n)
        }
        savedTotal = 0
        failedTotal = 0
        stopSelfResult(lastStartId)
    }

    private fun show(job: Job) {
        val state = when (job.status) {
            Status.CONVERTING -> "Conversie în MP3…"
            Status.SAVING -> "Se salvează…"
            else -> "Se descarcă" + (if (job.item.isNotEmpty()) " · piesa ${job.item}" else "") + " · ${job.progress.toInt()}%"
        }
        notify(NOTIF_ID, notification(job.title.ifBlank { job.url }, state, job.progress))
    }

    private fun notify(id: Int, n: Notification) {
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(id, n) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(title: String, text: String?, progress: Float): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(100, progress.toInt(), text == null)
            .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
