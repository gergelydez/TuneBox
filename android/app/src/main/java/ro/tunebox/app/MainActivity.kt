package ro.tunebox.app

import android.Manifest
import android.app.RecoverableSecurityException
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
import android.provider.MediaStore
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
            if (source == Source.LOCAL) askReadOnce()
        }
        b.tracks.layoutManager = LinearLayoutManager(this)
        b.tracks.adapter = adapter
        b.search.doAfterTextChanged { renderLibrary() }
        b.playAll.setOnClickListener { if (shown.isNotEmpty()) play(shown, 0, shuffle = false) }
        b.shuffleAll.setOnClickListener { if (shown.isNotEmpty()) play(shown, shown.indices.random(), shuffle = true) }
        b.refresh.setColorSchemeColors(getColor(R.color.brand))
        b.refresh.setOnRefreshListener { reloadLibrary(userAction = true) }
        b.permBtn.setOnClickListener { requestRead() }
        b.syncBtn.setOnClickListener { syncNow() }
        driveTracks = Drive.loadCache(this)
        if (source == Source.LOCAL) askReadOnce()
    }

    // ---- permisiunea de a vedea toate piesele din folder ----

    private val askRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) reloadLibrary() else renderLibrary()
    }

    private fun askReadOnce() {
        if (Library.hasReadPermission(this) || prefs.getBoolean("asked_read", false)) return
        prefs.edit().putBoolean("asked_read", true).apply()
        askRead.launch(Library.readPermission)
    }

    private fun requestRead() {
        val perm = Library.readPermission
        if (prefs.getBoolean("asked_read", false) && !shouldShowRequestPermissionRationale(perm)) {
            // Android nu mai arată fereastra: trimitem utilizatorul în setările aplicației
            toast("Apasă Permisiuni → Muzică și audio → Permite")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            prefs.edit().putBoolean("asked_read", true).apply()
            askRead.launch(perm)
        }
    }

    // ---- bibliotecă ----

    private var driveLoadedOk = false // lista din Drive a fost citită acum (nu doar din cache)
    private var autoSyncDone = false
    private var driveKeys: Set<String> = emptySet()
    private var localKeys: Set<String> = emptySet()

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun inDrive(t: Track) = t.source == Source.DRIVE || Sync.trackKey(t) in driveKeys
    private fun onPhone(t: Track) = t.source == Source.LOCAL || Sync.trackKey(t) in localKeys
    private fun driveTwin(t: Track) = driveTracks.firstOrNull { Sync.trackKey(it) == Sync.trackKey(t) }
    private fun localTwin(t: Track) = localTracks.firstOrNull { Sync.trackKey(it) == Sync.trackKey(t) }
    private fun unsynced() = localTracks.filter { Sync.trackKey(it) !in driveKeys }

    // ---- foldere ----

    private var folderFilter: String? = null // null = toate
    private var chipLabels: List<String> = emptyList()
    private val rootLabel = "Principal"

    /** Toate folderele cunoscute (Drive, telefon și create de tine). */
    private fun allFolders(): List<String> =
        (Drive.folders + driveTracks.map { it.folder } + localTracks.map { it.folder } +
            (prefs.getStringSet("folders", emptySet()) ?: emptySet()))
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    private fun rememberFolder(name: String) {
        val set = (prefs.getStringSet("folders", emptySet()) ?: emptySet()) + name
        prefs.edit().putStringSet("folders", set).apply()
    }

    private fun folderLabel(name: String) = name.ifBlank { "TuneBox (principal)" }

    private fun renderChips(all: List<Track>) {
        val names = (all.map { it.folder } + if (source == Source.DRIVE) Drive.folders else emptyList())
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        b.folderScroll.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        if (names.isEmpty()) {
            folderFilter = null
            return
        }
        val hasRoot = all.any { it.folder.isBlank() }
        val labels = listOf("Toate") + (if (hasRoot) listOf(rootLabel) else emptyList()) + names
        if (folderFilter != null && folderFilter!!.isNotBlank() && names.none { it.equals(folderFilter, true) }) folderFilter = null
        if (labels != chipLabels) {
            chipLabels = labels
            b.folderChips.setOnCheckedStateChangeListener(null)
            b.folderChips.removeAllViews()
            labels.forEachIndexed { i, label ->
                val chip = com.google.android.material.chip.Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isCheckedIconVisible = false
                    tag = when {
                        i == 0 -> null
                        hasRoot && i == 1 -> ""
                        else -> label
                    }
                    if (i > 0 && !(hasRoot && i == 1)) chipIcon = getDrawable(R.drawable.ic_folder)
                    chipIconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.brand))
                }
                b.folderChips.addView(chip)
            }
            b.folderChips.setOnCheckedStateChangeListener { group, ids ->
                val chip = ids.firstOrNull()?.let { group.findViewById<com.google.android.material.chip.Chip>(it) }
                folderFilter = chip?.tag as String?
                renderLibrary()
            }
        }
        // bifăm chip-ul folderului curent fără să declanșăm din nou randarea
        for (i in 0 until b.folderChips.childCount) {
            val chip = b.folderChips.getChildAt(i) as com.google.android.material.chip.Chip
            val want = (chip.tag as String?)?.lowercase() == folderFilter?.lowercase()
            if (chip.isChecked != want) {
                b.folderChips.setOnCheckedStateChangeListener(null)
                chip.isChecked = want
                b.folderChips.setOnCheckedStateChangeListener { group, ids ->
                    val c = ids.firstOrNull()?.let { group.findViewById<com.google.android.material.chip.Chip>(it) }
                    folderFilter = c?.tag as String?
                    renderLibrary()
                }
            }
        }
    }

    /** Fereastră de alegere a folderului, cu opțiunea „Folder nou”. */
    private fun pickFolder(title: String, current: String, onPick: (String) -> Unit) {
        val folders = allFolders()
        val labels: Array<CharSequence> = (listOf(folderLabel("")) + folders + "＋ Folder nou…")
            .map { it as CharSequence }.toTypedArray()
        val checked = if (current.isBlank()) 0 else folders.indexOfFirst { it.equals(current, true) }.let { if (it < 0) -1 else it + 1 }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> onPick("")
                    labels.size - 1 -> newFolder(onPick)
                    else -> onPick(folders[which - 1])
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun newFolder(onPick: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            hint = "ex. Rock, Petrecere, Mașină"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = android.widget.FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Folder nou")
            .setView(box)
            .setNegativeButton("Anulează", null)
            .setPositiveButton("Creează") { _, _ ->
                val name = Library.cleanFolder(input.text.toString())
                if (name.isEmpty()) return@setPositiveButton toast("Scrie un nume pentru folder")
                rememberFolder(name)
                // îl creăm și în Drive, ca să apară imediat și pe celelalte telefoane
                if (Drive.connected) lifecycleScope.launch(Dispatchers.IO) { runCatching { Drive.subfolderId(this@MainActivity, name) } }
                onPick(name)
            }
            .show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun moveTrack(t: Track, local: Track?, drive: Track?) {
        pickFolder("Mută „${t.title}” în", t.folder) { folder ->
            if (folder.equals(t.folder, true)) return@pickFolder
            lifecycleScope.launch {
                if (drive != null) {
                    val ok = withContext(Dispatchers.IO) { runCatching { Drive.move(this@MainActivity, drive.driveId!!, folder) } }
                    ok.onFailure { toast(it.message ?: "Nu am putut muta în Drive") }
                }
                if (local != null) moveLocal(local, folder) else {
                    toast("Mutată în ${folderLabel(folder)} ✓")
                    reloadLibrary()
                }
            }
        }
    }

    private fun moveLocal(t: Track, folder: String) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Library.move(this@MainActivity, t, folder) } }
            val e = res.exceptionOrNull()
            when {
                res.getOrNull() == true -> {
                    toast("Mutată în ${folderLabel(folder)} ✓")
                    reloadLibrary()
                }
                e is SecurityException && Build.VERSION.SDK_INT >= 30 -> {
                    val pi = MediaStore.createWriteRequest(contentResolver, listOf(t.uri))
                    afterConsent = { moveLocal(t, folder) }
                    systemConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
                Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException -> {
                    afterConsent = { moveLocal(t, folder) }
                    systemConsent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
                else -> {
                    toast("Nu am putut muta piesa pe telefon")
                    reloadLibrary()
                }
            }
        }
    }

    private fun renderLibrary() {
        driveKeys = driveTracks.map { Sync.trackKey(it) }.toSet()
        localKeys = localTracks.map { Sync.trackKey(it) }.toSet()
        val isDrive = source == Source.DRIVE
        val needsConnect = isDrive && !Drive.connected
        val all = if (isDrive) driveTracks else localTracks
        if (needsConnect) b.folderScroll.visibility = View.GONE else renderChips(all)
        val inFolder = folderFilter?.let { f -> all.filter { it.folder.equals(f, true) } } ?: all
        val q = normalize(b.search.text.toString().trim())
        shown = if (q.isEmpty()) inFolder else inFolder.filter { normalize(it.title).contains(q) }

        b.driveConnect.visibility = if (needsConnect) View.VISIBLE else View.GONE
        b.search.visibility = if (needsConnect || all.isEmpty()) View.GONE else View.VISIBLE
        b.actions.visibility = if (needsConnect || shown.isEmpty()) View.GONE else View.VISIBLE
        b.permBar.visibility = if (!isDrive && !Library.hasReadPermission(this)) View.VISIBLE else View.GONE
        b.trackCount.text = "${shown.size} ${if (shown.size == 1) "piesă" else "piese"}"
        b.libEmpty.visibility = if (!needsConnect && shown.isEmpty()) View.VISIBLE else View.GONE
        b.libEmpty.text = when {
            all.isEmpty() && isDrive -> "Încă nu ai piese în Drive.\nDescarcă ceva din tabul Descarcă."
            all.isEmpty() -> "Nicio piesă în Music/TuneBox.\nPiesele descărcate sau copiate acolo apar aici."
            inFolder.isEmpty() -> "Folderul e gol."
            else -> "Nicio piesă găsită."
        }
        adapter.submitList(shown)
        adapter.notifyDataSetChanged() // indicatorii „în Drive / pe telefon” se pot schimba fără ca piesa să se schimbe
        renderSync()
    }

    private fun renderSync() {
        val s = Sync.state.value
        val show = source == Source.LOCAL && Drive.connected && (driveLoadedOk || s.running)
        b.syncBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val missing = unsynced()
        when {
            s.running -> {
                b.syncText.text = "Se încarcă în Drive ${s.done + 1}/${s.total}\n${s.current}"
                b.syncBtn.visibility = View.GONE
                b.syncIcon.setImageResource(R.drawable.ic_cloud_upload)
            }
            missing.isEmpty() -> {
                b.syncText.text = "Toate piesele de pe telefon sunt și în Google Drive ✓"
                b.syncBtn.visibility = View.GONE
                b.syncIcon.setImageResource(R.drawable.ic_cloud)
            }
            else -> {
                val n = missing.size
                b.syncText.text = if (n == 1) "1 piesă e doar pe telefon" else "$n piese sunt doar pe telefon"
                b.syncBtn.visibility = View.VISIBLE
                b.syncIcon.setImageResource(R.drawable.ic_cloud_upload)
            }
        }
    }

    private fun syncNow() {
        if (!Drive.connected) return connectDrive()
        val missing = unsynced()
        if (missing.isEmpty()) return toast("Totul e deja în Drive ✓")
        Sync.upload(this, missing)
        renderSync()
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
                    driveLoadedOk = true
                    withContext(Dispatchers.IO) { Drive.saveCache(this@MainActivity, it) }
                }.onFailure {
                    if (userAction || driveTracks.isEmpty()) toast(it.message ?: "Nu am putut încărca lista din Drive")
                }
                renderLibrary()
                // sincronizare automată: o dată pe pornire, doar cu lista proaspătă din Drive (ca să nu dublăm piese)
                if (driveLoadedOk && !autoSyncDone && Sync.autoEnabled(this@MainActivity)) {
                    autoSyncDone = true
                    unsynced().takeIf { it.isNotEmpty() }?.let { Sync.upload(this@MainActivity, it) }
                }
            }
            b.refresh.isRefreshing = false
        }
    }

    // ---- acțiuni pe piesă ----

    private fun showTrackMenu(anchor: View, t: Track) {
        val local = if (t.source == Source.LOCAL) t else localTwin(t)
        val drive = if (t.source == Source.DRIVE) t else driveTwin(t)
        val menu = PopupMenu(this, anchor)
        if (local == null && drive != null) menu.menu.add(0, 1, 0, "Salvează pe telefon")
        if (drive == null && local != null && Drive.connected) menu.menu.add(0, 2, 0, "Încarcă în Google Drive")
        menu.menu.add(0, 3, 1, "Trimite")
        if (local != null) menu.menu.add(0, 4, 2, "Șterge de pe telefon")
        if (drive != null) menu.menu.add(0, 5, 3, "Șterge din Google Drive")
        if (local != null && drive != null) menu.menu.add(0, 6, 4, "Șterge de peste tot")
        menu.menu.add(0, 7, 1, "Mută în alt folder…")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> drive?.let { d -> saveToPhone(d) }
                2 -> local?.let { l -> uploadToDrive(l) }
                3 -> share(local ?: t)
                4 -> confirmDelete(t, local, null)
                5 -> confirmDelete(t, null, drive)
                6 -> confirmDelete(t, local, drive)
                7 -> moveTrack(t, local, drive)
            }
            true
        }
        menu.show()
    }

    /** Fișier local temporar cu piesa (din Drive sau de pe telefon). */
    private fun tempCopy(t: Track): File {
        val dir = File(cacheDir, "share")
        if (t.source == Source.LOCAL) return Library.copyToTemp(this, t, dir)
        dir.mkdirs()
        val f = File(dir, t.name)
        Drive.download(this, t.driveId!!, f)
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

    private fun uploadToDrive(t: Track) = Sync.upload(this, listOf(t)).also { renderSync() }

    private fun share(t: Track) {
        lifecycleScope.launch {
            val uri = if (t.source == Source.LOCAL) t.uri else {
                toast("Se pregătește…")
                withContext(Dispatchers.IO) {
                    runCatching { FileProvider.getUriForFile(this@MainActivity, "$packageName.files", tempCopy(t)) }
                }.getOrElse { return@launch toast(it.message ?: "Eroare") }
            }
            val send = Intent(Intent.ACTION_SEND).setType(Library.mimeOf(t.name))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, t.title))
        }
    }

    private fun confirmDelete(t: Track, local: Track?, drive: Track?) {
        val title = when {
            local != null && drive != null -> "Ștergi de peste tot?"
            drive != null -> "Ștergi din Google Drive?"
            else -> "Ștergi de pe telefon?"
        }
        val note = buildList {
            if (drive != null) add("Din Drive ajunge în coș (o poți recupera 30 de zile).")
            if (local != null && drive == null && inDrive(local)) add("Rămâne în Google Drive.")
            if (drive != null && local == null && onPhone(drive)) add("Rămâne pe telefon.")
        }.joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(t.title + if (note.isNotEmpty()) "\n\n$note" else "")
            .setNegativeButton("Nu", null)
            .setPositiveButton("Șterge") { _, _ ->
                lifecycleScope.launch {
                    if (drive != null) {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { Drive.trash(this@MainActivity, drive.driveId!!) }.isSuccess
                        }
                        if (ok) {
                            driveTracks = driveTracks.filterNot { it.id == drive.id }
                            Drive.saveCache(this@MainActivity, driveTracks)
                        } else {
                            toast("Nu am putut șterge din Drive")
                        }
                    }
                    if (local != null) deleteLocal(local) else reloadLibrary()
                }
            }
            .show()
    }

    // Android cere confirmare pentru fișierele care nu au fost create de aplicație
    private var afterConsent: (() -> Unit)? = null
    private val systemConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val next = afterConsent
        afterConsent = null
        if (res.resultCode == RESULT_OK) next?.invoke() else reloadLibrary()
    }

    private fun deleteLocal(t: Track) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Library.delete(this@MainActivity, t) } }
            val e = res.exceptionOrNull()
            when {
                res.getOrNull() == true -> reloadLibrary()
                e is SecurityException && Build.VERSION.SDK_INT >= 30 -> {
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(t.uri))
                    afterConsent = { reloadLibrary() } // Android a șters deja fișierul
                    systemConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
                Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException -> {
                    afterConsent = { deleteLocal(t) } // acum avem voie: încercăm din nou
                    systemConsent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
                else -> {
                    toast("Nu am putut șterge piesa de pe telefon")
                    reloadLibrary()
                }
            }
        }
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
            // unde se află piesa: în Drive, pe telefon sau în ambele
            val where = when {
                !Drive.connected -> null
                inDrive(t) && onPhone(t) -> "în Drive și pe telefon"
                t.source == Source.LOCAL -> "doar pe telefon"
                else -> null
            }
            h.v.sub.text = listOfNotNull(
                "▸ ${t.folder}".takeIf { t.folder.isNotBlank() && folderFilter == null },
                formatSize(t.size).takeIf { t.size > 0 },
                DateUtils.getRelativeTimeSpanString(t.added * 1000).toString().takeIf { t.added > 0 },
                where,
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
        b.trimSilence.isChecked = prefs.getBoolean("trim_silence", true)
        b.trimSilence.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("trim_silence", on).apply() }
        renderFolderBtn()
        b.folderBtn.setOnClickListener {
            pickFolder("Salvează în folderul", prefs.getString("dl_folder", "") ?: "") { f ->
                prefs.edit().putString("dl_folder", f).apply()
                renderFolderBtn()
            }
        }
        b.paste.setOnClickListener { paste() }
        b.download.setOnClickListener { startDownload() }
        b.clearJobs.setOnClickListener { Downloader.clearFinished() }
    }

    private fun renderFolderBtn() {
        b.folderBtn.text = folderLabel(prefs.getString("dl_folder", "") ?: "")
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

        val folder = prefs.getString("dl_folder", "") ?: ""
        Downloader.enqueue(
            links, quality(b.quality.checkedButtonId), b.playlist.isChecked, toDrive, keepLocal, folder,
            b.trimSilence.isChecked,
        )
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
                    var wasRunning = Sync.state.value.running
                    Sync.state.collect { st ->
                        renderSync()
                        if (wasRunning && !st.running && st.total > 0) {
                            toast(
                                when {
                                    st.failed == 0 -> "Sincronizare gata: ${st.uploaded} ${if (st.uploaded == 1) "piesă urcată" else "piese urcate"} în Drive ✓"
                                    else -> "Urcate: ${st.uploaded}, eșuate: ${st.failed}. ${st.error}"
                                }
                            )
                        }
                        wasRunning = st.running
                    }
                }
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
        menu.menu.add(0, 5, 2, "Sincronizare automată cu Drive").apply {
            isCheckable = true
            isChecked = Sync.autoEnabled(this@MainActivity)
        }
        menu.menu.add(0, 1, 3, "Actualizează yt-dlp")
        menu.menu.add(0, 3, 4, "Despre")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> updateYtDlp()
                2 -> checkForUpdate(manual = true)
                3 -> about()
                5 -> {
                    val on = !Sync.autoEnabled(this)
                    Sync.setAuto(this, on)
                    toast(if (on) "Piesele noi de pe telefon vor urca singure în Drive" else "Sincronizare automată oprită")
                    if (on && driveLoadedOk) unsynced().takeIf { l -> l.isNotEmpty() }?.let { l -> Sync.upload(this, l) }
                }
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
