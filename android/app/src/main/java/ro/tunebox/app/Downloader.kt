package ro.tunebox.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

enum class Status { QUEUED, RUNNING, CONVERTING, SAVING, UPLOADING, DONE, ERROR, CANCELED }

data class Job(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val quality: Int,
    val playlist: Boolean,
    val toDrive: Boolean = false,
    val keepLocal: Boolean = true,
    val folder: String = "",
    val status: Status = Status.QUEUED,
    val progress: Float = 0f, // 0..100
    val title: String = "",
    val item: String = "",
    val saved: Int = 0,
    val uploaded: Int = 0,
    val error: String = "",
    val warning: String = "",
) {
    val finished get() = status == Status.DONE || status == Status.ERROR || status == Status.CANCELED
}

sealed interface Engine {
    data object Loading : Engine
    data object Ready : Engine
    data class Failed(val message: String) : Engine
}

// Starea aplicației: motorul yt-dlp și lista de descărcări
object Downloader {
    private val _engine = MutableStateFlow<Engine>(Engine.Loading)
    val engine: StateFlow<Engine> = _engine

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    // crește de fiecare dată când se salvează piese noi, ca biblioteca să se reîncarce
    private val _libraryVersion = MutableStateFlow(0)
    val libraryVersion: StateFlow<Int> = _libraryVersion

    private const val UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000

    fun init(context: Context) {
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            _engine.value = Engine.Ready
        } catch (e: Throwable) {
            _engine.value = Engine.Failed(e.message ?: e.toString())
            return
        }
        // YouTube se schimbă des: actualizăm yt-dlp singuri din când în când
        val prefs = context.getSharedPreferences("tunebox", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_checked", 0)
        if (System.currentTimeMillis() - last > UPDATE_EVERY_MS) {
            runCatching { updateYtDlp(context) }
        }
    }

    suspend fun awaitEngine(): Engine = engine.first { it != Engine.Loading }

    /** Actualizează yt-dlp; întoarce versiunea nouă sau null dacă era deja la zi. */
    fun updateYtDlp(context: Context): String? {
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        context.getSharedPreferences("tunebox", Context.MODE_PRIVATE).edit()
            .putLong("ytdlp_checked", System.currentTimeMillis()).apply()
        return if (status == YoutubeDL.UpdateStatus.DONE) ytDlpVersion(context) else null
    }

    fun ytDlpVersion(context: Context): String =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() ?: "?"

    fun enqueue(urls: List<String>, quality: Int, playlist: Boolean, toDrive: Boolean, keepLocal: Boolean, folder: String) {
        val new = urls.map {
            Job(
                url = it, quality = quality, playlist = playlist, toDrive = toDrive,
                keepLocal = keepLocal || !toDrive, folder = folder,
            )
        }
        _jobs.update { new.reversed() + it }
    }

    fun nextQueued(): Job? = _jobs.value.lastOrNull { it.status == Status.QUEUED }

    fun hasQueued(): Boolean = _jobs.value.any { it.status == Status.QUEUED }

    fun failQueued(message: String) = _jobs.update { list ->
        list.map { if (it.status == Status.QUEUED) it.copy(status = Status.ERROR, error = message) else it }
    }

    fun clearFinished() = _jobs.update { list -> list.filterNot { it.finished } }

    fun cancel(id: String) {
        val job = _jobs.value.find { it.id == id } ?: return
        if (job.status == Status.QUEUED) {
            update(id) { it.copy(status = Status.CANCELED) }
        } else if (!job.finished) {
            YoutubeDL.getInstance().destroyProcessById(id)
        }
    }

    private fun update(id: String, f: (Job) -> Job) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun libraryChanged() = _libraryVersion.update { it + 1 }

    private val itemRegex = Regex("""Downloading item (\d+) of (\d+)""")
    private val destRegex = Regex("""^\[download] Destination: (.+)$""")

    /** Descarcă o lucrare (rulează pe un fir de fundal). */
    fun run(context: Context, job: Job, onProgress: (Job) -> Unit) {
        val dir = File(context.cacheDir, "dl/${job.id}").apply { deleteRecursively(); mkdirs() }
        update(job.id) { it.copy(status = Status.RUNNING) }

        val request = YoutubeDLRequest(job.url).apply {
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "${job.quality}K")
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption(if (job.playlist) "--yes-playlist" else "--no-playlist")
            if (job.playlist) addOption("--ignore-errors")
            addOption("--trim-filenames", "120")
            addOption("--no-mtime")
            addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
        }

        var index = 0
        var total = 0
        var lastEmit = 0L
        var error = ""
        var canceled = false

        try {
            YoutubeDL.getInstance().execute(request, job.id) { progress, _, line ->
                itemRegex.find(line)?.let {
                    index = it.groupValues[1].toInt()
                    total = it.groupValues[2].toInt()
                }
                destRegex.find(line)?.let { m ->
                    val title = File(m.groupValues[1]).nameWithoutExtension
                    update(job.id) { it.copy(title = title) }
                }
                if (line.startsWith("[ExtractAudio]")) update(job.id) { it.copy(status = Status.CONVERTING) }
                else if (line.startsWith("[download]") && progress > 0) update(job.id) { it.copy(status = Status.RUNNING) }

                val p = progress.coerceIn(0f, 100f)
                val overall = if (total > 1 && index > 0) ((index - 1) + p / 100f) / total * 100f else p
                update(job.id) {
                    it.copy(progress = overall.coerceAtMost(99f), item = if (total > 1) "$index/$total" else "")
                }
                val now = System.currentTimeMillis()
                if (now - lastEmit > 500) {
                    lastEmit = now
                    current(job.id)?.let(onProgress)
                }
            }
        } catch (e: YoutubeDL.CanceledException) {
            canceled = true
        } catch (e: YoutubeDLException) {
            error = lastError(e.message)
        } catch (e: InterruptedException) {
            canceled = true
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }

        // salvăm tot ce s-a descărcat, chiar dacă unele piese din playlist au eșuat
        val mp3s = dir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }?.sortedBy { it.lastModified() }
            ?: emptyList()
        var saved = 0
        var uploaded = 0
        var driveError = ""
        for (f in mp3s) {
            // întâi în Drive (dacă e cerut), apoi pe telefon dacă vrei copie sau dacă Drive a eșuat
            var inDrive = false
            if (job.toDrive) {
                update(job.id) { it.copy(status = Status.UPLOADING) }
                current(job.id)?.let(onProgress)
                try {
                    Drive.upload(context, f, subfolder = job.folder)
                    inDrive = true
                    uploaded++
                } catch (e: Exception) {
                    driveError = e.message ?: "Încărcarea în Drive a eșuat."
                }
            }
            if (job.keepLocal || !inDrive) {
                update(job.id) { it.copy(status = Status.SAVING) }
                if (runCatching { Library.save(context, f, job.folder) }.getOrDefault(false)) saved++
            }
        }
        if (saved > 0 || uploaded > 0) libraryChanged()
        dir.deleteRecursively()

        update(job.id) {
            when {
                canceled && saved == 0 && uploaded == 0 -> it.copy(status = Status.CANCELED)
                saved == 0 && uploaded == 0 -> it.copy(
                    status = Status.ERROR,
                    error = error.ifBlank { if (mp3s.isEmpty()) "Nu s-a descărcat nimic." else "Nu am putut salva în Music." },
                )
                else -> it.copy(
                    status = Status.DONE,
                    progress = 100f,
                    saved = saved,
                    uploaded = uploaded,
                    title = when {
                        mp3s.size > 1 -> "${mp3s.first().nameWithoutExtension} și încă ${mp3s.size - 1}"
                        it.title.isBlank() -> mp3s.first().nameWithoutExtension
                        else -> it.title
                    },
                    warning = when {
                        driveError.isNotBlank() -> "$driveError Piesele au rămas pe telefon."
                        error.isNotBlank() -> "Unele piese nu s-au putut descărca."
                        else -> ""
                    },
                )
            }
        }
        current(job.id)?.let(onProgress)
    }

    fun current(id: String): Job? = _jobs.value.find { it.id == id }

    private fun lastError(message: String?): String {
        val lines = (message ?: "").lines().filter { it.contains("ERROR") }
        val line = (lines.lastOrNull() ?: message?.lines()?.lastOrNull { it.isNotBlank() } ?: "")
            .replace(Regex("^ERROR:\\s*"), "")
        return when {
            line.contains("Sign in to confirm", true) -> "YouTube cere confirmare că nu ești robot. Încearcă mai târziu sau din altă rețea."
            line.contains("Unable to download", true) || line.contains("Failed to resolve", true) ->
                "Nu am putut accesa linkul. Verifică internetul."
            line.contains("Unsupported URL", true) -> "Linkul nu e suportat."
            line.contains("Video unavailable", true) || line.contains("Private video", true) -> "Videoclipul nu e disponibil."
            line.isBlank() -> "Descărcarea a eșuat."
            else -> line.take(300)
        }
    }
}
