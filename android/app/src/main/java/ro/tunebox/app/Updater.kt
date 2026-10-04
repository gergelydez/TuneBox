package ro.tunebox.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val code: Long, val name: String, val notes: String, val url: String)

/**
 * Actualizări din release-ul „android” de pe GitHub. Workflow-ul publică acolo version.json + APK-urile.
 * Instalarea trece prin PackageInstaller: prima dată Android cere confirmare, apoi (Android 12+)
 * aplicația se poate actualiza singură, fiind ea cea care s-a instalat.
 */
object Updater {
    private const val BASE = "https://github.com/gergelydez/TuneBox/releases/download/android"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    fun installedCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tunebox", Context.MODE_PRIVATE)

    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("auto_update", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_update", on).apply()

    fun dueForCheck(ctx: Context) =
        System.currentTimeMillis() - prefs(ctx).getLong("update_checked", 0) > CHECK_EVERY_MS

    /** Întoarce versiunea nouă, dacă există. Rulează pe un fir de fundal. */
    fun check(ctx: Context): UpdateInfo? {
        val json = JSONObject(get("$BASE/version.json?t=${System.currentTimeMillis()}"))
        prefs(ctx).edit().putLong("update_checked", System.currentTimeMillis()).apply()
        val code = json.getLong("versionCode")
        if (code <= installedCode(ctx)) return null
        val is64 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        return UpdateInfo(
            code = code,
            name = json.optString("versionName", code.toString()),
            notes = json.optString("notes"),
            url = json.optString(if (is64) "apk" else "apk32").ifBlank { "$BASE/${if (is64) "TuneBox.apk" else "TuneBox-32bit.apk"}" },
        )
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            if (c.responseCode >= 400) throw IOException("Nu am putut verifica actualizările (${c.responseCode}).")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Descarcă APK-ul (cu progres 0..100). */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dest = File(ctx.cacheDir, "update.apk")
        val c = URL(info.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            if (c.responseCode >= 400) throw IOException("Descărcarea actualizării a eșuat (${c.responseCode}).")
            val total = c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Descărcarea actualizării s-a întrerupt.")
        } finally {
            c.disconnect()
        }
        return dest
    }

    /** Pornește instalarea. Rezultatul vine în InstallReceiver. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("TuneBox.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags,
            )
            session.commit(pending.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android cere confirmarea utilizatorului (prima dată sau pe Android < 12)
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // aplicația repornește în versiunea nouă
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // utilizatorul a anulat
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cod $status"
                Toast.makeText(context, "Actualizarea nu s-a instalat: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
