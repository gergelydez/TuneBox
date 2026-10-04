package ro.tunebox.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

data class Track(val uri: Uri, val name: String, val size: Long, val added: Long, val file: File? = null)

// Piesele stau în Music/TuneBox, unde le vede orice player de muzică
object Library {
    private const val FOLDER = "TuneBox"
    private val relativePath = "${Environment.DIRECTORY_MUSIC}/$FOLDER/"

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
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(relativePath),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    tracks += Track(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                        name = c.getString(name) ?: "?",
                        size = c.getLong(size),
                        added = c.getLong(added),
                    )
                }
            }
            return tracks
        }

        val files = legacyDir().listFiles { f -> f.extension.equals("mp3", true) } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map {
            Track(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.files", it),
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
        return runCatching { context.contentResolver.delete(track.uri, null, null) > 0 }.getOrDefault(false)
    }
}
