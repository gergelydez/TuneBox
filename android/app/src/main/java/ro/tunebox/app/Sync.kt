package ro.tunebox.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.Normalizer

data class SyncState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val uploaded: Int = 0,
    val failed: Int = 0,
    val error: String = "",
    val finishedAt: Long = 0,
)

/** Urcă în Drive piesele care sunt doar pe telefon. */
object Sync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    /** Cheia după care recunoaștem aceeași piesă pe telefon și în Drive (numele, fără extensie și diacritice). */
    fun key(name: String): String =
        Normalizer.normalize(name.substringBeforeLast('.').lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('｜', '|')
            .replace(Regex("\\s+\\(\\d+\\)$"), "") // „piesă (1)” = copie făcută de Android
            .trim()

    /** Aceeași piesă în același folder (pe telefon și în Drive). */
    fun trackKey(t: Track): String = Library.cleanFolder(t.folder).lowercase() + "/" + key(t.name)

    fun autoEnabled(ctx: Context) = ctx.getSharedPreferences("tunebox", Context.MODE_PRIVATE).getBoolean("auto_sync", false)
    fun setAuto(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("tunebox", Context.MODE_PRIVATE).edit().putBoolean("auto_sync", on).apply()

    fun upload(context: Context, tracks: List<Track>) {
        val ctx = context.applicationContext
        if (_state.value.running || tracks.isEmpty()) return
        _state.value = SyncState(running = true, total = tracks.size)
        scope.launch {
            var uploaded = 0
            var failed = 0
            var error = ""
            val tmp = File(ctx.cacheDir, "sync")
            for ((i, t) in tracks.withIndex()) {
                _state.update { it.copy(done = i, current = t.title) }
                try {
                    val f = Library.copyToTemp(ctx, t, tmp)
                    try {
                        Drive.upload(ctx, f, t.name, t.folder)
                        uploaded++
                    } finally {
                        f.delete()
                    }
                } catch (e: Exception) {
                    failed++
                    error = e.message ?: "Încărcarea a eșuat."
                    if (e is DriveAuthException) break // fără acces la Drive n-are rost să continuăm
                }
            }
            tmp.deleteRecursively()
            _state.value = SyncState(
                running = false, done = tracks.size, total = tracks.size, uploaded = uploaded,
                failed = failed, error = error, finishedAt = System.currentTimeMillis(),
            )
            if (uploaded > 0) Downloader.libraryChanged()
        }
    }
}
