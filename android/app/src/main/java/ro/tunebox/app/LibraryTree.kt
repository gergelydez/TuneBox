package ro.tunebox.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Biblioteca văzută din mașină (Android Auto) și de alte playere:
 *   Google Drive → foldere → piese      Pe telefon → foldere → piese
 * Fiecare listă de piese începe cu „Amestecă tot”.
 */
object LibraryTree {
    const val ROOT = "root"
    private const val DRIVE = "drive"
    private const val LOCAL = "local"
    private const val FOLDER = "folder:"   // folder:drive:Rock / folder:local:
    private const val SHUFFLE = "shuffle:" // shuffle:drive:Rock

    private fun tracks(ctx: Context, src: String): List<Track> =
        if (src == DRIVE) (if (Drive.connected) Drive.loadCache(ctx) else emptyList())
        else runCatching { Library.list(ctx) }.getOrDefault(emptyList())

    fun rootItem(): MediaItem = node(ROOT, "TuneBox")

    fun children(ctx: Context, parentId: String): List<MediaItem> = when {
        parentId == ROOT -> buildList {
            if (Drive.connected) add(node(DRIVE, "Google Drive"))
            add(node(LOCAL, "Pe telefon"))
        }
        parentId == DRIVE || parentId == LOCAL -> {
            val all = tracks(ctx, parentId)
            val folders = (all.map { it.folder } + if (parentId == DRIVE) Drive.folders else emptyList())
                .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
            val root = all.filter { it.folder.isBlank() }
            buildList {
                if (all.isNotEmpty()) add(shuffleItem("$SHUFFLE$parentId:*", "Amestecă tot (${all.size})"))
                folders.forEach { add(node("$FOLDER$parentId:$it", it)) }
                root.forEach { add(playable(it)) }
            }
        }
        parentId.startsWith(FOLDER) -> {
            val (src, folder) = parentId.removePrefix(FOLDER).split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val inFolder = tracks(ctx, src).filter { it.folder.equals(folder, true) }
            buildList {
                if (inFolder.size > 1) add(shuffleItem("$SHUFFLE$src:$folder", "Amestecă"))
                inFolder.forEach { add(playable(it)) }
            }
        }
        else -> emptyList()
    }

    fun item(ctx: Context, mediaId: String): MediaItem? = when (mediaId) {
        ROOT -> rootItem()
        DRIVE -> node(DRIVE, "Google Drive")
        LOCAL -> node(LOCAL, "Pe telefon")
        else -> find(ctx, mediaId)?.let { playable(it) }
    }

    private fun find(ctx: Context, mediaId: String): Track? {
        val src = if (mediaId.startsWith("drive:")) DRIVE else LOCAL
        return tracks(ctx, src).firstOrNull { it.id == mediaId }
    }

    /**
     * Ce se redă când alegi ceva din mașină: piesa aleasă împreună cu celelalte din același folder,
     * sau tot folderul amestecat. Întoarce lista și poziția de start.
     */
    fun queueFor(ctx: Context, mediaId: String): Pair<List<MediaItem>, Int>? {
        if (mediaId.startsWith(SHUFFLE)) {
            val (src, folder) = mediaId.removePrefix(SHUFFLE).split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val all = tracks(ctx, src)
            val list = if (folder == "*") all else all.filter { it.folder.equals(folder, true) }
            return list.shuffled().map { playable(it) }.takeIf { it.isNotEmpty() }?.let { it to 0 }
        }
        val t = find(ctx, mediaId) ?: return null
        val src = if (t.source == Source.DRIVE) DRIVE else LOCAL
        val siblings = tracks(ctx, src).filter { it.folder.equals(t.folder, true) }
        val i = siblings.indexOfFirst { it.id == t.id }.coerceAtLeast(0)
        return siblings.map { playable(it) } to i
    }

    /** Completează adresa piesei (mașina trimite doar id-ul). */
    fun resolve(ctx: Context, item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item else find(ctx, item.mediaId)?.let { playable(it) } ?: item

    fun playable(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id)
        .setUri(t.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(if (t.source == Source.DRIVE) "Google Drive" else "Pe telefon")
                .setAlbumTitle(t.folder.ifBlank { "TuneBox" })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun node(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun shuffleItem(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("🔀 $title")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .build()
}
