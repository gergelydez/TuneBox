package ro.tunebox.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DriveState(val connected: Boolean = false, val email: String = "")

class DriveAuthException(message: String) : IOException(message)

/**
 * Google Drive prin REST. Folosim permisiunea „drive.file”: aplicația vede doar fișierele create de ea
 * (de pe orice telefon pe care e instalată), nu restul Drive-ului.
 */
object Drive {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    const val FOLDER_NAME = "TuneBox"
    private const val TOKEN_TTL_MS = 40L * 60 * 1000

    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        _state.value = DriveState(p.getBoolean("connected", false), p.getString("email", "") ?: "")
    }

    val connected get() = _state.value.connected

    fun streamUri(id: String): Uri = Uri.parse("$API/files/$id?alt=media")

    fun folderUrl(ctx: Context): String? =
        prefs(ctx).getString("folder", null)?.let { "https://drive.google.com/drive/folders/$it" }

    // ---- autentificare ----

    /** Pornește conectarea din activitate: fie cere fereastra Google (needsUi), fie e gata direct. */
    fun connect(activity: Activity, needsUi: (PendingIntent) -> Unit, done: (Result<Unit>) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) needsUi(pi)
                else {
                    val t = r.accessToken
                    if (t == null) done(Result.failure(IOException("Google nu a dat acces.")))
                    else finishConnect(activity, t, done)
                }
            }
            .addOnFailureListener { done(Result.failure(friendly(it))) }
    }

    /** Rezultatul ferestrei Google. */
    fun onConnectResult(activity: Activity, data: Intent?, done: (Result<Unit>) -> Unit) {
        try {
            val r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            val t = r.accessToken ?: return done(Result.failure(IOException("Google nu a dat acces.")))
            finishConnect(activity, t, done)
        } catch (e: Exception) {
            done(Result.failure(friendly(e)))
        }
    }

    private fun finishConnect(ctx: Context, t: String, done: (Result<Unit>) -> Unit) {
        setToken(t)
        Thread {
            val res = runCatching {
                val about = JSONObject(call(ctx, "GET", "$API/about?fields=user(emailAddress)"))
                val email = about.optJSONObject("user")?.optString("emailAddress").orEmpty()
                prefs(ctx).edit().putBoolean("connected", true).putString("email", email).apply()
                _state.value = DriveState(true, email)
                folderId(ctx)
                Unit
            }
            done(res)
        }.start()
    }

    fun disconnect(ctx: Context) {
        val t = token
        token = null
        prefs(ctx).edit().clear().apply()
        _state.value = DriveState()
        if (t != null) Thread { runCatching { GoogleAuthUtil.clearToken(ctx, t) } }.start()
    }

    private fun setToken(t: String) {
        token = t
        tokenAt = System.currentTimeMillis()
    }

    /** Token valid, obținut în fundal (fără ferestre). Nu se apelează pe firul principal. */
    fun token(ctx: Context): String {
        token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS }?.let { return it }
        if (!connected) throw DriveAuthException("Google Drive nu este conectat.")
        val r = try {
            Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw DriveAuthException(friendly(e).message ?: "Nu am putut accesa Google Drive.")
        }
        if (r.hasResolution()) throw DriveAuthException("Reconectează Google Drive din aplicație.")
        val t = r.accessToken ?: throw DriveAuthException("Reconectează Google Drive din aplicație.")
        setToken(t)
        return t
    }

    private fun invalidate(ctx: Context, t: String) {
        token = null
        runCatching { GoogleAuthUtil.clearToken(ctx, t) }
    }

    fun friendly(e: Throwable): Exception {
        val code = (e as? ApiException)?.statusCode ?: (e.cause as? ApiException)?.statusCode
        return when (code) {
            10 -> IOException(
                "Google nu recunoaște aplicația. În Google Cloud, clientul OAuth de tip Android trebuie să aibă " +
                    "pachetul ro.tunebox.app și amprenta SHA-1 din meniul ⋮ → Despre."
            )
            7 -> IOException("Fără internet.")
            12501, 16 -> IOException("Conectare anulată.")
            else -> (e as? Exception) ?: IOException(e.message)
        }
    }

    // ---- REST ----

    private fun call(
        ctx: Context, method: String, url: String, body: String? = null, override: String? = null, retry: Boolean = true,
    ): String {
        val t = token(ctx)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $t")
            override?.let { c.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code == 401 && retry) {
                invalidate(ctx, t)
                return call(ctx, method, url, body, override, false)
            }
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IOException(apiError(text, code))
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun apiError(text: String, code: Int): String {
        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
        return when {
            msg?.contains("has not been used", true) == true || msg?.contains("is disabled", true) == true ->
                "Google Drive API nu e activat în proiectul Google Cloud."
            code == 403 && msg?.contains("storage", true) == true -> "Spațiul din Google Drive e plin."
            msg != null -> "Drive: $msg"
            else -> "Drive: eroare $code"
        }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Folderul TuneBox (îl caută sau îl creează). */
    fun folderId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("folder", null)?.let { id ->
            val ok = runCatching {
                !JSONObject(call(ctx, "GET", "$API/files/$id?fields=id,trashed")).optBoolean("trashed")
            }.getOrDefault(false)
            if (ok) return id
        }
        val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = JSONObject(call(ctx, "GET", "$API/files?q=${q(query)}&fields=files(id)&spaces=drive"))
            .getJSONArray("files")
        val id = if (found.length() > 0) found.getJSONObject(0).getString("id") else {
            val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", "application/vnd.google-apps.folder")
            JSONObject(call(ctx, "POST", "$API/files?fields=id", meta.toString())).getString("id")
        }
        p.edit().putString("folder", id).apply()
        return id
    }

    fun list(ctx: Context): List<Track> {
        val folder = folderId(ctx)
        val query = "'$folder' in parents and trashed = false and mimeType contains 'audio/'"
        val out = mutableListOf<Track>()
        var page: String? = null
        do {
            val url = "$API/files?q=${q(query)}&orderBy=createdTime desc&pageSize=1000&spaces=drive" +
                "&fields=nextPageToken,files(id,name,size,createdTime)" + (page?.let { "&pageToken=${q(it)}" } ?: "")
            val json = JSONObject(call(ctx, "GET", url.replace(" ", "%20")))
            val files = json.getJSONArray("files")
            for (i in 0 until files.length()) out += files.getJSONObject(i).toTrack()
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    private fun JSONObject.toTrack(): Track {
        val id = getString("id")
        return Track(
            id = "drive:$id",
            driveId = id,
            uri = streamUri(id),
            name = optString("name"),
            size = optString("size").toLongOrNull() ?: 0,
            added = parseTime(optString("createdTime")),
            source = Source.DRIVE,
        )
    }

    private fun parseTime(s: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))!!.time / 1000
    }.getOrDefault(0)

    /** Încarcă un MP3 în folderul TuneBox (încărcare „resumable”). */
    fun upload(ctx: Context, file: File, name: String = file.name): Track {
        val folder = folderId(ctx)
        fun attempt(retry: Boolean): Track {
            val t = token(ctx)
            val init = URL("$UPLOAD/files?uploadType=resumable&fields=id,name,size,createdTime")
                .openConnection() as HttpURLConnection
            val location = try {
                init.requestMethod = "POST"
                init.doOutput = true
                init.setRequestProperty("Authorization", "Bearer $t")
                init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                init.setRequestProperty("X-Upload-Content-Type", "audio/mpeg")
                init.setRequestProperty("X-Upload-Content-Length", file.length().toString())
                val meta = JSONObject().put("name", name).put("mimeType", "audio/mpeg")
                    .put("parents", JSONArray().put(folder))
                init.outputStream.use { it.write(meta.toString().toByteArray()) }
                val code = init.responseCode
                if (code == 401 && retry) {
                    invalidate(ctx, t)
                    return attempt(false)
                }
                if (code >= 400) throw IOException(apiError(init.errorStream?.bufferedReader()?.readText().orEmpty(), code))
                init.getHeaderField("Location") ?: throw IOException("Drive nu a pornit încărcarea.")
            } finally {
                init.disconnect()
            }

            val put = URL(location).openConnection() as HttpURLConnection
            try {
                put.requestMethod = "PUT"
                put.doOutput = true
                put.connectTimeout = 20_000
                put.readTimeout = 120_000
                put.setFixedLengthStreamingMode(file.length())
                put.setRequestProperty("Content-Type", "audio/mpeg")
                file.inputStream().use { input -> put.outputStream.use { input.copyTo(it) } }
                val code = put.responseCode
                val text = (if (code < 400) put.inputStream else put.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code >= 400) throw IOException(apiError(text, code))
                return JSONObject(text).toTrack()
            } finally {
                put.disconnect()
            }
        }
        return attempt(true)
    }

    /** Mută în coșul de gunoi din Drive (se poate recupera 30 de zile). */
    fun trash(ctx: Context, driveId: String) {
        call(ctx, "POST", "$API/files/$driveId?fields=id", JSONObject().put("trashed", true).toString(), override = "PATCH")
    }

    /** Descarcă un fișier din Drive (pentru „Salvează pe telefon”). */
    fun download(ctx: Context, driveId: String, dest: File) {
        fun attempt(retry: Boolean) {
            val t = token(ctx)
            val c = URL("$API/files/$driveId?alt=media").openConnection() as HttpURLConnection
            try {
                c.setRequestProperty("Authorization", "Bearer $t")
                c.readTimeout = 120_000
                val code = c.responseCode
                if (code == 401 && retry) {
                    invalidate(ctx, t)
                    return attempt(false)
                }
                if (code >= 400) throw IOException(apiError(c.errorStream?.bufferedReader()?.readText().orEmpty(), code))
                c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            } finally {
                c.disconnect()
            }
        }
        attempt(true)
    }

    // ---- lista păstrată local, ca biblioteca să apară imediat și fără internet ----

    fun saveCache(ctx: Context, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach {
            arr.put(JSONObject().put("id", it.driveId).put("name", it.name).put("size", it.size).put("added", it.added))
        }
        runCatching { File(ctx.filesDir, "drive.json").writeText(arr.toString()) }
    }

    fun loadCache(ctx: Context): List<Track> = runCatching {
        val arr = JSONArray(File(ctx.filesDir, "drive.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            Track(
                id = "drive:$id", driveId = id, uri = streamUri(id), name = o.getString("name"),
                size = o.optLong("size"), added = o.optLong("added"), source = Source.DRIVE,
            )
        }
    }.getOrDefault(emptyList())
}
