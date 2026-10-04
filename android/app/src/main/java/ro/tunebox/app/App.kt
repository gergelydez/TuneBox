package ro.tunebox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(DownloadService.CHANNEL, "Descărcări", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        Drive.load(this)
        // la prima pornire despachetează Python + ffmpeg (durează câteva secunde)
        thread(name = "ytdlp-init") { Downloader.init(this) }
    }
}
