package ro.tunebox.app

import android.content.ContentUris
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

enum class Source { LOCAL, DRIVE }

data class Track(
    val id: String,
    val uri: Uri,
    val name: String,
    val size: Long,
    val added: Long,
    val source: Source,
    val file: File? = null,
    val driveId: String? = null,
) {
    val title get() = name.substringBeforeLast('.')
}

// Piesele stau în Music/TuneBox, unde le vede orice player de muzică
object Library {
    private const val FOLDER = "TuneBox"
    private val relativePath = "${Environment.DIRECTORY_MUSIC}/$FOLDER/"

    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma")

    /** Permisiunea de citire a muzicii (ca să vedem și piesele care nu au fost create de aplicație). */
    val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasReadPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, readPermission) == PackageManager.PERMISSION_GRANTED

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "audio/mpeg"

    /** Copie temporară a unei piese de pe telefon (pentru încărcare în Drive sau trimitere). */
    fun copyToTemp(context: Context, track: Track, dir: File): File {
        dir.mkdirs()
        val f = File(dir, track.name)
        val input = track.file?.inputStream() ?: context.contentResolver.openInputStream(track.uri)
            ?: throw java.io.IOException("Nu pot citi piesa de pe telefon.")
        input.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun legacyDir() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)

    fun save(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("no stream")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                return false
            }
            return true
        }

        val dir = legacyDir().apply { mkdirs() }
        var dest = File(dir, file.name)
        var n = 2
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        file.copyTo(dest)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("audio/mpeg"), null)
        return true
    }

    fun list(context: Context): List<Track> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
            )
            val tracks = mutableListOf<Track>()
            // fără permisiunea de citire, Android întoarce doar piesele create de aplicație
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'",
                arrayOf("$relativePath%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(id))
                    tracks += Track(
                        id = uri.toString(),
                        uri = uri,
                        name = c.getString(name) ?: "?",
                        size = c.getLong(size),
                        added = c.getLong(added),
                        source = Source.LOCAL,
                    )
                }
            }
            return tracks
        }

        val files = legacyDir().walkTopDown().filter { it.isFile && it.extension.lowercase() in audioExt }.toList()
        return files.sortedByDescending { it.lastModified() }.map {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", it)
            Track(
                id = uri.toString(),
                uri = uri,
                source = Source.LOCAL,
                name = it.name,
                size = it.length(),
                added = it.lastModified() / 1000,
                file = it,
            )
        }
    }

    fun delete(context: Context, track: Track): Boolean {
        track.file?.let { f ->
            val ok = f.delete()
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
            return ok
        }
        // pentru fișiere care nu sunt ale aplicației aruncă SecurityException → ecranul cere confirmarea Android
        return context.contentResolver.delete(track.uri, null, null) > 0
    }
}
