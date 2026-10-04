package ro.tunebox.app

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.tunebox.app.databinding.ActivityMainBinding
import ro.tunebox.app.databinding.ItemJobBinding
import ro.tunebox.app.databinding.ItemTrackBinding
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("tunebox", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStart = false

    private var source = Source.DRIVE
    private var driveTracks: List<Track> = emptyList()
    private var localTracks: List<Track> = emptyList()
    private var shown: List<Track> = emptyList()
    private val adapter = TrackAdapter()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var seeking = false
    private var pendingUpdate: UpdateInfo? = null
    private var updating = false

    private val qualityIds by lazy { mapOf(128 to b.q128.id, 192 to b.q192.id, 256 to b.q256.id, 320 to b.q320.id) }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStart) {
            pendingStart = false
            if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                toast("Fără acces la memorie nu pot salva piesele pe telefon")
            } else {
                startDownload()
            }
        }
    }

    private val driveConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) Drive.onConnectResult(this, res.data) { onDriveConnected(it) }
        else toast("Conectare anulată")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupMusicPage()
        setupDownloadPage()
        setupPlayer()

        b.nav.setOnItemSelectedListener { item ->
            showPage(item.itemId == R.id.nav_music)
            true
        }
        b.menu.setOnClickListener { showMenu(it) }
        b.driveChip.setOnClickListener { if (Drive.connected) showDriveMenu(it) else connectDrive() }
        b.driveConnectBtn.setOnClickListener { connectDrive() }

        observe()
        if (intent?.action == Intent.ACTION_SEND) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun showPage(music: Boolean) {
        b.pageMusic.visibility = if (music) View.VISIBLE else View.GONE
        b.pageDownload.visibility = if (music) View.GONE else View.VISIBLE
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ================= Muzică =================

    private fun setupMusicPage() {
        source = if (prefs.getString("source", "drive") == "local") Source.LOCAL else Source.DRIVE
        b.sourceTabs.check(if (source == Source.DRIVE) b.tabDrive.id else b.tabLocal.id)
        b.sourceTabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            source = if (id == b.tabDrive.id) Source.DRIVE else Source.LOCAL
            prefs.edit().putString("source", if (source == Source.DRIVE) "drive" else "local").apply()
            renderLibrary()
        }
        b.tracks.layoutManager = LinearLayoutManager(this)
        b.tracks.adapter = adapter
        b.search.doAfterTextChanged { renderLibrary() }
        b.playAll.setOnClickListener { if (shown.isNotEmpty()) play(shown, 0, shuffle = false) }
        b.shuffleAll.setOnClickListener { if (shown.isNotEmpty()) play(shown, shown.indices.random(), shuffle = true) }
        b.refresh.setColorSchemeColors(getColor(R.color.brand))
        b.refresh.setOnRefreshListener { reloadLibrary(userAction = true) }
        driveTracks = Drive.loadCache(this)
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun renderLibrary() {
        val isDrive = source == Source.DRIVE
        val needsConnect = isDrive && !Drive.connected
        val all = if (isDrive) driveTracks else localTracks
        val q = normalize(b.search.text.toString().trim())
        shown = if (q.isEmpty()) all else all.filter { normalize(it.title).contains(q) }

        b.driveConnect.visibility = if (needsConnect) View.VISIBLE else View.GONE
        b.search.visibility = if (needsConnect || all.isEmpty()) View.GONE else View.VISIBLE
        b.actions.visibility = if (needsConnect || shown.isEmpty()) View.GONE else View.VISIBLE
        b.trackCount.text = "${shown.size} ${if (shown.size == 1) "piesă" else "piese"}"
        b.libEmpty.visibility = if (!needsConnect && shown.isEmpty()) View.VISIBLE else View.GONE
        b.libEmpty.text = when {
            all.isEmpty() && isDrive -> "Încă nu ai piese în Drive.\nDescarcă ceva din tabul Descarcă."
            all.isEmpty() -> "Nicio piesă pe telefon.\nPiesele păstrate pe telefon ajung în Music/TuneBox."
            else -> "Nicio piesă găsită."
        }
        adapter.submitList(shown)
    }

    private fun reloadLibrary(userAction: Boolean = false) {
        lifecycleScope.launch {
            localTracks = withContext(Dispatchers.IO) {
                runCatching { Library.list(this@MainActivity) }.getOrDefault(emptyList())
            }
            renderLibrary()
            if (Drive.connected) {
                b.refresh.isRefreshing = true
                val res = withContext(Dispatchers.IO) { runCatching { Drive.list(this@MainActivity) } }
                res.onSuccess {
                    driveTracks = it
                    withContext(Dispatchers.IO) { Drive.saveCache(this@MainActivity, it) }
                }.onFailure {
                    if (userAction || driveTracks.isEmpty()) toast(it.message ?: "Nu am putut încărca lista din Drive")
                }
                renderLibrary()
            }
            b.refresh.isRefreshing = false
        }
    }

    private fun showTrackMenu(anchor: View, t: Track) {
        val menu = PopupMenu(this, anchor)
        if (t.source == Source.DRIVE) {
            menu.menu.add(0, 1, 0, "Salvează pe telefon")
        } else if (Drive.connected) {
            menu.menu.add(0, 2, 0, "Încarcă în Google Drive")
        }
        menu.menu.add(0, 3, 1, "Trimite")
        menu.menu.add(0, 4, 2, if (t.source == Source.DRIVE) "Șterge din Drive" else "Șterge de pe telefon")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> saveToPhone(t)
                2 -> uploadToDrive(t)
                3 -> share(t)
                4 -> confirmDelete(t)
            }
            true
        }
        menu.show()
    }

    /** Fișier local temporar cu piesa (din Drive sau de pe telefon). */
    private fun tempCopy(t: Track): File {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, t.name)
        if (t.source == Source.DRIVE) Drive.download(this, t.driveId!!, f)
        else contentResolver.openInputStream(t.uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun saveToPhone(t: Track) {
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            askPermissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        toast("Se salvează pe telefon…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val f = tempCopy(t)
                    Library.save(this@MainActivity, f).also { f.delete() }
                }
            }
            ok.fold({ toast(if (it) "Salvată în Music/TuneBox ✓" else "Nu am putut salva") }, { toast(it.message ?: "Eroare") })
            Downloader.libraryChanged()
        }
    }

    private fun uploadToDrive(t: Track) {
        toast("Se încarcă în Drive…")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val f = tempCopy(t)
                    try {
                        Drive.upload(this@MainActivity, f)
                    } finally {
                        f.delete()
                    }
                }
            }
            res.fold({ toast("Încărcată în Google Drive ✓") }, { toast(it.message ?: "Eroare") })
            Downloader.libraryChanged()
        }
    }

    private fun share(t: Track) {
        lifecycleScope.launch {
            val uri = if (t.source == Source.LOCAL) t.uri else {
                toast("Se pregătește…")
                withContext(Dispatchers.IO) {
                    runCatching { FileProvider.getUriForFile(this@MainActivity, "$packageName.files", tempCopy(t)) }
                }.getOrElse { return@launch toast(it.message ?: "Eroare") }
            }
            val send = Intent(Intent.ACTION_SEND).setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, t.title))
        }
    }

    private fun confirmDelete(t: Track) {
        val fromDrive = t.source == Source.DRIVE
        MaterialAlertDialogBuilder(this)
            .setTitle(if (fromDrive) "Ștergi din Google Drive?" else "Ștergi de pe telefon?")
            .setMessage(t.title + if (fromDrive) "\n\nAjunge în coșul din Drive (o poți recupera 30 de zile)." else "")
            .setNegativeButton("Nu", null)
            .setPositiveButton("Șterge") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            if (fromDrive) Drive.trash(this@MainActivity, t.driveId!!).let { true }
                            else Library.delete(this@MainActivity, t)
                        }.getOrDefault(false)
                    }
                    if (!ok) toast("Nu am putut șterge piesa")
                    else if (fromDrive) {
                        driveTracks = driveTracks.filterNot { it.id == t.id }
                        Drive.saveCache(this@MainActivity, driveTracks)
                    }
                    reloadLibrary()
                }
            }
            .show()
    }

    private inner class TrackAdapter : ListAdapter<Track, TrackAdapter.VH>(object : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(a: Track, b: Track) = a.id == b.id
        override fun areContentsTheSame(a: Track, b: Track) = a == b
    }) {
        var playingId: String? = null
            set(value) {
                if (field != value) {
                    field = value
                    notifyDataSetChanged()
                }
            }

        inner class VH(val v: ItemTrackBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = getItem(position)
            val active = t.id == playingId
            h.v.title.text = t.title
            h.v.title.setTextColor(getColor(if (active) R.color.brand else R.color.text))
            h.v.sub.text = listOfNotNull(
                formatSize(t.size).takeIf { t.size > 0 },
                DateUtils.getRelativeTimeSpanString(t.added * 1000).toString().takeIf { t.added > 0 },
            ).joinToString(" · ")
            h.v.icon.setImageResource(if (active) R.drawable.ic_play else R.drawable.ic_music)
            h.v.icon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (active) R.color.brand else R.color.muted))
            h.v.root.setOnClickListener {
                val list = currentList
                val i = list.indexOfFirst { it.id == t.id }
                if (i >= 0) play(list, i, shuffle = false)
            }
            h.v.more.setOnClickListener { showTrackMenu(it, t) }
        }
    }

    private fun formatSize(bytes: Long) =
        if (bytes > 1024 * 1024) String.format("%.1f MB", bytes / 1024.0 / 1024.0) else "${bytes / 1024} KB"

    // ================= Player =================

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayer()
    }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled) return@addListener
            controller = runCatching { future.get() }.getOrNull()?.also { it.addListener(playerListener) }
            updatePlayer()
        }, ContextCompat.getMainExecutor(this))
        handler.post(tick)
        reloadLibrary()
        renderJobs(Downloader.jobs.value)
        if (Updater.dueForCheck(this)) checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // revenit din setarea „Permite instalarea”: continuăm actualizarea
        pendingUpdate?.let { info ->
            if (canInstall()) {
                pendingUpdate = null
                startUpdate(info, quiet = false)
            }
        }
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun setupPlayer() {
        b.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
        }
        b.next.setOnClickListener { controller?.seekToNext() }
        b.prev.setOnClickListener { controller?.seekToPrevious() }
        b.shuffle.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        b.repeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) b.position.text = time(value.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                seeking = false
                controller?.seekTo((s?.progress ?: 0).toLong())
            }
        })
    }

    private fun play(list: List<Track>, start: Int, shuffle: Boolean) {
        val c = controller ?: return toast("Player-ul încă pornește, mai încearcă o dată")
        val items = list.map { t ->
            MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(t.uri)
                .setMimeType("audio/mpeg")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(if (t.source == Source.DRIVE) "Google Drive" else "Pe telefon")
                        .build()
                )
                .build()
        }
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    private fun updatePlayer() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            b.player.visibility = View.GONE
            adapter.playingId = null
            return
        }
        b.player.visibility = View.VISIBLE
        val meta = c.mediaMetadata
        val title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaMetadata.title?.toString() ?: ""
        if (b.nowTitle.text.toString() != title) {
            b.nowTitle.text = title
            b.nowTitle.isSelected = true // pornește textul care derulează
        }
        b.nowSub.text = listOfNotNull(
            meta.artist?.toString()?.takeIf { it.isNotBlank() && it != item.mediaMetadata.artist },
            item.mediaMetadata.artist?.toString(),
        ).joinToString(" · ")
        val art = meta.artworkData?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (art != null) {
            b.art.setImageBitmap(art)
            b.art.imageTintList = null
            b.art.setPadding(0, 0, 0, 0)
            b.art.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            b.art.setImageResource(R.drawable.ic_music)
            b.art.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.muted))
            val p = (12 * resources.displayMetrics.density).toInt()
            b.art.setPadding(p, p, p, p)
        }
        val loading = c.playbackState == Player.STATE_BUFFERING
        b.playPause.setImageResource(if (c.isPlaying || (loading && c.playWhenReady)) R.drawable.ic_pause else R.drawable.ic_play)
        b.shuffle.imageTintList = tint(c.shuffleModeEnabled)
        b.repeat.setImageResource(if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        b.repeat.imageTintList = tint(c.repeatMode != Player.REPEAT_MODE_OFF)
        c.playerError?.let { e ->
            b.nowSub.text = "Eroare: " + (e.cause?.message ?: e.errorCodeName)
        }
        adapter.playingId = item.mediaId
        updateProgress()
    }

    private fun tint(on: Boolean) = android.content.res.ColorStateList.valueOf(getColor(if (on) R.color.brand else R.color.muted))

    private fun updateProgress() {
        val c = controller ?: return
        val dur = c.duration.takeIf { it > 0 } ?: 0L
        b.seek.max = dur.toInt()
        b.duration.text = time(dur)
        if (!seeking) {
            b.seek.progress = c.currentPosition.toInt()
            b.position.text = time(c.currentPosition)
        }
        b.seek.secondaryProgress = c.bufferedPosition.toInt()
    }

    private fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ================= Google Drive =================

    private fun connectDrive() {
        Drive.connect(
            this,
            needsUi = { pi -> driveConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build()) },
            done = { onDriveConnected(it) },
        )
    }

    private fun onDriveConnected(r: Result<Unit>) = runOnUiThread {
        r.fold(
            onSuccess = {
                toast("Google Drive conectat ✓")
                prefs.edit().putBoolean("to_drive", true).apply()
                b.toDrive.isChecked = true
                reloadLibrary()
            },
            onFailure = { e ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Nu m-am putut conecta la Drive")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            },
        )
    }

    private fun showDriveMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, Drive.state.value.email.ifBlank { "Cont Google" }).isEnabled = false
        menu.menu.add(0, 1, 1, "Deschide folderul în Google Drive")
        menu.menu.add(0, 2, 2, "Deconectează")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Drive.folderUrl(this)?.let { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    ?: toast("Folderul încă nu a fost creat")
                2 -> MaterialAlertDialogBuilder(this)
                    .setTitle("Deconectezi Google Drive?")
                    .setMessage("Piesele rămân în Drive. Te poți reconecta oricând.")
                    .setNegativeButton("Nu", null)
                    .setPositiveButton("Deconectează") { _, _ ->
                        Drive.disconnect(this)
                        driveTracks = emptyList()
                        File(filesDir, "drive.json").delete()
                        renderLibrary()
                    }
                    .show()
            }
            true
        }
        menu.show()
    }

    // ================= Descarcă =================

    private fun setupDownloadPage() {
        b.quality.check(qualityIds[prefs.getInt("quality", 192)] ?: b.q192.id)
        b.playlist.isChecked = prefs.getBoolean("playlist", false)
        b.toDrive.isChecked = prefs.getBoolean("to_drive", true)
        b.keepLocal.isChecked = prefs.getBoolean("keep_local", false)
        b.quality.addOnButtonCheckedListener { _, id, checked ->
            if (checked) prefs.edit().putInt("quality", quality(id)).apply()
        }
        b.playlist.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("playlist", on).apply() }
        b.toDrive.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("to_drive", on).apply()
            renderDriveState()
        }
        b.keepLocal.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("keep_local", on).apply() }
        b.paste.setOnClickListener { paste() }
        b.download.setOnClickListener { startDownload() }
        b.clearJobs.setOnClickListener { Downloader.clearFinished() }
    }

    private fun renderDriveState() {
        val s = Drive.state.value
        b.driveChip.text = if (s.connected) s.email.substringBefore('@').ifBlank { "Drive" } else "Conectează Drive"
        b.toDrive.isEnabled = s.connected
        val toDrive = s.connected && b.toDrive.isChecked
        // fără Drive, piesele rămân oricum pe telefon
        b.keepLocal.isEnabled = toDrive
        b.keepLocal.alpha = if (toDrive) 1f else 0.5f
        b.toDrive.alpha = if (s.connected) 1f else 0.5f
    }

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
        b.nav.selectedItemId = R.id.nav_download
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

        val toDrive = Drive.connected && b.toDrive.isChecked
        val keepLocal = !toDrive || b.keepLocal.isChecked
        // Android 9 și mai vechi: e nevoie de acces la memorie ca să scriem în Music
        val needStorage = keepLocal && Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

        Downloader.enqueue(links, quality(b.quality.checkedButtonId), b.playlist.isChecked, toDrive, keepLocal)
        b.urls.setText("")
        DownloadService.start(this)
    }

    @OptIn(FlowPreview::class)
    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Downloader.jobs.sample(300).collect { renderJobs(it) } }
                launch { Downloader.libraryVersion.collect { if (it > 0) reloadLibrary() } }
                launch {
                    Drive.state.collect {
                        renderDriveState()
                        renderLibrary()
                    }
                }
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

    private fun renderJobs(jobs: List<Job>) {
        b.jobsCard.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        b.jobs.removeAllViews()
        for (j in jobs.take(30)) {
            val row = ItemJobBinding.inflate(layoutInflater, b.jobs, false)
            row.title.text = j.title.ifBlank { j.url }
            row.progress.isIndeterminate = j.status == Status.CONVERTING || j.status == Status.SAVING ||
                j.status == Status.UPLOADING || (j.status == Status.RUNNING && j.progress <= 0f)
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
                Status.SAVING -> "Se salvează pe telefon…"
                Status.UPLOADING -> "Se încarcă în Google Drive…"
                Status.DONE -> "Gata · " + listOfNotNull(
                    "${j.uploaded} în Drive".takeIf { j.uploaded > 0 },
                    "${j.saved} pe telefon".takeIf { j.saved > 0 },
                ).joinToString(", ")
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

    // ================= Meniu =================

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 2, 0, "Caută actualizări")
        menu.menu.add(0, 4, 1, "Actualizare automată").apply {
            isCheckable = true
            isChecked = Updater.autoEnabled(this@MainActivity)
        }
        menu.menu.add(0, 1, 2, "Actualizează yt-dlp")
        menu.menu.add(0, 3, 3, "Despre")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> updateYtDlp()
                2 -> checkForUpdate(manual = true)
                3 -> about()
                4 -> {
                    val on = !Updater.autoEnabled(this)
                    Updater.setAuto(this, on)
                    toast(if (on) "Actualizare automată pornită" else "Actualizare automată oprită")
                }
            }
            true
        }
        menu.show()
    }

    // ================= Actualizarea aplicației =================

    private fun checkForUpdate(manual: Boolean) {
        if (updating) return
        if (manual) toast("Caut actualizări…")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Updater.check(this@MainActivity) } }
            val info = res.getOrNull()
            when {
                res.isFailure -> if (manual) toast(res.exceptionOrNull()?.message ?: "Nu am putut verifica actualizările")
                info == null -> if (manual) toast("Ai ultima versiune ✓")
                // automat: doar dacă nu ascultă muzică acum (instalarea repornește aplicația)
                !manual && Updater.autoEnabled(this@MainActivity) && controller?.isPlaying != true && canInstall() ->
                    startUpdate(info, quiet = true)
                else -> showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Versiune nouă: ${info.name}")
            .setMessage(info.notes.ifBlank { "Este disponibilă o versiune nouă a aplicației." })
            .setNegativeButton("Mai târziu", null)
            .setPositiveButton("Actualizează") { _, _ -> startUpdate(info, quiet = false) }
            .show()
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun startUpdate(info: UpdateInfo, quiet: Boolean) {
        if (updating) return
        if (!canInstall()) {
            // o singură dată: Android cere voie ca TuneBox să instaleze actualizări
            MaterialAlertDialogBuilder(this)
                .setTitle("Permite actualizările")
                .setMessage("Ca TuneBox să se poată actualiza singur, activează „Permite din această sursă” în pagina care se deschide, apoi revino în aplicație.")
                .setNegativeButton("Anulează", null)
                .setPositiveButton("Deschide setarea") { _, _ ->
                    pendingUpdate = info
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }
                .show()
            return
        }
        updating = true
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            isIndeterminate = true
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val dialog = if (quiet) null else MaterialAlertDialogBuilder(this)
            .setTitle("Se descarcă ${info.name}…")
            .setView(bar)
            .setCancelable(false)
            .show()
        if (quiet) toast("Se descarcă actualizarea TuneBox ${info.name}…")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = Updater.download(this@MainActivity, info) { p ->
                        runOnUiThread {
                            bar.isIndeterminate = false
                            bar.setProgressCompat(p, true)
                        }
                    }
                    Updater.install(this@MainActivity, apk)
                }
            }
            dialog?.dismiss()
            updating = false
            res.onFailure { toast(it.message ?: "Actualizarea a eșuat") }
        }
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

    /** Amprenta SHA-1 a semnăturii (necesară la configurarea Google Drive în Google Cloud). */
    @Suppress("DEPRECATION")
    private fun signatureSha1(): String = runCatching {
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners.first()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
        }
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("?")

    private fun about() {
        lifecycleScope.launch {
            val ytdlp = withContext(Dispatchers.IO) { Downloader.ytDlpVersion(applicationContext) }
            @Suppress("DEPRECATION")
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            val sha1 = signatureSha1()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("TuneBox $version")
                .setMessage(
                    "Descarcă audio în MP3 cu yt-dlp și îl ascultă din Google Drive sau de pe telefon.\n\n" +
                        "yt-dlp: $ytdlp\n\n" +
                        "Pentru Google Cloud (client OAuth Android):\nPachet: $packageName\nSHA-1: $sha1\n\n" +
                        "Folosește aplicația pentru conținut pe care ai dreptul să-l descarci."
                )
                .setNeutralButton("Copiază SHA-1") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("SHA-1", sha1))
                    toast("SHA-1 copiat")
                }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

}
