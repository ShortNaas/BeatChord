package com.music.beatchord.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.music.beatchord.data.model.ArtistRef
import com.music.beatchord.data.model.NOTIFICATION_ART_PX
import com.music.beatchord.data.model.PlaybackSourceType
import com.music.beatchord.data.model.QueueTier
import com.music.beatchord.data.model.Song
import com.music.beatchord.data.model.artworkAt
import com.music.beatchord.ui.rememberIsForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Snapshot of playback state, driven by the MediaController. */
data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    /**
     * The playhead. A field rather than a value: its identity never changes, so
     * carrying it here costs no invalidation — see [PlaybackPosition].
     */
    val position: PlaybackPosition = PlaybackPosition(),
    /**
     * Left here rather than moved alongside the position: it settles once per
     * track, and [mutableStateOf] compares structurally, so the poll writing it
     * back unchanged every tick invalidates nothing.
     */
    val durationMs: Long = 0L,
    val error: String? = null,
    /** True while ExoPlayer is buffering — including preparing a local file. */
    val isLoading: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    /**
     * Whether the queue has somewhere to go either side of the current track.
     * Taken from the player rather than [queueIndex], so the wrap-around of
     * repeat-all is already accounted for.
     */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** Binds to [PlaybackService] for the lifetime of the composition. */
@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

/**
 * Routes Shuffle through the playback service so changing its state and
 * reordering the service-owned queue happen as one operation.
 */
fun MediaController.toggleShuffle() {
    sendCustomCommand(
        SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY),
        Bundle.EMPTY,
    )
}

/**
 * The credited artists, read back out of the two parallel lists they travel in.
 *
 * A `Song` is a data class and a `MediaItem`'s extras are a `Bundle`, which
 * holds scalars and collections of them — so the credits cross as their names
 * and their ids side by side rather than as the list itself. A blank id is a
 * name a catalogue stated without a channel behind it, which is kept so the credit
 * still reads; it simply has no page to open.
 *
 * Shorter of the two lists wins, and they are written together, so a bundle
 * that has been through something which dropped one of them yields what is left
 * rather than a list of names with no ids or ids with no names.
 */
private fun Bundle.artistsFromBundle(): List<ArtistRef> {
    val names = getStringArrayList(ARTISTS_NAMES).orEmpty()
    val ids = getStringArrayList(ARTISTS_IDS).orEmpty()
    if (names.isEmpty() || ids.isEmpty()) return emptyList()
    return names.indices.map { index ->
        ArtistRef(name = names[index], browseId = ids.getOrNull(index)?.ifBlank { null })
    }
}

private const val ARTISTS_NAMES = "artistNames"
private const val ARTISTS_IDS = "artistIds"

/** Mirrors the controller into Compose state, polling position while playing. */
@Composable
fun rememberPlayerState(controller: MediaController?): PlayerState {
    val position = remember { PlaybackPosition() }
    var state by remember { mutableStateOf(PlayerState(position = position)) }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}

        // A MediaController reads each item across the session boundary. Keep
        // the converted queue until its timeline actually changes; playback,
        // buffering, repeat and metadata events do not require another O(n)
        // walk over a large playlist.
        var queueSnapshot = emptyList<Song>()

        // Set when the timeline change was the *contents* changing, which is the
        // only kind this has to convert again.
        //
        // `EVENT_TIMELINE_CHANGED` on its own is not that question. It also
        // fires for `TIMELINE_CHANGE_REASON_SOURCE_UPDATE`, which every track
        // raises as its source is prepared and reports its real duration — the
        // running order is identical, only the window it describes has filled
        // in. Rebuilding on those meant a thousand-track queue converted a
        // thousand songs out of the session for every track it loaded, on the
        // main thread, for as long as the queue kept playing.
        var queueChanged = false

        fun sync(
            error: String? = null,
            rebuildQueue: Boolean = false,
            refreshCurrentQueueItem: Boolean = false,
        ) {
            val item = player.currentMediaItem
            if (rebuildQueue) {
                queueSnapshot = (0 until player.mediaItemCount)
                    .map { player.getMediaItemAt(it).toSong() }
            } else if (refreshCurrentQueueItem && item != null) {
                val index = player.currentMediaItemIndex
                if (index in queueSnapshot.indices) {
                    queueSnapshot = queueSnapshot.toMutableList().also { it[index] = item.toSong() }
                }
            }
            // Synced here too, so seeking while paused or buffering still moves
            // the scrubber (the poll loop only runs on play).
            position.report(player.currentPosition.coerceAtLeast(0L))
            state = state.copy(
                song = item?.toSong(),
                isPlaying = player.isPlaying,
                durationMs = player.duration.coerceAtLeast(0L),
                error = error,
                isLoading = player.playbackState == Player.STATE_BUFFERING,
                repeatMode = player.repeatMode,
                queue = queueSnapshot,
                queueIndex = player.currentMediaItemIndex,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                    )
        }

        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // The count guard is belt and braces: a playlist change is the
                // only reason the queue can be a different length, so if it is,
                // this must convert it again whatever the reason claims.
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ||
                    timeline.windowCount != queueSnapshot.size
                ) {
                    queueChanged = true
                }
            }
            // Every jump the player makes — a seek, a skip, a repeat starting
            // over, a stretch of silence skipped — is announced here, ahead of
            // the `onEvents` that reports where it landed. It is the only thing
            // the lyrics accept as a reason to go backwards; see PlaybackPosition.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                position.seeks++
            }
            override fun onEvents(p: Player, events: Player.Events) = sync(
                error = state.error,
                rebuildQueue = queueChanged.also { queueChanged = false },
                refreshCurrentQueueItem = events.contains(Player.EVENT_MEDIA_METADATA_CHANGED),
            )
            override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
                sync(error?.let { "Playback failed: ${it.errorCodeName}" })
            }
        }
        player.addListener(listener)
        sync(rebuildQueue = true)
        onDispose { player.removeListener(listener) }
    }

    // Only while the app is on screen. The poll exists to move a scrubber, and
    // a scrubber behind a locked screen is not being read — but the loop is a
    // plain `delay`, so without this it went on making two binder round-trips a
    // second to the media session for the whole time the phone was in a pocket.
    // Nothing is lost by stopping: `sync` above runs on the controller's own
    // events, and the first thing that happens on the way back is a fresh read.
    val foreground = rememberIsForeground()
    LaunchedEffect(controller, state.isPlaying, foreground) {
        while (controller != null && state.isPlaying && foreground) {
            position.report(controller.currentPosition.coerceAtLeast(0L))
            val duration = controller.duration.coerceAtLeast(0L)
            if (duration != state.durationMs) state = state.copy(durationMs = duration)
            delay(500)
        }
    }
    return state
}

/**
 * The inverse of [toMediaItem], as far as a MediaItem can carry a [Song].
 *
 * It has to round-trip the fields used by the live queue UI and playback
 * service. A field dropped here disappears as soon as a Song passes through
 * Media3, because controllers and service helpers read it back through this.
 */
fun MediaItem.toSong() = Song(
    videoId = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    thumbnailUrl = mediaMetadata.artworkUri?.toString(),
    durationText = mediaMetadata.extras?.getString(EXTRA_DURATION),
    artistId = mediaMetadata.extras?.getString(EXTRA_ARTIST_ID),
    artists = mediaMetadata.extras?.artistsFromBundle() ?: emptyList(),
    albumId = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
    albumName = mediaMetadata.albumTitle?.toString(),
    isExplicit = mediaMetadata.extras?.takeIf { it.containsKey(EXTRA_EXPLICIT) }
        ?.getBoolean(EXTRA_EXPLICIT),
    isVideo = mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true,
    isVideoOrigin = mediaMetadata.extras?.getBoolean(EXTRA_VIDEO_ORIGIN) == true ||
        mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true,
    setVideoId = mediaMetadata.extras?.getString(EXTRA_SET_VIDEO_ID),
    queueTier = this.queueTier,
    queueEntryId = this.queueEntryId,
    radioName = mediaMetadata.extras?.getString(EXTRA_RADIO_NAME),
    playbackSource = mediaMetadata.extras?.getString(EXTRA_PLAYBACK_SOURCE),
    playbackSourceType = mediaMetadata.extras?.getString(EXTRA_PLAYBACK_SOURCE_TYPE)
        ?.let { runCatching { PlaybackSourceType.valueOf(it) }.getOrNull() },
    playbackSourceId = mediaMetadata.extras?.getString(EXTRA_PLAYBACK_SOURCE_ID),
    localUri = mediaMetadata.extras?.getString(EXTRA_LOCAL_URI),
    localPath = mediaMetadata.extras?.getString(EXTRA_LOCAL_PATH),
)

/** @see Song.fromAutoplay */
val MediaItem.fromAutoplay: Boolean
    get() = queueTier == QueueTier.AUTOPLAY

/** @see Song.queueTier */
val MediaItem.queueTier: QueueTier
    get() {
        return when (mediaMetadata.extras?.getString(EXTRA_QUEUE_TIER)) {
            "USER_QUEUE" -> QueueTier.USER_QUEUE
            "CONTEXT" -> QueueTier.CONTEXT
            "AUTOPLAY" -> QueueTier.AUTOPLAY
            else -> if (mediaMetadata.extras?.getBoolean(EXTRA_FROM_AUTOPLAY) == true) {
                QueueTier.AUTOPLAY
            } else {
                QueueTier.CONTEXT
            }
        }
    }

/** @see Song.queueEntryId */
val MediaItem.queueEntryId: String?
    get() = mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY_ID)

/** Public metadata keys for queue categorization and immutable queue entry identity. */
const val EXTRA_QUEUE_TIER = "beatchord.queueTier"
const val EXTRA_QUEUE_ENTRY_ID = "beatchord.queueEntryId"

/**
 * Marks a queue entry as AutoPlay's rather than the user's. Carried on the
 * MediaItem so it survives the trip through the session — the queue belongs to
 * the player, and the UI only ever sees it back through a MediaController.
 */
private const val EXTRA_FROM_AUTOPLAY = "beatchord.fromAutoplay"

/** @see Song.radioName */
private const val EXTRA_RADIO_NAME = "beatchord.radioName"

/** @see Song.playbackSource */
private const val EXTRA_PLAYBACK_SOURCE = "beatchord.playbackSource"
private const val EXTRA_PLAYBACK_SOURCE_TYPE = "beatchord.playbackSourceType"
private const val EXTRA_PLAYBACK_SOURCE_ID = "beatchord.playbackSourceId"

/**
 * The artist and album pages this track hangs under, when they are known.
 *
 * Carried so they survive the round trip through the session: the player's own
 * menu backfills them with a lookup when they are missing (see MainActivity's
 * `links`), but a queue restored after a restart, or a track read back by the
 * service, has only what the item carries.
 */
private const val EXTRA_ARTIST_ID = "beatchord.artistId"
private const val EXTRA_ALBUM_ID = "beatchord.albumId"

/** @see Song.setVideoId */
private const val EXTRA_SET_VIDEO_ID = "beatchord.setVideoId"

/** @see Song.localUri */
internal const val EXTRA_LOCAL_URI = "beatchord.localUri"

/** @see Song.localPath */
internal const val EXTRA_LOCAL_PATH = "beatchord.localPath"

/**
 * How long the track runs, as the row that queued it said.
 *
 * On the item rather than left to [MediaMetadata.durationMs] because that field
 * is the *player's* to state, and the player takes its own figure from the
 * decoder. This one is the claim the row that queued the track made, and the two
 * disagree often enough that overwriting either with the other loses
 * information. Carried so that [toSong] can give it back to the live queue.
 */
private const val EXTRA_DURATION = "beatchord.durationText"
private const val EXTRA_EXPLICIT = "beatchord.explicit"
private const val EXTRA_IS_VIDEO = "beatchord.isVideo"
private const val EXTRA_VIDEO_ORIGIN = "beatchord.isVideoOrigin"

/** Whether this item was selected from a music-video row, even after conversion. */
val MediaItem.isVideoOrigin: Boolean
    get() = mediaMetadata.extras?.getBoolean(EXTRA_VIDEO_ORIGIN) == true ||
        mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true

fun MediaController.autoplaySectionStart(): Int = autoplaySectionStart(
    fromAutoplay = (0 until mediaItemCount).map { getMediaItemAt(it).fromAutoplay },
    currentIndex = currentMediaItemIndex,
)

/**
 * MP4-family containers (m4a/aac/amr/wma/...) store their header or trailing
 * metadata in a way that needs backward seeking to parse, which the
 * content:// route (ContentDataSource) doesn't reliably support — the same
 * bytes read fine as a plain file. Formats like flac/mp3/ogg/webm already
 * seek correctly through content:// and are left alone.
 */
private val DIRECT_FILE_URI_EXTENSIONS = setOf(
    "m4a", "m4b", "m4p", "mp4", "aac", "3ga", "3gp", "3gpp",
    "alac", "amr", "awb", "wma", "aif", "aiff", "ac3", "dts",
    // DSF keeps its tags at the end, so the extractor reads the tail first and
    // seeks back to the audio; DFF walks every chunk to the end before it
    // starts, and seeks back the same way.
    "dsf", "dff",
)

private fun resolvePlaybackUri(uriString: String, localPath: String?): String {
    if (localPath.isNullOrBlank() || !uriString.startsWith("content://")) return uriString
    val ext = localPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext !in DIRECT_FILE_URI_EXTENSIONS) return uriString
    val file = File(localPath)
    return if (file.exists() && file.canRead()) Uri.fromFile(file).toString() else uriString
}

/**
 * Where this song's audio actually is, or null when it has no file.
 *
 * [Song.localUri] is the primary answer: a library row carries the
 * `content://` URI MediaStore gave it, and a file imported from elsewhere
 * carries its `file://` path. [Song.videoId] doubles as the identity of a
 * library track — see [com.music.beatchord.data.LocalMediaRepository] — so it is
 * accepted when it is itself a URI. [localPath] is the last resort: a row that
 * lost its URI but still knows where the file lives.
 */
private fun Song.localPlaybackUri(): String? {
    val local = localUri
    if (!local.isNullOrBlank()) return local
    val id = videoId
    if (id.startsWith("content://") || id.startsWith("file://")) return id
    val path = localPath
    return path?.takeIf { it.isNotBlank() }?.let { Uri.fromFile(File(it)).toString() }
}

/**
 * Builds the player item for this song, or null when there is no local file to
 * play.
 *
 * Answered here, where the item is built, because everything downstream decides
 * what to do by the scheme the item arrives with — [androidx.media3.datasource.DefaultDataSource]
 * picks ContentDataSource or FileDataSource off it, and there is nothing else
 * behind it. A song with no file is refused rather than pointed at a network
 * copy: BeatChord plays what is on the device, and callers that build a queue
 * drop the ones that answer null — see [MediaController.playSongs].
 *
 * [localUri] is not only set by a folder read that just verified the file: it
 * also round-trips off the player's own item through [MediaItem.toSong], so an
 * item waiting in the live queue can carry a URI that has become stale since it
 * was created. Playback's own recovery path is what turns that into a skip.
 */
fun Song.toMediaItemOrNull(): MediaItem? {
    val uriString = localPlaybackUri() ?: return null
    return MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(resolvePlaybackUri(uriString, localPath))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                // The release this track came off, when whoever queued it knew.
                //
                // A native field rather than an extra because Media3 bundles this
                // one across the session on its own, and because the lock screen and
                // Android Auto both draw it — a track queued from an album page had
                // the name in hand all along and was arriving at those surfaces
                // without it. It is also what the Replay's album chart is counted
                // on: read back off the player, a track with no album here is a
                // track that cannot be filed under one.
                .setAlbumTitle(albumName)
                // Sized here rather than left as stored: this is what the lock
                // screen, the notification and Android Auto draw, all of them
                // large, and none of them go back for a better copy later.
                .setArtworkUri(artworkAt(NOTIFICATION_ART_PX)?.toUri())
                // System media surfaces (One UI's Now Bar, Android Auto, Assistant)
                // classify a session by its media type; untyped sessions get treated
                // as generic audio and lose the music-specific card.
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                // What a queue entry has to carry about itself: which section of
                // the queue it belongs to, whether it is playing off the device,
                // and how long the row that queued it said it runs. The uri above
                // answers the second question but does not survive the trip back
                // out — Media3 leaves a MediaItem's localConfiguration out of the
                // bundle it sends to a MediaController — so without this a track
                // playing from a file reaches the UI without its path.
                .setExtras(
                    bundleOf(
                        EXTRA_QUEUE_TIER to queueTier.name,
                        EXTRA_QUEUE_ENTRY_ID to queueEntryId,
                        EXTRA_FROM_AUTOPLAY to fromAutoplay,
                        EXTRA_RADIO_NAME to radioName,
                        EXTRA_PLAYBACK_SOURCE to playbackSource,
                        EXTRA_PLAYBACK_SOURCE_TYPE to playbackSourceType?.name,
                        EXTRA_PLAYBACK_SOURCE_ID to playbackSourceId,
                        EXTRA_LOCAL_URI to uriString,
                        EXTRA_LOCAL_PATH to localPath,
                        EXTRA_DURATION to durationText,
                        EXTRA_ARTIST_ID to artistId,
                        EXTRA_ALBUM_ID to albumId,
                        EXTRA_SET_VIDEO_ID to setVideoId,
                        EXTRA_EXPLICIT to isExplicit,
                        EXTRA_IS_VIDEO to isVideo,
                        EXTRA_VIDEO_ORIGIN to isVideoOrigin,
                    ),
                )
                .build(),
        )
        .build()
}

/**
 * [toMediaItemOrNull], for the callers that hold a list of songs and expect an
 * item back.
 *
 * A song with no file still gets an item, with no URI, so it fails immediately
 * and visibly in the queue rather than playing something else. Callers that can
 * drop it should use [toMediaItemOrNull] instead.
 */
fun Song.toMediaItem(): MediaItem = toMediaItemOrNull() ?: MediaItem.Builder()
    .setMediaId(videoId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(albumName)
            .setArtworkUri(artworkAt(NOTIFICATION_ART_PX)?.toUri())
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(false)
            .setIsBrowsable(false)
            .build(),
    )
    .build()

/**
 * Replaces the queue with [songs] and starts it.
 *
 * Suspending because of the mapping: [toMediaItemOrNull] is not the cheap struct
 * copy it looks like — it builds a URI and a metadata bundle per track. One
 * track's worth of that is nothing; a playlist's worth is hundreds of
 * milliseconds of it, and run inline it lands on the frame that handles the tap,
 * so the page freezes before the music starts. Built off the main thread and
 * handed to the player back on it, which is where Media3 requires the call to be
 * made.
 */
suspend fun MediaController.playSongs(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    // A queue started while shuffle is on goes in shuffled rather than being
    // played out of order — see [QueueShuffle]. The track the user picked still
    // leads, so it ends up at the top instead of at [startIndex].
    val shuffled = QueueShuffle.enabled.value
    val selected = songs[startIndex.coerceIn(songs.indices)]
    val items = withContext(Dispatchers.Default) {
        val queue = if (shuffled) {
            QueueShuffle.startingOrder(songs, startIndex.coerceIn(songs.indices))
        } else {
            songs
        }
        // A song with no file cannot play, so it never enters the queue. The
        // selected track still leads unless there is nothing of it to play, in
        // which case the first track that does have a file starts instead.
        queue.mapNotNull { it.toMediaItemOrNull() }
    }
    if (items.isEmpty()) return
    val start = if (shuffled) {
        0
    } else {
        items.indexOfFirst { it.mediaId == selected.videoId }.takeIf { it >= 0 } ?: 0
    }
    setMediaItems(items, start, 0L)
    prepare()
    play()
}

/**
 * The selected track is moved to the head when a new queue is shuffled, so
 * playback must begin there rather than at its index in the unshuffled list.
 */
internal fun queueStartIndex(requestedIndex: Int, itemCount: Int, shuffled: Boolean): Int =
    if (shuffled) 0 else requestedIndex.coerceIn(0, itemCount - 1)
