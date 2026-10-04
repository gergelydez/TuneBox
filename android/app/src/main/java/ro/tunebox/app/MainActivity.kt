package ro.tunebox.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.tunebox.app.databinding.ActivityMainBinding
import ro.tunebox.app.databinding.ItemFileBinding
import ro.tunebox.app.databinding.ItemJobBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("tunebox", MODE_PRIVATE) }

    private var player: MediaPlayer? = null
    private var playing: Track? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false
    private var pendingStart = false

    private val qualityIds by lazy { mapOf(128 to b.q128.id, 192 to b.q192.id, 256 to b.q256.id, 320 to b.q320.id) }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStart) {
            pendingStart = false
            if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                toast("Fără acces la memorie nu pot salva piesele în Music")
            } else {
                startDownload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // setări păstrate
        b.quality.check(qualityIds[prefs.getInt("quality", 192)] ?: b.q192.id)
        b.playlist.isChecked = prefs.getBoolean("playlist", false)
        b.quality.addOnButtonCheckedListener { _, id, checked ->
            if (checked) prefs.edit().putInt("quality", quality(id)).apply()
        }
        b.playlist.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("playlist", on).apply() }

        b.paste.setOnClickListener { paste() }
        b.download.setOnClickListener { startDownload() }
        b.clearJobs.setOnClickListener { Downloader.clearFinished() }
        b.menu.setOnClickListener { showMenu(it) }
        setupPlayer()

        observe()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun quality(id: Int) = qualityIds.entries.firstOrNull { it.value == id }?.key ?: 192

    // „Distribuie → TuneBox” din YouTube: completăm linkul și pornim imediat
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        ).joinToString(" ")
        val links = extractLinks(text)
        intent.action = null
        if (links.isEmpty()) return toast("Nu am găsit niciun link")
        b.urls.setText(links.joinToString("\n"))
        startDownload()
    }

    private fun extractLinks(text: String) =
        Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')', '"', '\'') }.distinct().toList()

    private fun paste() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val links = extractLinks(text)
        if (links.isEmpty()) return toast("Nu am găsit niciun link în clipboard")
        val current = b.urls.text.toString().trim()
        b.urls.setText((if (current.isEmpty()) "" else "$current\n") + links.joinToString("\n"))
    }

    private fun startDownload() {
        val links = extractLinks(b.urls.text.toString())
        if (links.isEmpty()) return toast("Lipește cel puțin un link")

        // Android 9 și mai vechi: e nevoie de acces la memorie ca să scriem în Music
        val needStorage = Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        // Android 13+: notificarea cu progresul (întrebăm o singură dată, e opțională)
        val askNotif = Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) &&
            !prefs.getBoolean("asked_notif", false)
        if (needStorage || askNotif) {
            if (askNotif) prefs.edit().putBoolean("asked_notif", true).apply()
            pendingStart = true
            askPermissions.launch(
                listOfNotNull(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { needStorage },
                    Manifest.permission.POST_NOTIFICATIONS.takeIf { askNotif },
                ).toTypedArray()
            )
            return
        }

        Downloader.enqueue(links, quality(b.quality.checkedButtonId), b.playlist.isChecked)
        b.urls.setText("")
        DownloadService.start(this)
    }

    @OptIn(FlowPreview::class)
    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Downloader.jobs.sample(300).collect { renderJobs(it) } }
                launch { Downloader.libraryVersion.collect { loadLibrary() } }
                launch {
                    Downloader.engine.collect { e ->
                        b.engineStatus.visibility = if (e is Engine.Ready) View.GONE else View.VISIBLE
                        b.engineStatus.text = when (e) {
                            Engine.Loading -> "Se pregătește motorul de descărcare… (prima pornire durează mai mult)"
                            is Engine.Failed -> "Motorul de descărcare nu a pornit: ${e.message}"
                            Engine.Ready -> ""
                        }
                        b.engineStatus.setTextColor(getColor(if (e is Engine.Failed) R.color.err else R.color.muted))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderJobs(Downloader.jobs.value)
        loadLibrary()
    }

    private fun renderJobs(jobs: List<Job>) {
        b.jobsCard.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        b.jobs.removeAllViews()
        for (j in jobs.take(30)) {
            val row = ItemJobBinding.inflate(layoutInflater, b.jobs, false)
            row.title.text = j.title.ifBlank { j.url }
            row.progress.isIndeterminate = j.status == Status.CONVERTING || j.status == Status.SAVING ||
                (j.status == Status.RUNNING && j.progress <= 0f)
            row.progress.setProgressCompat(if (j.status == Status.ERROR) 1000 else (j.progress * 10).toInt(), false)
            row.progress.setIndicatorColor(
                getColor(
                    when (j.status) {
                        Status.DONE -> R.color.ok
                        Status.ERROR -> R.color.err
                        Status.CANCELED -> R.color.muted
                        else -> R.color.brand
                    }
                )
            )
            row.meta.text = when (j.status) {
                Status.QUEUED -> "În așteptare"
                Status.RUNNING -> "Se descarcă… ${j.progress.toInt()}%" + if (j.item.isNotEmpty()) " · piesa ${j.item}" else ""
                Status.CONVERTING -> "Conversie în MP3…"
                Status.SAVING -> "Se salvează în Music…"
                Status.DONE -> "Gata · ${j.saved} ${if (j.saved == 1) "fișier" else "fișiere"}"
                Status.ERROR -> "Eroare"
                Status.CANCELED -> "Anulat"
            }
            val msg = j.error.ifBlank { j.warning }
            row.error.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
            row.error.text = msg
            row.error.setTextColor(getColor(if (j.status == Status.ERROR) R.color.err else R.color.warn))
            row.cancel.visibility = if (j.finished) View.GONE else View.VISIBLE
            row.cancel.setOnClickListener { Downloader.cancel(j.id) }
            b.jobs.addView(row.root)
        }
    }

    private fun loadLibrary() {
        lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) { runCatching { Library.list(this@MainActivity) }.getOrDefault(emptyList()) }
            b.fileCount.text = if (tracks.isEmpty()) "" else tracks.size.toString()
            b.empty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
            b.files.removeAllViews()
            for (t in tracks) {
                val row = ItemFileBinding.inflate(layoutInflater, b.files, false)
                row.name.text = t.name.removeSuffix(".mp3")
                row.size.text = formatSize(t.size)
                row.play.setImageResource(if (playing?.uri == t.uri) R.drawable.ic_pause else R.drawable.ic_play)
                row.play.setOnClickListener { if (playing?.uri == t.uri) stopPlayer() else play(t) }
                row.share.setOnClickListener { share(t) }
                row.delete.setOnClickListener { confirmDelete(t) }
                b.files.addView(row.root)
            }
        }
    }

    private fun formatSize(bytes: Long) =
        if (bytes > 1024 * 1024) String.format("%.1f MB", bytes / 1024.0 / 1024.0) else "${bytes / 1024} KB"

    private fun share(t: Track) {
        val send = Intent(Intent.ACTION_SEND).setType("audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, t.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, t.name))
    }

    private fun confirmDelete(t: Track) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ștergi piesa?")
            .setMessage(t.name.removeSuffix(".mp3"))
            .setNegativeButton("Nu", null)
            .setPositiveButton("Șterge") { _, _ ->
                if (playing?.uri == t.uri) stopPlayer()
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { Library.delete(this@MainActivity, t) }
                    if (!ok) toast("Nu am putut șterge piesa")
                    loadLibrary()
                }
            }
            .show()
    }

    // ---- player simplu ----

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!seeking) runCatching { b.playerSeek.progress = p.currentPosition }
            handler.postDelayed(this, 500)
        }
    }

    private fun setupPlayer() {
        b.playerToggle.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.start()
            b.playerToggle.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        b.playerClose.setOnClickListener { stopPlayer() }
        b.playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                seeking = false
                player?.seekTo(s?.progress ?: 0)
            }
        })
    }

    private fun play(t: Track) {
        stopPlayer()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            p.setDataSource(this, t.uri)
            p.setOnCompletionListener { stopPlayer() }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            return toast("Nu pot reda piesa")
        }
        player = p
        playing = t
        b.player.visibility = View.VISIBLE
        b.playerName.text = t.name.removeSuffix(".mp3")
        b.playerSeek.max = p.duration
        b.playerToggle.setImageResource(R.drawable.ic_pause)
        handler.post(tick)
        loadLibrary()
    }

    private fun stopPlayer() {
        handler.removeCallbacks(tick)
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        if (playing != null) {
            playing = null
            loadLibrary()
        }
        b.player.visibility = View.GONE
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---- meniu ----

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Actualizează yt-dlp")
        menu.menu.add(0, 2, 1, "Versiune nouă a aplicației")
        menu.menu.add(0, 3, 2, "Despre")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> updateYtDlp()
                2 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
                3 -> about()
            }
            true
        }
        menu.show()
    }

    private fun updateYtDlp() {
        if (Downloader.engine.value !is Engine.Ready) return toast("Motorul încă se pregătește")
        toast("Se actualizează yt-dlp…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Downloader.updateYtDlp(applicationContext) } }
            result.fold(
                onSuccess = { v -> toast(if (v == null) "yt-dlp e deja la zi" else "yt-dlp actualizat: $v") },
                onFailure = { e -> toast("Actualizarea a eșuat: ${e.message}") },
            )
        }
    }

    private fun about() {
        lifecycleScope.launch {
            val ytdlp = withContext(Dispatchers.IO) { Downloader.ytDlpVersion(applicationContext) }
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("TuneBox $version")
                .setMessage(
                    "Descarcă audio în MP3 cu yt-dlp, direct pe telefon.\n\n" +
                        "yt-dlp: $ytdlp\nPiesele se salvează în Music/TuneBox.\n\n" +
                        "Folosește aplicația pentru conținut pe care ai dreptul să-l descarci."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val RELEASES_URL = "https://github.com/gergelydez/TuneBox/releases/tag/android"
    }
}
