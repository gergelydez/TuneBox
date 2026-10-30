package ro.tunebox.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import java.io.File
import java.io.IOException

// Player-ul rulează aici, ca muzica să meargă și cu ecranul stins / din altă aplicație
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun onCreate() {
        super.onCreate()
        val context = this

        // piesele din Drive se cer cu tokenul contului (adăugat la fiecare cerere)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.host == "www.googleapis.com") {
                val token = try {
                    Drive.token(context)
                } catch (e: Exception) {
                    throw IOException(e.message, e)
                }
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
            } else {
                spec
            }
        }
        // ce ai ascultat rămâne în cache (1 GB), ca să nu se descarce din nou
        val cached = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(authed)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // fișierele de pe telefon (content://) nu trec prin cache
        val dataSource = DefaultDataSource.Factory(context, cached)

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // butoane -10 s / +10 s în notificare, pe ecranul de blocare și în mașină
        val seekButtons = ImmutableList.of(
            CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setIconResId(R.drawable.ic_fast_rewind)
                .setDisplayName("Înapoi 10 s")
                .build(),
            CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setIconResId(R.drawable.ic_fast_forward)
                .setDisplayName("Înainte 10 s")
                .build(),
        )
        session = MediaLibrarySession.Builder(context, player, LibraryCallback())
            .setSessionActivity(open)
            .setCustomLayout(seekButtons)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Ce vede mașina (Android Auto): folderele și piesele din Drive și de pe telefon. */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(LibraryTree.rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val all = LibraryTree.children(this@PlaybackService, parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(if (pageSize > 0) all.subList(from, to) else all), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = io.submit<LibraryResult<MediaItem>> {
            LibraryTree.item(this@PlaybackService, mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = io.submit<MutableList<MediaItem>> {
            mediaItems.map { LibraryTree.resolve(this@PlaybackService, it) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit<MediaSession.MediaItemsWithStartPosition> {
            val first = mediaItems.firstOrNull()
            // din mașină vine o singură piesă (doar id-ul): redăm tot folderul, pornind de la ea
            val queue = if (mediaItems.size == 1 && first != null && first.localConfiguration == null) {
                LibraryTree.queueFor(this@PlaybackService, first.mediaId)
            } else null
            if (queue != null) {
                MediaSession.MediaItemsWithStartPosition(queue.first, queue.second, startPositionMs.takeIf { it > 0 } ?: C.TIME_UNSET)
            } else {
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems.map { LibraryTree.resolve(this@PlaybackService, it) }, startIndex, startPositionMs,
                )
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        io.shutdown()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private var simpleCache: SimpleCache? = null

        @Synchronized
        fun cache(context: Context): SimpleCache = simpleCache ?: SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { simpleCache = it }
    }
}
