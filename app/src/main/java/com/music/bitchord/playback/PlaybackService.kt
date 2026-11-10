package com.music.bitchord.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.music.bitchord.data.TelemetryProvenance
import com.music.bitchord.playback.audio.usb.UsbDirectManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
import com.music.bitchord.playback.audio.DspChain
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.cast.CastPlayback
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.CastStream
import com.music.bitchord.playback.audio.PrecisionAudioSink
import com.music.bitchord.playback.dsd.DsdExtractorsFactory
import com.music.bitchord.playback.audio.DirectAudioProbe
import com.music.bitchord.playback.audio.OutputNegotiator
import com.music.bitchord.playback.audio.PcmEncoding
import com.music.bitchord.playback.audio.SourceDescriptor
import com.music.bitchord.playback.audio.bluetooth.BluetoothAudioTracker
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.ListeningRecorder
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.widget.MediaWidget
import com.music.bitchord.widget.MediaWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet


/** Session command used by the media notification's and the 4×1 widget's Favorite button. */
const val ACTION_TOGGLE_FAVORITE = "com.music.bitchord.action.TOGGLE_FAVORITE"

/** Session command used by the media notification's and the 4×1 widget's Shuffle button. */
const val ACTION_TOGGLE_SHUFFLE = "com.music.bitchord.action.TOGGLE_SHUFFLE"

/**
 * Session command carrying a rearrangement of the queue worked out by a
 * controller — see [QueueShuffle.reorderFromCommand].
 *
 * A command rather than the ordinary player call because the items a controller
 * can see have had their playback URIs stripped on the way out to it. The
 * permutation travels instead, and the session applies it to the items it holds.
 */
const val ACTION_REORDER_QUEUE = "com.music.bitchord.action.REORDER_QUEUE"

/** Where the rearrangement starts, and where each slot's new occupant stands now. */
const val EXTRA_REORDER_FROM = "bitchord.reorder.from"
const val EXTRA_REORDER_ORDER = "bitchord.reorder.order"

/**
 * Background playback via Media3. A [MediaLibraryService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry the `content://` or `file://` URI of a file on this device
 * — see [Song.toMediaItem] — and are opened by [DefaultDataSource], which
 * dispatches on the scheme. There is no remote stream behind any of them.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    /** Local Auto search results, and the rows behind folder and playlist children. */
    private val searchResults = ConcurrentHashMap<String, List<Song>>()
    private val songCache = ConcurrentHashMap<String, Song>()

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val bluetoothTracker by lazy { BluetoothAudioTracker(this) }
    private var currentAudioInputFormat: Format? = null
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            // Plugging something in moves the music to it. Always — a choice
            // made an hour ago about the speaker is not a standing instruction
            // to ignore the headphones now going in, and there is no row in the
            // picker for "go back to following the system", so a stuck choice
            // would be a picker that had quietly broken the obvious behaviour.
            AudioRouting.forget()
            requestOutputReconfiguration()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            // A chosen output that has been unplugged is no longer a choice.
            // Left set, its id matches nothing — and ids are reused, so it
            // would eventually match whatever device the framework hands that
            // number to next. See [AudioRouting].
            if (removedDevices.any { it.id == AudioRouting.selectedId.value }) {
                AudioRouting.forget()
            }
            requestOutputReconfiguration()
        }
    }

    /**
     * The player the session is on. Swaps with [spare] at every crossfade — see
     * [adoptPlayer] — so anything reading it must read it *now* rather than
     * capturing it.
     */
    private var player: ExoPlayer? = null

    /**
     * The idle player. Between transitions it holds nothing; to arm one,
     * [CrossfadeController] loads it with the queue positioned on the incoming
     * track.
     */
    private var spare: ExoPlayer? = null

    private var crossfade: CrossfadeController? = null

    /**
     * What the players currently in hand were built for.
     *
     * Read at construction rather than per buffer because it decides whether
     * Media3 may open a float AudioTrack at all, which cannot be changed under
     * a running renderer. Moving the setting therefore rebuilds both players.
     */
    private var configuredFloatOutput = false
    private var outputReconfigureJob: Job? = null

    /**
     * One audio-processor set per player, because both carry per-sink state — a
     * delay line, filter memory — that two sinks cannot share.
     *
     * The `A`/`B` pair is fixed to the players that own them; [activeFilter] and
     * [spareFilter] are the *roles*, and they trade places at every handoff
     * along with the players. Everything downstream talks in roles.
     */
    private val spatialAudioProcessorA = SpatialAudioProcessor()
    private val spatialAudioProcessorB = SpatialAudioProcessor()
    private val equalizerProcessorA = EqualizerProcessor()
    private val equalizerProcessorB = EqualizerProcessor()
    private val transitionFilterA = TransitionFilterProcessor()
    private val transitionFilterB = TransitionFilterProcessor()

    /**
     * Loudness normalization, one per player like the other stages — see
     * [LoudnessProcessor] for why it stopped being a single session effect.
     * Paired to the players through [activeFilter]'s role rather than tracked
     * as roles of their own: see [activeLoudness].
     */
    private val loudnessA = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private val loudnessB = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }

    /** The platform audio session currently advertised to system audio tools. */
    private var advertisedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    /**
     * Whether the format currently arriving at the active player's decoder
     * is Dolby Atmos (E-AC-3 JOC). Widening a JOC stream would fight the
     * object-based mix Dolby already spatializes, so the effect is forced
     * off for as long as this is true — see [applySpatialAudioEnabled].
     */
    private var activeTrackIsDolbyAtmos = false

    private var activeFilter: TransitionFilterProcessor = transitionFilterA
    private var spareFilter: TransitionFilterProcessor = transitionFilterB

    /**
     * The loudness stage on the session player. Read off [activeFilter]'s role,
     * which every handoff and rebuild already keeps right, so the two can never
     * disagree about which sink is which.
     */
    private fun activeLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessA else loudnessB

    private fun spareLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessB else loudnessA

    /** Automix's DSP analyzer — see [com.music.bitchord.playback.smart.TrackAnalyzer]. */
    private val trackAnalyzer = com.music.bitchord.playback.smart.TrackAnalyzer(this)

    /** Shared with the crossfade's standby player, so both open files the same way. */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    /** Last sampled position of the playing track, in seconds. */
    private var lastPositionSeconds = 0L

    /** When the current track was chosen, for the time-to-first-audio log. */
    private var trackSelectedAt: Long? = null

    /**
     * The last track that actually made a sound, which is how [recoverFrom]
     * tells "this never started" from "this died in the middle".
     *
     * Deliberately a mediaId rather than a flag that gets cleared on every
     * transition. A retry *is* a seek, so a transition fires for it — the same
     * trap [recoveries] fell into — and a flag cleared there would make a track
     * that failed twenty seconds in look like a track that never began, which is
     * precisely the case that must not be skipped past. Holding the id instead
     * means the queue moving to a different track invalidates it for free, and
     * nothing has to be cleared anywhere.
     *
     * The one thing it is deliberately wrong about: a track played earlier in
     * the session and returned to — by repeat-one, by the previous button —
     * still counts as audible, so a later failure to start it is only retried
     * and not skipped. That errs towards leaving the listener where they are,
     * which is the safe direction for a queue.
     */
    private var audibleMediaId: String? = null

    /**
     * How many tracks in a row have been skipped for a plain playback error,
     * reset the moment anything actually plays — see [MAX_CONSECUTIVE_SKIPS].
     */
    private var consecutiveErrorSkips = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val localBitrateCache = ConcurrentHashMap<String, Int>()

    /**
     * What plays on a Cast receiver, when the music is on one. The phone's
     * player keeps the queue and is held paused; this is what the session
     * player answers with in the meantime. See [CastPlayback].
     */
    private val castPlayback = CastPlayback(
        scope = scope,
        localPlayer = { player },
        resolve = ::resolveForCast,
        say = { message -> Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() },
    )

    /** Commands exposed as the secondary buttons on the media notification. */
    private val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val reorderQueueCommand = SessionCommand(ACTION_REORDER_QUEUE, Bundle.EMPTY)

    private var favoriteActionJob: Job? = null

    /** Index in the live queue represented by entry zero of the persisted window. */
    private var persistedQueueStart = 0

    /**
     * The last repeat mode seen, so [onRepeatModeChanged] can tell which
     * direction the change went in: the callback reports where the player has
     * arrived, and leaving repeat-all is the half that has to restore.
     */
    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    private var serviceLyricsJob: Job? = null
    private var serviceLyrics: List<LyricLine>? = null
    private var lastPublishedSubtitle: String? = null
    private var lyricsTickerJob: Job? = null

    /**
     * Everything the service books against the player it is currently on.
     *
     * A field rather than an anonymous object registered once, because the
     * session moves between two players at every crossfade — see [adoptPlayer]
     * — and this has to move with it. It is attached to exactly one player at a
     * time: the one [player] names.
     */
    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val activePlayer = this@PlaybackService.player ?: return
            if (player === activePlayer) updateAudioEffectSession(activePlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // The only number that describes what a listener actually
            // waits through. Every other timing in this app measures one
            // leg of getting a track started — a resolve, a client walk, an
            // extraction — and a leg being fast has repeatedly turned out
            // to say nothing about whether sound arrived quickly, because
            // the legs that were measured were the ones running in the
            // background for tracks nobody was waiting on.
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "BitChord",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
                // Sound is out of the speaker, so whatever happens to this track
                // from here is a failure mid-song. Also the one thing that can
                // say the queue is not simply unplayable end to end.
                audibleMediaId = exoPlayer.currentMediaItem?.mediaId
                consecutiveErrorSkips = 0
            }
            savePlaybackState(exoPlayer)
            // Not strictly needed for the glyph — onPlayWhenReadyChanged has
            // already flipped that — but this is where hasNext/hasPrevious and
            // the artwork are known to be settled.
            publishWidgetState()

            // The listening record has to be told a pause happened, not merely
            // stop being told about play: its sampler measures the gap between
            // ticks, and an unclosed one across a pause is an afternoon on the
            // lock screen arriving as an afternoon of listening.
            if (!isPlaying) ListeningRecorder.onStopped()

            if (isPlaying) startLyricsTicker() else stopLyricsTicker()
        }

        /**
         * The one callback the home-screen widget can be driven from.
         *
         * `onIsPlayingChanged` is too late by a beat: a widget keyed on that
         * answers a tap on play by leaving the play glyph exactly where it was,
         * the control reads as broken, and the obvious response is to tap it
         * again. `playWhenReady` flips on the command, not on the audio, which
         * is what the media notification shows too.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Something started the phone's own player while a receiver is the
            // speaker — an internal path, since the session player's door
            // already sends a listener's play to the receiver. The phone goes
            // quiet again and the receiver is asked instead.
            if (playWhenReady) player?.let(castPlayback::onLocalStartedPlaying)
            publishWidgetState(playing = playWhenReady)
        }

        /** A seek is the one change to a playing track that no other callback reports. */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                updateLyricSubtitle()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return

            // A receiver, if it is the speaker, is put on whatever became
            // current — or it is the one that moved, and this is the phone
            // catching up, which [CastPlayback] tells apart by track.
            castPlayback.onLocalTransition(mediaItem, reason)

            // No crossfade case to allow for here any more. A blended advance
            // never reaches this callback — the incoming track starts as the
            // *first* item of the other player — so it is booked by
            // [adoptPlayer] instead, and what is left arriving here is only ever
            // ExoPlayer moving the queue on by itself, a repeat, or a skip.
            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
            // Prune consumed USER_QUEUE entries when playback enters CONTEXT,
            // keeping the timeline clean and ensuring REPEAT_MODE_ALL loops only CONTEXT items.
            QueueCoordinator.consumePlayedUserQueue(exoPlayer)

            loadLyricsForCurrentTrack()
            if (exoPlayer.isPlaying) startLyricsTicker()
            refreshCustomLayouts()
        }

        /**
         * A failed read is not a failed track: nothing else in this service
         * ever calls [Player.prepare] again, so before this existed a single
         * read error left the player in `STATE_IDLE` for good. The notification kept the song on it, the play button kept
         * being pressed, and nothing happened — which is exactly what a
         * broken app looks like from the outside.
         */
        override fun onPlayerError(error: PlaybackException) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            recoverFrom(error, exoPlayer)
        }

        // Nothing follows the last track, so there is no transition to
        // pause on — the queue simply runs out and the timer is spent.
        override fun onPlaybackStateChanged(state: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                lastPositionSeconds = 0
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            lastRepeatMode = repeatMode
            castPlayback.onLocalRepeatChanged(repeatMode)
            // Repeat-one makes the "next" track this one again.
            player?.let { activeLoudness().nextMediaId = nextMediaIdOf(it) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // A queue edit can change what follows gaplessly, and the loudness
            // stage switches to that track on its own at the boundary.
            activeLoudness().nextMediaId = nextMediaIdOf(exoPlayer)
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                castPlayback.onLocalQueueChanged()
                saveQueueSnapshot(exoPlayer)
                refreshCustomLayouts()
            }
        }
    }

    /** Registered alongside [playbackListener], and moved with it. */
    private val formatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Taken off the event's own window rather than off the player,
            // so it names the track this format arrived for even if the
            // queue has moved on again since. See [audioFormatFor].
            audioFormatFor = eventTime.mediaId()
            // Ground truth for a real-device listening test: this is the
            // renderer's own Format, straight off the decoder with none of
            // the app's own processing in between, so it's the one line that
            // can prove a "hi-res" session never quietly slid onto a
            // lower-rate path mid-track. `adb logcat -s DECODE:I`.
            val measured = format.measure()
            val khz = measured.sampleRateHz
                ?.let { "%.1fkHz".format(Locale.ROOT, it / 1000.0) } ?: "?kHz"
            val kbps = format.bitrate.takeIf { it > 0 }
                ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
            val depth = measured.bitDepth?.let { "${it}-bit" } ?: "?-bit"
            val channels = measured.channels?.let { "${it}ch" } ?: "?ch"
            TrackLog.i(
                "DECODE",
                "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth $channels",
                about = audioFormatFor,
            )
            activeTrackIsDolbyAtmos = NerdStats.isDolbyAtmosMime(format.sampleMimeType)
            applySpatialAudioEnabled()
            publishNerdStats()
            currentAudioInputFormat = format
            applyOutputRoute()
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            AudioOutputStatus.publishDecoder(decoderName)
            TrackLog.i(
                "AUDIO_OUT",
                "decoder=$decoderName initialized in ${initializationDurationMs}ms",
                about = eventTime.mediaId(),
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            AudioOutputStatus.publishAudioTrack(
                encoding = audioTrackConfig.encoding,
                sampleRateHz = audioTrackConfig.sampleRate,
                bufferSize = audioTrackConfig.bufferSize,
            )
            TrackLog.i(
                "AUDIO_OUT",
                "AudioTrack ${audioTrackConfig.sampleRate}Hz encoding=${audioTrackConfig.encoding}",
                about = eventTime.mediaId(),
            )
        }

    }

    /**
     * Which track an analytics event is about, taken off the event's own window.
     *
     * The player has moved on by the time some of these arrive — a format
     * change for the outgoing track lands after the transition — so its
     * `currentMediaItem` names the wrong one. The event carries the timeline it
     * was raised against, which does not.
     */
    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId

    override fun onCreate() {
        super.onCreate()

        // First, because everything below assumes it is standing up fresh and
        // one of the two ways this service starts does not give it that.
        //
        // A cold start runs in a new process, where the session-scoped state
        // these two hold is empty anyway. A *warm* one doesn't: closing the app
        // destroys the service while Android keeps the process to reuse, so
        // without this a second service inherits the first one's idea of what
        // was playing. That showed as a "Lossless" badge over a player holding
        // no bytes.
        NerdStats.forgetLastSession()
        localBitrateCache.clear()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        // Both the player screen and notification route their Shuffle command
        // here. Observe its state so either surface's icon follows the queue
        // edit once that single service-side transaction has completed.
        scope.launch {
            QueueShuffle.enabled
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            LocalPlaylistStore.playlists
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }

        bluetoothTracker.start()
        scope.launch {
            bluetoothTracker.telemetry.collect {
                applyOutputRoute()
            }
        }

        // Every track is a file on this device, so the chain is plain: the
        // default data source dispatches by scheme — `file://` for a path
        // MediaStore exposed, `content://` for a row it did not — and
        // [DsdExtractorsFactory] adds DSF/DFF to the stock extractors, which
        // Media3 cannot open at all. A DSD file leaves the extractor as float PCM.
        val dataSourceFactory = DefaultDataSource.Factory(this)
        mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, DsdExtractorsFactory())
            .setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy())

        configuredFloatOutput = shouldEnableFloatOutput()
        val exoPlayer = buildPlayer(
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val sparePlayer = buildPlayer(
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = exoPlayer
        spare = sparePlayer
        // Both sinks feed the same session id, so the system equalizer and any
        // other effect attached to the app applies to whichever player happens
        // to be audible. Without it a crossfade would audibly change EQ halfway
        // through, and again at every handoff.
        sparePlayer.audioSessionId = exoPlayer.audioSessionId
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null)
        applyOutputRoute()

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        observeSettings()
        watchSleepTimer()
        val restored = restoreLastQueue(exoPlayer)
        if (restored) {
            publishWidgetState()
        } else {
            MediaWidgetSnapshot.save(this, MediaWidgetSnapshot.EMPTY)
            MediaWidget.refresh(this)
        }
        lastRepeatMode = exoPlayer.repeatMode
        exoPlayer.addListener(playbackListener)

        // Only the analytics listener reports the format the audio renderer was
        // configured with. Treated as a trigger rather than a source: the
        // publisher reads the format off the player, so it can't go stale
        // against the track the bitrate is looked up for.
        exoPlayer.addAnalyticsListener(formatListener)

        reportProgress()

        val controller = createCrossfadeController()
        crossfade = controller
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            newSessionPlayer(exoPlayer, controller),
            MediaLibraryCallback(),
        )
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()
        refreshCustomLayouts()

        // Last, so a receiver that outlived the app is adopted by a service
        // whose queue is already restored.
        CastController.ensureStarted(this)
        CastController.attach(castPlayback)
    }

    private fun newSessionPlayer(player: Player, crossfade: CrossfadeController) = SessionPlayer(
        player,
        crossfade,
        cast = castPlayback,
    ) { lastPublishedSubtitle }

    /**
     * A Cast receiver cannot open a file on this phone, and this app plays
     * nothing else, so there is no remote stream to hand it. [CastPlayback]
     * refuses the track with its own message rather than loading it silent.
     */
    private suspend fun resolveForCast(item: MediaItem): CastStream? = null

    private fun createCrossfadeController() = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            // "Incoming" and "outgoing" here are session roles, not players:
            // the session player's sink and the spare's. The controller maps
            // its two tracks onto them according to which side of the
            // mid-blend handoff it is on — so these read the role fields fresh
            // on every call rather than closing over an instance that will
            // have changed hands.
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    spareFilter.setCutoffs(lowPassHz, highPassHz)

                override fun incomingLeadMs(): Long = activeFilter.leadUs / 1000

                override fun outgoingLeadMs(): Long = spareFilter.leadUs / 1000

                override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    activeFilter.setEcho(delaySeconds, send, dry)

                override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    spareFilter.setEcho(delaySeconds, send, dry)

                override fun parkEchoes() {
                    transitionFilterA.parkEcho()
                    transitionFilterB.parkEcho()
                }
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
            // The standby is still the spare when this runs — it only becomes
            // the session player at the handoff — so the incoming track is
            // levelled on its own sink before it renders a frame, and the
            // outgoing track keeps its own gain for the rest of the blend.
            onArmIncoming = { item, nextId -> spareLoudness().track(item.mediaId, nextId) },
            // Both sinks, not a role: the trim is about what the pair sums to.
            onBlendHeadroom = { trim ->
                loudnessA.setBlendTrim(trim)
                loudnessB.setBlendTrim(trim)
            },
        )

    /** Favorite and Shuffle: the only actions shown on the phone notification. */
    private fun notificationButtons(): List<CommandButton> {
        val song = player?.currentMediaItem?.toSong()
        val favorite = CommandButton.Builder(
            if (song != null && LocalPlaylistStore.isFavorite(song)) {
                CommandButton.ICON_HEART_FILLED
            } else {
                CommandButton.ICON_HEART_UNFILLED
            },
        )
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val shuffleEnabled = QueueShuffle.enabled.value
        val shuffle = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOf(favorite, shuffle)
    }

    /** Android Auto / Automotive controllers currently connected to the session. */
    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    /**
     * Pushes the current button state to every surface. The buttons are the same
     * everywhere — Favorite and Shuffle — so the notification override and the
     * broadcast layout carry the same list.
     */
    private fun refreshCustomLayouts() {
        val session = mediaSession ?: return
        val buttons = notificationButtons()
        session.setCustomLayout(buttons)
        session.getMediaNotificationControllerInfo()?.let {
            session.setCustomLayout(it, buttons)
        }
    }



    private fun toggleShuffleFromSession() {
        player?.let(QueueShuffle::toggle)
        refreshCustomLayouts()
    }


    /**
     * The notification's and the widget's Favorite button, backed by the local
     * Favorites playlist rather than a catalogue rating.
     */
    private fun toggleFavoriteFromNotification(mediaId: String) {
        favoriteActionJob?.cancel()
        val song = player?.currentMediaItem?.takeIf { it.mediaId == mediaId }?.toSong() ?: return
        favoriteActionJob = scope.launch(Dispatchers.IO) {
            LocalPlaylistStore.toggleFavorite(song)
            withContext(Dispatchers.Main) {
                refreshCustomLayouts()
                publishWidgetState()
            }
        }
    }

    /**
     * Both players, built identically. Only [ownsSession] differs, and only at
     * construction — it moves at every handoff, see [setSessionOwner].
     *
     * They share the media source factory, so whichever one is arming opens the
     * file the way the other one does — same extractors, same data source.
     */
    private fun buildPlayer(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        filter: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(spatial, equalizer, filter, loudness))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        // Back restarts the track once you're this far into it; only a
        // press before that steps to the previous one.
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    /**
     * Moves the session onto the player the crossfade has just started the
     * incoming track on. This is the whole of the handoff: no seek, no
     * re-buffer, and no audio rendered twice.
     *
     * Order matters in one place — focus is released on the outgoing player
     * *before* the incoming one asks for it, so the app never holds two focus
     * requests at once and never briefly holds none.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)
        // The fields move before the listeners are attached, so anything the
        // first callback reads already describes the new arrangement.
        player = incoming
        spare = outgoing
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))

        // The queue moving on used to arrive here as an item transition on the
        // one player that owned the queue. It cannot any more — the incoming
        // track started as its own player's *first* item, which fires on a
        // player nothing was listening to yet — so the bookkeeping that hung off
        // that callback is driven explicitly instead. Without this the crossfade
        // would silently stop writing history and stop honouring "sleep after
        // this song".
        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )
    }

    /**
     * Only one player may handle audio focus at a time.
     *
     * Two focus-handling players in one process fight each other: the standby
     * taking focus as it starts would have Media3 pause the player that lost it,
     * cutting the outgoing track dead instead of fading it. Focus follows the
     * session, and so does "becoming noisy" — unplugging headphones should pause
     * the song you are listening to, which is whichever one the session is on.
     */
    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    /**
     * Publishes the standard music-session lifecycle used by system and
     * third-party audio controllers. Keep the session open while playback is
     * preparing as well as while it is audible, so an effect can attach before
     * the first decoded buffer reaches the output device.
     */
    private fun updateAudioEffectSession(activePlayer: ExoPlayer) {
        val sessionId = activePlayer.audioSessionId
        val shouldAdvertise = activePlayer.playWhenReady &&
            (activePlayer.playbackState == Player.STATE_BUFFERING ||
                activePlayer.playbackState == Player.STATE_READY) &&
            sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0

        if (!shouldAdvertise) {
            closeAudioEffectSession()
            return
        }
        if (advertisedAudioEffectSessionId == sessionId) return

        closeAudioEffectSession()
        advertisedAudioEffectSessionId = sessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        val sessionId = advertisedAudioEffectSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        advertisedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    /**
     * Where a tap on the session lands. Media3 uses this both as the media
     * notification's contentIntent and as the session activity handed to the
     * platform MediaSession.
     *
     * This is not cosmetic on One UI: Samsung's Now Bar / Live Notification
     * chip is a launcher for the session, so a session that advertises nowhere
     * to go is skipped and only the plain shade notification survives. Same
     * reason the notification itself was previously un-tappable.
     */
    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // MainActivity is singleTask, so this resumes the existing task
            // rather than stacking a second copy of the UI.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Everything that has to happen when a different song becomes the one
     * playing: the local listening record, the sleep timer, loudness and the
     * surfaces that name the track.
     *
     * Called from two places, and it has to be, because there are two ways for
     * the current song to change. ExoPlayer's own item transition covers the
     * ordinary ones — the queue advancing, a skip, a repeat. A crossfade covers
     * none of them: the incoming track starts life as the *first* item of the
     * other player, which fires a transition on a player nothing is listening
     * to yet, so [adoptPlayer] calls this by hand at the handoff. Before that
     * split existed this logic lived inside the callback, and moving to two
     * players would have silently stopped every crossfaded track being
     * recorded.
     *
     * @param previousEnded whether the song being replaced ran to its end, as
     *   opposed to being skipped past. Only an ended song is a listen.
     * @param alreadyAudible whether the track was already sounding when it
     *   became current, which is only true of a crossfade handoff.
     */
    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        currentAudioInputFormat = null
        NerdStats.onTrackTransition()
        AudioOutputStatus.onTrackTransition()
        mediaItem?.mediaId?.let { id ->
            resolveLocalBitrate(id, mediaItem)
        }

        // A crossfade handoff never fires [formatListener] for the entering
        // track — [CrossfadeController] starts its decoder during ARMING, while
        // the listener is still on the outgoing player, and it isn't reattached
        // until this handoff, by which point the format has already been
        // reported once and won't be again. Until the decoder says otherwise,
        // nothing is claimed about this track's codec.
        activeTrackIsDolbyAtmos = false
        applySpatialAudioEnabled()

        setupLoudness(mediaItem?.mediaId)

        // Keep a real, bounded history in the player rather than merely hiding
        // old rows in Compose. MediaController mirrors the playlist across the
        // session boundary, persistence snapshots it, and crossfade duplicates
        // it onto the standby player, so letting completed entries accumulate
        // made every one of those paths progressively more expensive.
        val expiredHistory = queueHistoryTrimCount(exoPlayer.currentMediaItemIndex)
        if (expiredHistory > 0) {
            if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
                // Repeat-all still means the whole queue. Rotate history that
                // has fallen out of the visible 25-song window to the end
                // instead of deleting it; it becomes upcoming again in the
                // same order and the loop can continue indefinitely.
                repeat(expiredHistory) {
                    exoPlayer.moveMediaItem(0, exoPlayer.mediaItemCount - 1)
                }
            } else {
                exoPlayer.removeMediaItems(0, expiredHistory)
            }
        }
        // A *different* track is a clean slate for [recoverFrom], and so is the
        // same track becoming current for any reason other than that method's
        // own retry. The distinction is the whole of the reported loading loop.
        //
        // This was an unconditional clear, and the reasoning that the count
        // exists to stop one broken file looping rather than to hold a grudge is
        // right about the listener pressing play again, which is why it is kept
        // below. What it missed is that "a track became current" is not the same
        // event as "something other than the retry happened": ExoPlayer fires a
        // transition for PLAYLIST_CHANGED and for SEEK, and [recoverFrom]'s
        // recovery *is* a seek — so the counter was reset by the very retries it
        // was counting. [retryingMediaId] is the one case that must not reset;
        // everything else still does.
        val becameCurrent = mediaItem?.mediaId
        if (becameCurrent == null || becameCurrent != retryingMediaId) {
            recoveries.clear()
        }
        retryingMediaId = null

        // Where the wait starts, for the log in onIsPlayingChanged — unless
        // there was no wait. A crossfaded track has been audible for as long as
        // it has been current, so `onIsPlayingChanged` will never fire for it
        // and an armed timer would sit there until some unrelated buffering
        // blip tripped it, reporting a wait of seconds for a track that started
        // instantly. Measured one at 16871ms.
        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "BitChord",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem?.mediaId,
            )
            // The other way a track becomes audible. `onIsPlayingChanged` never
            // fires across a crossfade handoff — the incoming track has been
            // sounding since before it was current — so without this a blended
            // advance would leave every track looking like it never started.
            audibleMediaId = mediaItem?.mediaId
            consecutiveErrorSkips = 0
        }
        // And the same instant on the wall clock, which is the one
        // logcat stamps its lines with — see [TrackLog].
        mediaItem?.mediaId?.let(TrackLog::onTrackStarted)
        TrackLog.d(
            "BitChord",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
        )

        lastPositionSeconds = 0

        // The local record needs the transition even when the track
        // id doesn't change: repeat-one plays the same song again, and without
        // this the second play through is a continuation of the first and is
        // never counted.
        ListeningRecorder.onStopped()
        val ended = previousEnded

        // The renderer is still configured for the track that just ended at
        // this point. Clear its measurements so a new lossy track does not
        // briefly inherit the previous track's Lossless/Atmos line.
        NerdStats.current.value = null

        // "Sleep after this song": the queue moving on by itself is the
        // moment the track the user meant has finished. REPEAT counts
        // too, or the timer would never fire with repeat-one on.
        if (ended && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        savePlaybackState(exoPlayer)
        // Covers crossfades too: a blended advance never reaches
        // onMediaItemTransition, and [adoptPlayer] calls this handler by hand.
        publishWidgetState()
        loadLyricsForCurrentTrack()
        if (exoPlayer.isPlaying) startLyricsTicker()
    }


    /**
     * How many times each track has been picked up off the floor, so a file
     * that fails the same way every time stops rather than loops. Reset when
     * the queue genuinely moves on, not when a track merely re-prepares.
     */
    private val recoveries = mutableMapOf<String, Int>()

    /**
     * The track [recoverFrom] is about to seek-and-prepare, so that the
     * transition its own retry may fire can be told from the queue moving on or
     * the listener asking again — see [onTrackBecameCurrent]. Cleared by the
     * transition it describes.
     */
    private var retryingMediaId: String? = null

    /**
     * Media3's retry budget, with one difference: a load error that cannot
     * succeed on a second attempt does not get one.
     *
     * Every track is a file here, so the load failures that happen are a missing
     * file, a revoked permission, or a provider that will not open the URI.
     * Retrying any of those reopens the identical path, and the recovery path in
     * [recoverFrom] is what moves the queue on instead. Everything else keeps
     * the default behaviour, which is right: a read that failed once is worth
     * another go.
     */
    private class PermanentAwareLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (isPermanent(loadErrorInfo.exception)) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        private fun isPermanent(error: Throwable?): Boolean {
            var cause = error
            var depth = 0
            while (cause != null && depth++ < CAUSE_DEPTH) {
                if (cause is java.io.FileNotFoundException || cause is SecurityException) return true
                cause = cause.cause?.takeIf { it !== cause }
            }
            return false
        }

        private companion object {
            /** Media3 and the coroutine machinery both wrap; nothing nests deeper than this. */
            const val CAUSE_DEPTH = 8
        }
    }

    /**
     * Puts a track that died mid-read back on its feet, or moves past it.
     *
     * There is no stream to re-resolve any more: the item in the timeline names
     * a file, and if that file is gone or unreadable the next attempt reopens
     * the identical path. So this keeps its position and retries a bounded
     * number of times, then leaves the track behind — [skipPastUnplayable] does
     * the skipping, and [skipReason] decides whether the track or the queue is
     * at fault.
     *
     * The position is kept: this should look like a hiccup, not like the song
     * starting over.
     */
    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val position = player.currentPosition.coerceAtLeast(0L)
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "BitChord",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )
        val givingUp = attempts > MAX_RECOVERIES
        // A track that has never made a sound has *failed to start*, whatever
        // the error was, and the queue should move past it once the attempts are
        // spent — see [skipReason]. Read here rather than after the delay below,
        // because the seek-and-prepare of an unrelated recovery could move the
        // player on in between.
        val neverStarted = audibleMediaId != mediaId
        if (givingUp) {
            TrackLog.w("BitChord", "$mediaId has failed $attempts times; leaving it alone", about = mediaId)
        }
        scope.launch(TrackLog.about(mediaId)) {
            // Long enough for the released source to let go of the file, short
            // enough to read as a stutter.
            delay(RECOVERY_DELAY_MS)
            if (givingUp) {
                // A track nobody can play must not leave the player parked in
                // IDLE on it. Nothing else in this service calls prepare() again,
                // so before this the queue simply stopped: the notification kept
                // showing the song, the play button kept doing nothing, and from
                // the outside that is indistinguishable from a hung app.
                skipReason(error, neverStarted, attempts)?.let { reason ->
                    withContext(Dispatchers.Main) { skipPastUnplayable(mediaId, reason) }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = this@PlaybackService.player ?: return@withContext
                if (player.currentMediaItem?.mediaId != mediaId) return@withContext
                TrackLog.d("BitChord", "retrying $mediaId from ${position}ms")
                // Claimed before the seek, so the transition it may fire is
                // recognised as this retry rather than read as a fresh start and
                // handed a fresh attempt budget.
                retryingMediaId = mediaId
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
            }
        }
    }


    /**
     * Why a track that has run out of attempts should be left behind, or null
     * to park on it as the queue used to.
     *
     * The error itself is not classified: a missing file, a revoked permission
     * and a file the decoder cannot read are all, from the listener's side, the
     * same event — they pressed play and nothing happened. What is consulted is
     * [neverStarted], because the one failure that must not move the queue is
     * the one in the middle of a song someone is listening to: the attempts are
     * spent, the recovery has already put them back where they were twice, and
     * jumping to the next track at that point would take the song away rather
     * than rescue it.
     *
     * [MAX_CONSECUTIVE_SKIPS] is the floor under the rest. A run of missing
     * files fails identically, so without a limit the queue would walk to the
     * end of itself and hand back something the listener no longer recognises.
     * Stopping instead leaves the error on the player, which is what puts a
     * message on screen. The count only resets when something actually plays,
     * so a working track anywhere in a bad patch restores the full allowance.
     */
    private fun skipReason(
        error: PlaybackException,
        neverStarted: Boolean,
        attempts: Int,
    ): String? {
        if (!neverStarted) return null
        if (consecutiveErrorSkips >= MAX_CONSECUTIVE_SKIPS) {
            TrackLog.w(
                "BitChord",
                "$MAX_CONSECUTIVE_SKIPS tracks in a row failed to start; " +
                    "this is the queue or the files, not the track — stopping here",
            )
            return null
        }
        consecutiveErrorSkips++
        return "failed to start after $attempts attempts (${error.errorCodeName})"
    }

    /**
     * Leave a track that cannot be opened and carry on down the queue.
     *
     * The item is left in place rather than removed: the listener queued it, and
     * the file may come back — a folder restored, a permission granted, a card
     * remounted. Removing it would quietly rewrite a queue on the strength of a
     * one-off failure.
     *
     * Whether a failure has earned this at all is [skipReason]'s decision, not
     * this method's; by here the answer is yes.
     *
     * With nothing after it there is nowhere to go, and stopping is then the
     * correct end state rather than a failure to recover: the error stays on the
     * player, which is what puts a message in front of the listener — see
     * `rememberPlayerState` in
     * [PlayerConnection][com.music.bitchord.playback.PlayerConnection].
     */
    private fun skipPastUnplayable(mediaId: String, reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) return
        if (!exoPlayer.hasNextMediaItem()) {
            TrackLog.w("BitChord", "$reason — and nothing after it in the queue", about = mediaId)
            return
        }
        TrackLog.w("BitChord", "$reason — skipping to the next track", about = mediaId)
        exoPlayer.seekToNextMediaItem()
        // No play() here: an error does not clear playWhenReady, so prepare()
        // resumes exactly as far as the listener had asked for. Calling play()
        // would un-pause a queue they had paused.
        exoPlayer.prepare()
    }

    /**
     * The track [ExoPlayer.getAudioFormat] is currently describing.
     *
     * `audioFormat` is a property of the *renderer*, not of the queue item, and
     * it keeps naming the outgoing track's codec until the renderer has read a
     * sample of the incoming one. Anything that asks "what is playing right
     * now" in the moments after a transition is therefore told about the track
     * before it, which is why the statistics readout is refreshed off the
     * analytics event's own window rather than off the player.
     */
    private var audioFormatFor: String? = null

    /**
     * Depth, rate and channel count for one stream, taken from the stream.
     *
     * Kept as a value rather than read field-by-field off [Format] because
     * for some containers the fields are not all in the same place — see
     * [measure].
     */
    private class Measured(
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int?,
    ) {
        /**
         * What the samples cost per second once decoded, in kbps.
         *
         * `16-bit · 44.1 kHz · stereo` is 1411, `24-bit · 96 kHz · stereo` is
         * 4608 — the figures Tidal, Qobuz and Apple Music all put next to a
         * lossless track, and the only bitrate that means anything for one.
         * A FLAC's *compressed* rate is a property of how compressible that
         * particular recording was, so two copies of the same master at the
         * same quality report different numbers and the comparison the reader
         * is trying to make doesn't survive it.
         *
         * Null unless all three are known: two thirds of this product is not
         * a bitrate.
         */
        val pcmBitrateKbps: Int?
            get() {
                val depth = bitDepth ?: return null
                val rate = sampleRateHz ?: return null
                val ch = channels ?: return null
                return (depth.toLong() * rate * ch / 1000).toInt().takeIf { it > 0 }
            }
    }

    /**
     * What the renderer was handed, with the gaps its container left filled in
     * from the stream's own header.
     *
     * A container is free not to state any of this, and one in use here
     * doesn't: Tidal's FLAC arrives inside MP4, whose audio sample entry
     * carries the sample rate as a 16.16 fixed-point field and is written
     * zero, the escape the FLAC-in-ISOBMFF spec allows precisely because the
     * real figure lives in the `dfLa` box below it. Media3 passes that box
     * through as initialization data without folding it back into the
     * `Format`, so `sampleRate` reached the stats line as 0 and `pcmEncoding`
     * as unset — `audio/flac 0.0kHz ?-bit`, on a track that is plain
     * 16-bit/44.1kHz and displayed as such everywhere else.
     *
     * Every figure here still comes from the bytes being decoded. This is not
     * a fallback to what a source claimed; it is the same stream read at the
     * one layer that always states it, because a FLAC frame cannot be decoded
     * without it.
     */
    private fun Format.measure(): Measured {
        val streamInfo = flacStreamInfo()
        // The renderer's PCM encoding first: it is the one that would show a
        // 24-bit master being truncated on the way to the sink, which is the
        // whole reason the figure is on screen. STREAMINFO describes the file,
        // so it can only answer what the file holds.
        //
        // Float is the exception, and has to be, because it is a carrier
        // rather than a measurement. `MediaCodecAudioRenderer.getMediaFormat`
        // asks the decoder for float output whenever the sink reports it can
        // take one, so a plain 16-bit/44.1kHz file comes back through this
        // path reading 32-bit exactly as readily as a master does — and then
        // gets called Hi-Res Lossless, and wears the shine under the
        // transport, on the strength of the container it happened to travel
        // in. Nothing about a float buffer says how many bits were in the
        // samples poured into it, so it is not asked.
        val rendererDepth = if (pcmEncoding == C.ENCODING_PCM_FLOAT) null else bitDepthOf(pcmEncoding)
        return Measured(
            sampleRateHz = sampleRate.takeIf { it > 0 } ?: streamInfo?.sampleRateHz,
            channels = channelCount.takeIf { it > 0 } ?: streamInfo?.channels,
            bitDepth = rendererDepth ?: streamInfo?.bitDepth,
        )
    }

    /** Sample rate, channel count and bit depth as a FLAC STREAMINFO block states them. */
    private class FlacStreamInfo(val sampleRateHz: Int, val channels: Int, val bitDepth: Int)

    /**
     * The STREAMINFO block carried in this format's initialization data, for a
     * FLAC stream that has one.
     *
     * The offset is found rather than assumed. Media3 hands the block over
     * with a `fLaC` marker in front of it from both the MP4 and the raw path,
     * but that is its business and not something worth depending on, so all
     * three shapes — marker, bare block header, bare body — are recognised by
     * looking for the header itself: a metadata block type of 0 (STREAMINFO)
     * followed by a 24-bit length of 34.
     */
    private fun Format.flacStreamInfo(): FlacStreamInfo? {
        if (sampleMimeType != MimeTypes.AUDIO_FLAC) return null
        val data = initializationData.firstOrNull() ?: return null
        fun byteAt(i: Int) = data[i].toInt() and 0xFF
        fun headerAt(i: Int) = data.size > i + 3 &&
            (byteAt(i) and 0x7F) == 0 &&
            byteAt(i + 1) == 0 && byteAt(i + 2) == 0 && byteAt(i + 3) == STREAM_INFO_BYTES
        val body = when {
            data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC" ->
                if (headerAt(4)) 8 else 4
            headerAt(0) -> 4
            else -> 0
        }
        if (data.size < body + STREAM_INFO_BYTES) return null
        fun at(i: Int) = byteAt(body + i)
        // STREAMINFO opens with two 16-bit block sizes and two 24-bit frame
        // sizes — ten bytes — and then packs, without alignment, a 20-bit
        // sample rate, a 3-bit channel count and a 5-bit sample depth, the
        // last two both stored one less than they mean.
        val sampleRate = (at(10) shl 12) or (at(11) shl 4) or (at(12) shr 4)
        val channels = ((at(12) shr 1) and 0x07) + 1
        val bitDepth = (((at(12) and 0x01) shl 4) or (at(13) shr 4)) + 1
        return FlacStreamInfo(sampleRate, channels, bitDepth).takeIf { sampleRate > 0 }
    }

    /**
     * Whether the current item is a file on this device.
     *
     * Everything the app queues now is one, so this is mostly a guard for items
     * another controller handed the session: those carry their URI only in
     * [MediaItem.localConfiguration], and a few surfaces still send a path in
     * the metadata. Anything else has no local audio behind it.
     */
    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val extras = mediaItem?.mediaMetadata?.extras
        val localUri = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUri.isNullOrBlank() && (localUri.startsWith("content://") || localUri.startsWith("file://"))) {
            return true
        }
        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        return !localPath.isNullOrBlank()
    }

    private fun resolveLocalUri(mediaId: String, mediaItem: MediaItem?): Uri? {
        val directUriStr = when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> mediaId
            mediaId.startsWith("/") -> "file://$mediaId"
            else -> null
        }
        if (directUriStr != null) return Uri.parse(directUriStr)

        val localConfigUri = mediaItem?.localConfiguration?.uri
        if (localConfigUri != null && (localConfigUri.scheme == "file" || localConfigUri.scheme == "content")) {
            return localConfigUri
        }

        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return requestUri
        }

        val extras = mediaItem?.mediaMetadata?.extras
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUriStr.isNullOrBlank()) {
            return Uri.parse(localUriStr)
        }

        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return Uri.fromFile(java.io.File(localPath))
        }


        return null
    }

    private fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val kbps = runCatching {
                val retriever = MediaMetadataRetriever()
                var bps: Long? = null
                var durMs: Long? = null
                try {
                    retriever.setDataSource(this@PlaybackService, uri)
                    bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }

                if (bps != null && bps > 0) {
                    ((bps + 500L) / 1000L).toInt()
                } else if (durMs != null && durMs > 0) {
                    val sizeBytes = when (uri.scheme) {
                        "file" -> uri.path?.let { java.io.File(it).length() }
                        "content" -> contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        else -> null
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        ((sizeBytes * 8000L) / durMs / 1000L).toInt().takeIf { it > 0 }
                    } else null
                } else null
            }.getOrNull()

            if (kbps != null && kbps > 0) {
                localBitrateCache[mediaId] = kbps
                withContext(Dispatchers.Main) {
                    if (player?.currentMediaItem?.mediaId == mediaId) {
                        publishNerdStats()
                    }
                }
            }
        }
    }

    /**
     * Publishes what the decoder is really being fed, for "stats for nerds"
     * and for the quality badge above it.
     *
     * Everything the badge is decided on is measured off the stream in hand —
     * see [Measured] and [NerdStats.Snapshot.isLossless]. Bitrate is the
     * awkward one: a lossless stream gets the rate its samples decode to, which
     * is a product of three figures already measured and is the number a
     * listener can compare between tracks. A lossy one gets the container's,
     * when the container states it, and otherwise the metadata reader's figure
     * for the file — see [resolveLocalBitrate]. Anything still unknown is left
     * null for the UI to omit — better a shorter line than a made-up number.
     */
    private fun publishNerdStats() {
        val player = player ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        val measured = format?.measure()
        // DSD reaches the renderer already decimated to PCM (DsdExtractor), so
        // its sample mime is `audio/raw`; the extractor leaves `dsd64` and the
        // like in `codecs`, and that is the name worth showing — with the DSD
        // stream's own bitrate and rate (2.8224 MHz for DSD64) rather than the
        // 176.4 kHz PCM it became.
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val (bitrate, provenance) = when {
            format?.averageBitrate != null && format.averageBitrate > 0 ->
                Pair((format.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            format?.bitrate != null && format.bitrate > 0 ->
                Pair((format.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            mediaId != null && localBitrateCache.containsKey(mediaId) ->
                Pair(localBitrateCache[mediaId], TelemetryProvenance.AUTHORITATIVE)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.current.value = NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
        )
    }

    /** Serialize only the bounded window needed for a future cold-start resume. */
    private fun saveQueueSnapshot(player: ExoPlayer) {
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clear()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        persistedQueueStart = range.first
        LastPlayed.saveQueue(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
        )
        savePlaybackState(player)
    }


    /** Persist index and position without touching or serializing queue contents. */
    private fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        LastPlayed.savePlaybackState(
            index = player.currentMediaItemIndex - persistedQueueStart,
            positionMs = player.currentPosition,
        )
    }

    /** Restore the bounded queue without preparing it yet. */
    private fun restoreLastQueue(player: ExoPlayer): Boolean {
        val last = LastPlayed.load() ?: return false
        persistedQueueStart = 0
        player.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        return true
    }

    /**
     * PCM sample depth the renderer settled on, in bits.
     *
     * This is the figure that decides whether a hi-res file is being played as
     * one. A 24-bit FLAC whose renderer reports 16-bit PCM has been truncated
     * somewhere between the decoder and the sink, and no other number on the
     * stats line would show it — the sample rate and the codec both survive
     * that unharmed.
     *
     * `ENCODING_INVALID` and `NO_VALUE` mean the renderer hasn't said, which is
     * common for pass-through and for formats decoded straight to float, and
     * is reported as unknown rather than as a failure.
     */
    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /**
     * Tell the home-screen widgets what is playing.
     *
     * Kept out of [saveQueue] even though every caller does both: that one also
     * runs from the per-second sampler in [reportProgress], and pushing a bitmap
     * to the launcher once a second would be a lot of work to redraw the same
     * picture.
     *
     * [playing] overrides what the player reports, for the one caller that knows
     * better than it does — teardown, where the player is still nominally set to
     * play right up to the moment it is released.
     */
    private fun publishWidgetState(playing: Boolean? = null) {
        val exoPlayer = player ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val liked = LocalPlaylistStore.isFavorite(song)
        MediaWidgetSnapshot.save(
            this,
            MediaWidgetSnapshot(
                mediaId = song.videoId,
                title = song.title,
                artist = song.artist,
                artworkUrl = song.thumbnailUrl,
                // playWhenReady, not isPlaying — see MediaWidgetSnapshot.isPlaying.
                isPlaying = playing ?: exoPlayer.playWhenReady,
                hasPrevious = exoPlayer.hasPreviousMediaItem(),
                hasNext = exoPlayer.hasNextMediaItem(),
                isLiked = liked,
                shuffleEnabled = QueueShuffle.enabled.value,
            ),
        )
        MediaWidget.refresh(this)
    }


    /**
     * Samples the playhead while audio is coming out, feeding the device's own
     * listening record and keeping the saved position current. ExoPlayer is
     * confined to this thread, so the sampler has to live here.
     */
    private fun reportProgress() {
        scope.launch {
            while (isActive) {
                // Re-read every tick rather than captured once: the session
                // moves between two players, and a sampler pinned to the one
                // that happened to be first would go on reporting a player that
                // has been silent since the last crossfade.
                val player = this@PlaybackService.player
                if (player != null && player.isPlaying) {
                    lastPositionSeconds = player.currentPosition / 1000
                    // The device's own listening record — see [ListeningRecorder],
                    // which counts wall-clock time between ticks rather than
                    // reading the position. This loop is the only place in the app
                    // that ticks exactly while audio is coming out, which is what
                    // makes it the right place to count from.
                    player.currentMediaItem?.toSong()?.let {
                        ListeningRecorder.onSample(it, player.duration)
                    }
                    // Only two primitive preference values. Queue JSON is
                    // written from onTimelineChanged, never from this loop.
                    savePlaybackState(player)
                    // The renderer can settle on its format a moment after the
                    // track change, which no callback of ours follows up on.
                    publishNerdStats()
                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    /**
     * Pause when the sleep timer runs out.
     *
     * `collectLatest` is what makes re-setting the timer work: the pending wait
     * is cancelled and restarted on the new deadline instead of both firing.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player?.pause()
                SleepTimer.cancel()
            }
        }
    }

    /**
     * Buffers as far ahead as a whole track rather than a rolling window.
     *
     * Media3's audio default stops loading at 13 buffer segments — around 830kB
     * — and everything past that is read only as playback consumes it. A seek
     * past the buffered part is then the one that has to wait on the disk, and
     * the back buffer below keeps a short rewind from doing the same.
     *
     * The byte ceiling is what governs; the duration is set past any song so
     * that it never becomes the binding constraint.
     *
     * Two further departures from the defaults, both about how long the
     * listener waits for sound:
     *
     *  - **Back buffer.** Media3 keeps nothing behind the playhead, so a seek
     *    *backwards* drops the buffer and reloads, while a seek forwards lands
     *    in samples already held. Half a minute of history closes that gap for
     *    the seek people actually make — nudging back a few seconds to catch a
     *    lyric — and it is deliberately no longer than that. The byte ceiling
     *    above counts *everything* the player holds, history included, so a
     *    back buffer wide enough to keep a whole track would spend the entire
     *    read-ahead budget on audio already heard.
     *  - **Thresholds to (re)start playback.** The defaults — 2.5s of audio
     *    before starting, 5s before resuming after a rebuffer — are sized for
     *    bytes that might arrive slowly. Here they are already on disk, so those
     *    seconds are spent waiting on a buffer that fills instantly and are
     *    simply dead air after a seek. Resuming is given more room than
     *    starting: a stall means the disk is genuinely struggling, and coming
     *    back with a second of audio in hand only buys the next stall.
     */
    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            /* maxBufferMs = */ FAR_BUFFER_MS,
            /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(/* backBufferDurationMs = */ BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()

    /**
     * Renderers whose audio sink only skips silence worth skipping.
     *
     * Media3's stock threshold is 100ms, which eats the breaths, rests and
     * pre-chorus beats *inside* a song — the setting reads as "make the music
     * sound rushed" rather than "trim dead air". A second-long floor leaves
     * musical pauses alone and still collapses the run-in and run-out of a
     * track. Everything else about the chain stays default, so
     * `skipSilenceEnabled` keeps driving it as before.
     */
    private fun silenceSkippingRenderers(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        transition: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
    ) = object : DefaultRenderersFactory(this) {
        init {
            // Do not force PCM_FLOAT onto an OEM speaker mixer merely because
            // the preference asks for it. The selected route must advertise
            // the format; otherwise Media3 uses its stable PCM16 path.
            //
            // Which way this cuts is worth stating, because it reads backwards:
            // float is what *preserves* a hi-res source rather than what
            // embellishes it, since it is the only AudioTrack encoding Media3
            // will open that carries a 24-bit sample intact.
            setEnableAudioFloatOutput(configuredFloatOutput)
            // c2.sec.flac.decoder produces one extra 232.2ms timestamp
            // advance per decoded buffer when Media3 requests float PCM.
            // Decode FLAC through the platform software codec on affected Samsung devices.
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val selector = if (mimeType == MimeTypes.AUDIO_FLAC) {
                    MediaCodecSelector.PREFER_SOFTWARE
                } else {
                    MediaCodecSelector.DEFAULT
                }
                val candidates = selector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                if (mimeType == MimeTypes.AUDIO_FLAC) {
                    candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                } else {
                    candidates
                }
            }
        }

        // FFmpeg after the MediaCodec renderer, so it only gets a track no
        // platform decoder takes: ALAC everywhere, and AC-4 and Dolby Digital
        // (Plus, Atmos) on phones without Dolby's own decoder. Atmos then plays
        // as its 5.1 bed, folded down to stereo — the object layer needs
        // Dolby's decoder, which is the only thing that can read it.
        // Same sink as the MediaCodec renderer, so the DSP chain and the
        // precision path see no difference between the two.
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out,
            )
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val defaultSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // Speed handled by the AudioTrack itself on a float-capable
                // route, rather than by Sonic. `DefaultAudioSink.configure`
                // appends `audioProcessorChain.getAudioProcessors()` — which is
                // where Sonic lives — only on its 16-bit branch; the float
                // branch gets the format converter and nothing else. With the
                // stock flag, changing speed on a float route therefore left
                // the audio playing at 1x while `getMediaDuration` went on
                // scaling, so the position clock drifted away from the sound.
                // AudioTrack's own playback params work on either branch.
                //
                // Silence skipping is in that same dropped chain and has no
                // such escape hatch; the equaliser screen says so, and the
                // equaliser itself is unaffected because it runs upstream in
                // [PrecisionAudioSink], not here.
                .setEnableAudioTrackPlaybackParams(
                    enableAudioTrackPlaybackParams || enableFloatOutput,
                )
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray<AudioProcessor>(),
                        SilenceSkippingAudioProcessor(
                            MIN_SILENCE_US,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                        ),
                        SonicAudioProcessor(),
                    ),
                )
                .build()

            // Transition filtering last of the three: widening is a
            // property of the track, and a bass swap that ran before it
            // would have its own low end fed back in by the crossfeed —
            // or, once the equaliser is in the chain, by whatever the
            // listener's low band was set to. The equaliser sits between
            // them for the same reason: it belongs to the listener and
            // the whole session, while the transition filter belongs to
            // one handoff and has to have the last word on it.
            val dspChain = DspChain(spatial, equalizer, transition, loudness)
            return PrecisionAudioSink(
                delegate = defaultSink,
                dspChain = dspChain,
                enableFloatOutput = enableFloatOutput,
                preferredOutputEncodingProvider = { format -> resolvePreferredOutputEncoding(format) },
                // Which of the two sinks the listener can hear, told by the
                // role its transition filter currently holds — [activeFilter]
                // and [spareFilter] trade places at every handoff. Only the
                // audible one writes telemetry, so the idle player's reset
                // cannot publish "not exact" over the playing track's verdict.
                isAudible = { transition === activeFilter },
            )
        }
    }

    /**
     * Push current settings onto a player. Called for both: whichever one is
     * idle right now is the one the next transition will start a song on, so it
     * cannot be left on stale settings.
     */
    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        // Restore persisted shuffle and repeat states on startup. Assign both
        // values: Android can destroy and recreate the service while retaining
        // this process, so QueueShuffle's process-wide object may still hold
        // the previous service instance's value. Only assigning `true` made a
        // stale enabled state consume the first tap meant to enable Shuffle.
        QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
        player.repeatMode = AppSettings.repeatMode.value
    }

    /**
     * What the pipeline last reported, for when the live [Format] carries no
     * usable rate or channel count of its own. Guessing 48 kHz here is what made
     * a 176.4 kHz stream negotiate itself down, so the real last-known value is
     * worth reaching for before giving up.
     */
    private fun lastReportedSampleRateHz(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.sampleRateHz?.takeIf { it > 0 }
            ?: status.actualSampleRateHz?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun lastReportedChannelCount(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.channels?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.channelCount?.takeIf { it > 0 }
    }

    /**
     * Applies the user's USB-DAC preference through the public Android routing
     * API. This does not bypass the system USB driver: it intentionally lets
     * Android negotiate only formats the connected DAC actually advertises.
     */
    private fun applyOutputRoute() {
        val manager = audioManager ?: return
        val usb = preferredUsbDevice()
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: usb.takeIf { AppSettings.preferUsbDac.value }
        eachPlayer { it.setPreferredAudioDevice(preferred) }

        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val directUsbProbe = if (routeKind == AudioRouting.Kind.USB) UsbDirectManager.probe(this) else null

        val format = currentAudioInputFormat
        val measured by lazy(LazyThreadSafetyMode.NONE) { format?.measure() }
        val sampleRate = format?.sampleRate?.takeIf { it > 0 }
            ?: measured?.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
        val channels = format?.channelCount?.takeIf { it > 0 }
            ?: measured?.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val directSupport = if (sampleRate != null) {
            DirectAudioProbe.probeDirectSupport(
                audioManager = manager,
                sampleRateHz = sampleRate,
                channelCount = channels,
                activeDevice = activeDevice,
            )
        } else {
            DirectAudioProbe.DirectSupport.NONE
        }
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        AudioOutputStatus.publish(
            manager = manager,
            requestedPcmMode = AppSettings.outputPcmMode.value,
            preferred = activeDevice,
            floatEnabled = shouldEnableFloatOutput(),
            routeKind = routeKind,
            directUsbProbe = directUsbProbe,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            systemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        if (format != null) {
            resolvePreferredOutputEncoding(format)
        }
    }

    private fun resolvePreferredOutputEncoding(format: Format): PcmEncoding? {
        val manager = audioManager ?: return null
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        if (routeKind == AudioRouting.Kind.PHONE) {
            return PcmEncoding.PCM_16BIT
        }

        val inputFormat = currentAudioInputFormat ?: format
        val measuredFormat = format.measure()
        val measuredInput by lazy(LazyThreadSafetyMode.NONE) {
            if (inputFormat === format) measuredFormat else inputFormat.measure()
        }

        // No rate from the stream and none ever reported means nothing is known
        // yet: negotiating against a guessed rate would publish that guess as
        // fact. Leave the encoding to the sink's own fallback instead.
        val sampleRate = format.sampleRate.takeIf { it > 0 }
            ?: inputFormat.sampleRate.takeIf { it > 0 }
            ?: measuredFormat.sampleRateHz?.takeIf { it > 0 }
            ?: measuredInput.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
            ?: return null

        val channels = format.channelCount.takeIf { it > 0 }
            ?: inputFormat.channelCount.takeIf { it > 0 }
            ?: measuredFormat.channels?.takeIf { it > 0 }
            ?: measuredInput.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val bitDepth = measuredFormat.bitDepth
            ?: measuredInput.bitDepth
            ?: NerdStats.current.value?.bitDepth ?: when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_FLOAT -> 32
            else -> when (format.pcmEncoding) {
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }
        }

        val sourceEncoding = if (inputFormat.sampleMimeType != null && inputFormat.sampleMimeType != "audio/raw") {
            inputFormat.sampleMimeType!!
        } else {
            format.sampleMimeType ?: "audio/raw"
        }

        val source = SourceDescriptor(
            encoding = sourceEncoding,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitDepth = bitDepth,
        )

        val directSupport = DirectAudioProbe.probeDirectSupport(
            audioManager = manager,
            sampleRateHz = sampleRate,
            channelCount = channels,
            activeDevice = activeDevice,
        )
        val directUsbProbe = if (routeKind == AudioRouting.Kind.USB) UsbDirectManager.probe(this) else null
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        val advertisedEncodings = activeDevice?.encodings?.toList() ?: emptyList()
        val advertisedSampleRates = activeDevice?.sampleRates?.toList() ?: emptyList()

        val negotiation = OutputNegotiator.negotiate(
            source = source,
            decoderName = AudioOutputStatus.current.value.decoderName,
            sampleRateHz = sampleRate,
            channelCount = channels,
            routeKind = routeKind,
            deviceName = activeDevice?.productName?.toString()?.ifBlank { null } ?: "System default",
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            requestedMode = AppSettings.outputPcmMode.value,
            directUsbProbe = directUsbProbe,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            delegateSupportsFloat = shouldEnableFloatOutput(),
            // Never, on any route. `DefaultAudioSink.configure` inserts either
            // `ToFloatPcmAudioProcessor` or `ToInt16PcmAudioProcessor` for every
            // linear-PCM input encoding, so there is no packed-24-bit AudioTrack
            // to be had through it however loudly the device advertises one.
            // Claiming otherwise here selected a 24-bit path that Media3 then
            // silently re-converted, costing an extra quantization and leaving
            // this readout naming a bit depth that was never written.
            delegateSupportsPcm24 = false,
            knownSystemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        AudioOutputStatus.publishNegotiation(negotiation)

        return negotiation.output.encoding
    }

    private fun requestOutputReconfiguration() {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            // Replacing two renderers during a blend would cut one half of it.
            // Wait for the short transition to settle, then swap the engine.
            while (crossfade?.isTransitioning() == true) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput) {
                applyOutputRoute()
            } else {
                rebuildPlayersForOutput(requestedFloat)
            }
        }
    }

    /**
     * Rebuilds Media3's immutable AudioSink configuration without restarting
     * the app or service. Queue, item, position and play state are transferred
     * to the replacement player; the old AudioTrack is released only after the
     * MediaSession points at the new one.
     */
    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player ?: return
        val oldSpare = spare ?: return
        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val shuffleMode = oldActive.shuffleModeEnabled

        oldActive.playWhenReady = false
        crossfade?.release()
        oldActive.removeListener(playbackListener)
        oldActive.removeAnalyticsListener(formatListener)

        configuredFloatOutput = enableFloat
        activeFilter = transitionFilterA
        spareFilter = transitionFilterB
        val newActive = buildPlayer(
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val newSpare = buildPlayer(
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = newActive
        spare = newSpare
        newSpare.audioSessionId = newActive.audioSessionId
        AppSettings.audioSessionId.value = newActive.audioSessionId
        applySettings(newActive)
        applySettings(newSpare)
        newActive.repeatMode = repeatMode
        newActive.shuffleModeEnabled = shuffleMode
        applyEqualizer()
        applySpatialAudioEnabled()
        if (items.isNotEmpty()) {
            newActive.setMediaItems(items, index.coerceIn(items.indices), position)
            // By hand, because the listener is attached below this: the
            // transition `setMediaItems` fires lands before anything is
            // listening, so the ordinary setup path in [onTrackBecameCurrent]
            // never runs for the track the rebuild resumes on.
            setupLoudness(items.getOrNull(index.coerceIn(items.indices))?.mediaId)
        }
        newActive.addListener(playbackListener)
        newActive.addAnalyticsListener(formatListener)

        val newCrossfade = createCrossfadeController()
        crossfade = newCrossfade
        newCrossfade.start()
        mediaSession?.player = newSessionPlayer(newActive, newCrossfade)
        applyOutputRoute()
        if (items.isNotEmpty()) newActive.prepare()
        newActive.playWhenReady = playWhenReady

        oldActive.release()
        oldSpare.release()
        TrackLog.i(
            "AUDIO_OUT",
            "live output switch: ${if (enableFloat) "PCM_FLOAT" else "PCM_16BIT"}",
            about = newActive.currentMediaItem?.mediaId,
        )
    }

    private fun preferredUsbDevice(): AudioDeviceInfo? = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    private fun resolveActiveOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val infos = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: preferredUsbDevice().takeIf { AppSettings.preferUsbDac.value }
        return preferred ?: AudioRouting.activeOf(infos, null)
    }

    private fun resolveActiveRouteKind(device: AudioDeviceInfo?): AudioRouting.Kind {
        if (device == null) return AudioRouting.Kind.PHONE
        return AudioRouting.kindOf(device.type) ?: AudioRouting.Kind.PHONE
    }

    private fun shouldEnableFloatOutput(): Boolean {
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val advertisesFloat = activeDevice?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true
        val directFloatSupported = audioManager?.let { mgr ->
            DirectAudioProbe.probeDirectSupport(mgr, 48000, 2, activeDevice).supportsFloat
        } ?: false
        // Worth knowing which way this cuts for a hi-res stream: with float
        // off, Media3 downconverts *every* input encoding to 16-bit, because
        // float and 16-bit are the only two linear-PCM tracks its sink can
        // open. Float is what carries a 24-bit sample to the hardware intact,
        // so "32-bit float" in Output precision is the setting that preserves
        // a hi-res source, not the one that embellishes it.
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = AppSettings.outputPcmMode.value,
            routeKind = routeKind,
            advertisesPcmFloat = advertisesFloat || directFloatSupported,
        )
    }

    /** Runs [body] against both players, in whichever roles they currently hold. */
    private inline fun eachPlayer(body: (ExoPlayer) -> Unit) {
        player?.let(body)
        spare?.let(body)
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.skipSilence.collect { on -> eachPlayer { it.skipSilenceEnabled = on } }
        }
        scope.launch {
            AppSettings.preferUsbDac.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AudioRouting.selectedId.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.outputPcmMode.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            // Not applied to a player mid-transition: [CrossfadeController]
            // stacks a beatmatch stretch on top of this setting, and writing the
            // raw value over it would drop the incoming track back to its own
            // tempo halfway through a blend. The controller re-reads the setting
            // when it restores the rate, so the change still lands.
            AppSettings.playbackSpeed.collect { speed ->
                if (crossfade?.isTransitioning() == true) return@collect
                crossfade?.applyPlaybackSpeed(speed) ?: eachPlayer { it.setPlaybackSpeed(speed) }
            }
        }
        scope.launch {
            combine(
                AppSettings.syncedLyrics,
                AppSettings.lyricsSources,
                AppSettings.lyricsSourceOrder,
                AppSettings.prioritizeSyllableSync,
            ) { synced, sources, order, prio ->
                synced to sources
            }.distinctUntilChanged().collect {
                loadLyricsForCurrentTrack()
            }
        }
        scope.launch {
            AppSettings.spatialAudio.collect { applySpatialAudioEnabled() }
        }
        scope.launch {
            AppSettings.loudnessNormalization.collect {
                setupLoudness(player?.currentMediaItem?.mediaId)
            }
        }
        scope.launch {
            // Same route the player's output caption reads, so "on speaker"
            // means the same thing here as there.
            combine(
                AppSettings.loudnessOffOnSpeaker,
                AudioOutputStatus.current.map { it.routeKind == AudioRouting.Kind.PHONE }.distinctUntilChanged(),
            ) { off, onSpeaker -> off && onSpeaker }
                .distinctUntilChanged()
                .drop(1)
                .collect { setupLoudness(player?.currentMediaItem?.mediaId) }
        }
        scope.launch {
            // Explicit <Any, _>: these flows have mixed element types, and
            // letting the reified vararg combine() infer T lands on an
            // intersection type. Nothing is read out of the array — the
            // equaliser reads the settings it wants directly — because seven
            // sources of one curve is seven chances to destructure them in the
            // wrong order.
            combine<Any, Unit>(
                AppSettings.equalizerEnabled,
                AppSettings.equalizerMode,
                AppSettings.equalizerToneX,
                AppSettings.equalizerToneY,
                AppSettings.equalizerFocused,
                AppSettings.equalizerBalance,
                AppSettings.equalizerBands,
            ) { }.collect { applyEqualizer() }
        }
    }

    /**
     * Renders the equaliser settings into a curve and hands it to both
     * processors.
     *
     * Both, and not just the audible one, for the reason [applySettings] gives:
     * the idle player is the one the next transition starts a song on, and a
     * blend whose two halves were equalised differently would sweep the curve in
     * over the crossfade.
     *
     * The curve is built here rather than in the processor because working out
     * the make-up attenuation walks the whole response ([EqCurve]), and the
     * audio thread is the one place that must not do that.
     */
    private fun applyEqualizer() {
        val enabled = AppSettings.equalizerEnabled.value
        val curve = when (AppSettings.equalizerMode.value) {
            EqualizerMode.DYNAMIC -> toneCurve(
                x = AppSettings.equalizerToneX.value,
                y = AppSettings.equalizerToneY.value,
                focused = AppSettings.equalizerFocused.value,
            )
            EqualizerMode.MANUAL -> manualCurve(AppSettings.equalizerBands.value)
        }
        val balance = AppSettings.equalizerBalance.value
        equalizerProcessorA.setTuning(enabled, curve, balance)
        equalizerProcessorB.setTuning(enabled, curve, balance)
    }

    /**
     * The effective spatial-audio state: the user's toggle, unless the
     * active player's decoder is currently on a Dolby Atmos (E-AC-3 JOC)
     * stream, in which case it's forced off. [activeTrackIsDolbyAtmos] only
     * ever describes the active player — see [formatListener] — so both
     * processors are kept in lockstep rather than tracking a role swap.
     */
    private fun applySpatialAudioEnabled() {
        val enabled = AppSettings.spatialAudio.value && !activeTrackIsDolbyAtmos
        spatialAudioProcessorA.enabled = enabled
        spatialAudioProcessorB.enabled = enabled
    }

    /**
     * The linear gain [LoudnessProcessor] applies to [mediaId].
     *
     * A local file carries no normalization figure the player can read, so this
     * is unity: the loudness stage stays in the chain — the crossfade still
     * drives its blend trim, and its limiter still protects the sum of two
     * tracks — but it does not re-level a track on its own.
     *
     * Called on the audio thread, so it reads nothing that can block.
     */
    private fun loudnessGainFor(mediaId: String): Float = 1f

    /**
     * Points the session player's loudness stage at [mediaId] and the track
     * queued after it.
     *
     * Only the session player's: the spare is pointed at its own track when a
     * transition arms it — see [CrossfadeController]'s `onArmIncoming` — which is
     * the whole point of the stage being per player.
     */
    private fun setupLoudness(mediaId: String?) {
        val exoPlayer = player ?: return
        activeLoudness().track(mediaId, nextMediaIdOf(exoPlayer))
    }

    /** The track [exoPlayer] will move onto gaplessly, if any. */
    private fun nextMediaIdOf(exoPlayer: ExoPlayer): String? {
        val next = exoPlayer.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next !in 0 until exoPlayer.mediaItemCount) return null
        return exoPlayer.getMediaItemAt(next).mediaId
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Called by Android when the user swipes this app's task away from the
     * recent apps screen.
     *
     * When [AppSettings.stopOnTaskRemoved] is on we stop the player and let the
     * service die naturally; otherwise we leave it running in the background so
     * music continues past the swipe, which is the default Android behaviour for
     * a foreground-service-backed media session.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppSettings.stopOnTaskRemoved.value) {
            // A receiver left playing with nothing on the phone to drive it
            // would run out its few queued tracks and stop on its own anyway;
            // better to say so now than leave the TV on a casting screen.
            if (castPlayback.active) CastController.disconnect(resumeHere = false)
            // Both, or a swipe-away mid-crossfade leaves the outgoing track
            // playing on its own out of a service that is on its way out.
            eachPlayer { it.stop() }
            stopSelf()
        }
    }


    override fun onDestroy() {
        // Let go of the receiver's player but leave its session alone: a
        // service that idles out while casting is not the listener choosing to
        // stop, and the next one picks the session up where it was.
        CastController.detach(castPlayback)
        castPlayback.release()
        closeAudioEffectSession()
        bluetoothTracker.stop()
        audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
        player?.let(::savePlaybackState)
        // And to leave the widgets showing a play button. Nothing else reports a
        // swipe-away, so a widget left on the home screen would sit there with a
        // pause glyph on a service that no longer exists.
        publishWidgetState(playing = false)
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        serviceLyricsJob?.cancel()
        serviceLyricsJob = null
        serviceLyrics = null
        trackAnalyzer.release()
        // Last chance to get the current track's minutes onto disk: the scope is
        // cancelled a few lines down and the sampler goes with it.
        ListeningRecorder.onStopped()
        scope.cancel()
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playbackListener)
        player?.removeAnalyticsListener(formatListener)
        player?.release()
        player = null
        // Released too, and not conditionally: mid-crossfade it is holding a
        // decoder and an open audio track of its own, and the service going away
        // is not a reason to leave either behind.
        spare?.release()
        spare = null
        AudioOutputStatus.reset()
        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        super.onDestroy()
    }

    private fun loadLyricsForCurrentTrack() {
        val currentSong = player?.currentMediaItem?.toSong() ?: run {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        if (!AppSettings.syncedLyrics.value) {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        val trackDurationMs = (player?.duration ?: 0L).takeIf { it > 0 } ?: 0L

        serviceLyricsJob?.cancel()
        serviceLyricsJob = scope.launch(Dispatchers.IO) {
            val localUri = currentSong.localUri
            var lines: List<LyricLine>? = null
            if (localUri != null) {
                lines = EmbeddedLyrics.forUri(this@PlaybackService, localUri)
            }
            if (lines == null) {
                val found = LyricsRepository.lyrics(
                    videoId = currentSong.videoId,
                    title = currentSong.title,
                    artist = currentSong.artist,
                    durationMs = trackDurationMs,
                    album = currentSong.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
                lines = found?.lines
            }
            withContext(Dispatchers.Main) {
                serviceLyrics = lines
                if (player?.isPlaying == true) {
                    updateLyricSubtitle()
                }
            }
        }
    }

    private fun startLyricsTicker() {
        if (lyricsTickerJob?.isActive == true) return
        lyricsTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateLyricSubtitle()
                delay(500L)
            }
        }
    }

    private fun stopLyricsTicker() {
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        updateLyricSubtitle()
    }

    private fun updateLyricSubtitle() {
        val exoPlayer = player ?: return
        val currentSong = exoPlayer.currentMediaItem?.toSong() ?: return
        val lines = serviceLyrics
        val pos = exoPlayer.currentPosition
        val subtitleText = if (lines != null && lines.isNotEmpty() && AppSettings.syncedLyrics.value) {
            val idx = lines.indexOfLast { it.timeMs <= pos }
            val currentLine = lines.getOrNull(idx)
            if (currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank()) {
                "♪ ${currentLine.text}"
            } else {
                currentSong.artist
            }
        } else {
            currentSong.artist
        }

        if (subtitleText != lastPublishedSubtitle) {
            lastPublishedSubtitle = subtitleText
            // buildUpon() off the metadata that's already playing, not a fresh
            // Builder() — a synced lyric line can advance every second or two,
            // and re-stating setArtworkUri() on every tick made media3 treat
            // the cover as newly changed that often, racing its own artwork
            // decode/cache against the legacy MediaSession broadcast and
            // crashing with "cannot use a recycled source in createBitmap".
            // Carrying the existing artwork field forward keeps that field
            // untouched while still firing onMediaMetadataChanged for the
            // subtitle itself.
            exoPlayer.playlistMetadata = exoPlayer.mediaMetadata
                .buildUpon()
                .setSubtitle(subtitleText)
                .build()
        }
    }

    /**
     * What the MediaSession, and so every control surface, actually talks to.
     *
     * Two behaviours are grafted onto the player here rather than left to
     * ExoPlayer's defaults:
     *
     * **Back restarts the track.** ExoPlayer already implements
     * restart-then-skip in [Player.seekToPrevious], gated on
     * `maxSeekToPreviousPosition`. External surfaces don't use it:
     * [DefaultMediaNotificationProvider] binds its previous button to
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, which skips unconditionally. So
     * that command is redirected rather than left to behave differently
     * depending on which back button was pressed.
     *
     * **A skip cancels the crossfade.** Blending is for a track running out,
     * not for one being changed: told to move on, the listener wants the song
     * they were on to stop, not to keep playing over the one they asked for.
     * So every skip tells [CrossfadeController] to drop whatever is in flight
     * and then moves the queue plainly.
     *
     * Command availability is deliberately untouched — mutating it through a
     * [ForwardingPlayer] means intercepting listener callbacks too. The one
     * consequence is the first track of a queue, where ExoPlayer withholds
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` for want of a previous item: back
     * stays inert on those surfaces, exactly as it already was. In the app it
     * restarts, since that path asks for `COMMAND_SEEK_TO_PREVIOUS`.
     */
    private class SessionPlayer(
        player: Player,
        private val crossfade: CrossfadeController,
        /** What plays on a Cast receiver, whose clock this reports while it is the speaker. */
        private val cast: CastPlayback,
        private val getSubtitle: () -> String?,
    ) : ForwardingPlayer(player), CastSink {

        // The listeners registered through this wrapper, kept so the receiver's
        // changes can be announced to them. The phone's own player never
        // announces them — it is paused, and as far as it knows nothing is
        // happening.
        private val listeners = CopyOnWriteArraySet<Player.Listener>()
        private var reportedPlayWhenReady = false
        private var reportedPlaybackState = Player.STATE_IDLE
        private var reportedIsPlaying = false

        init {
            cast.sink = this
        }

        private companion object {
            val CAST_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(100)
                .build()

            /** A hardware key press, as a share of the receiver's range. */
            const val VOLUME_STEP = 0.05f
        }

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }

        // While a receiver is the speaker the phone's player is paused and its
        // clock is stale, so everything that describes playback is the
        // receiver's. The queue, the metadata and the commands stay the
        // phone's.
        override fun getPlayWhenReady() = if (cast.active) cast.playWhenReady else super.getPlayWhenReady()

        override fun isPlaying() = if (cast.active) cast.isPlaying else super.isPlaying()

        override fun getPlaybackState() = if (cast.active) cast.playbackState else super.getPlaybackState()

        override fun getCurrentPosition() = if (cast.active) cast.positionMs else super.getCurrentPosition()

        override fun getContentPosition() = if (cast.active) cast.positionMs else super.getContentPosition()

        override fun getBufferedPosition() = if (cast.active) cast.positionMs else super.getBufferedPosition()

        override fun getContentBufferedPosition() =
            if (cast.active) cast.positionMs else super.getContentBufferedPosition()

        override fun getDuration(): Long =
            if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getDuration()

        override fun getContentDuration(): Long =
            if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getContentDuration()

        // The receiver is a remote output with a volume of its own, which is
        // what makes the hardware keys move it instead of the phone's.
        override fun getDeviceInfo(): DeviceInfo = if (cast.active) CAST_DEVICE else super.getDeviceInfo()

        override fun getDeviceVolume(): Int = if (cast.active) cast.deviceVolume else super.getDeviceVolume()

        override fun isDeviceMuted(): Boolean = if (cast.active) false else super.isDeviceMuted()

        override fun setDeviceVolume(volume: Int, flags: Int) {
            if (cast.active) CastController.setVolume(volume / 100f) else super.setDeviceVolume(volume, flags)
        }

        @Suppress("DEPRECATION")
        override fun setDeviceVolume(volume: Int) {
            if (cast.active) CastController.setVolume(volume / 100f) else super.setDeviceVolume(volume)
        }

        override fun increaseDeviceVolume(flags: Int) {
            if (cast.active) nudgeReceiverVolume(+VOLUME_STEP) else super.increaseDeviceVolume(flags)
        }

        @Suppress("DEPRECATION")
        override fun increaseDeviceVolume() {
            if (cast.active) nudgeReceiverVolume(+VOLUME_STEP) else super.increaseDeviceVolume()
        }

        override fun decreaseDeviceVolume(flags: Int) {
            if (cast.active) nudgeReceiverVolume(-VOLUME_STEP) else super.decreaseDeviceVolume(flags)
        }

        @Suppress("DEPRECATION")
        override fun decreaseDeviceVolume() {
            if (cast.active) nudgeReceiverVolume(-VOLUME_STEP) else super.decreaseDeviceVolume()
        }

        override fun setDeviceMuted(muted: Boolean, flags: Int) {
            if (!cast.active) super.setDeviceMuted(muted, flags)
        }

        @Suppress("DEPRECATION")
        override fun setDeviceMuted(muted: Boolean) {
            if (!cast.active) super.setDeviceMuted(muted)
        }

        private fun nudgeReceiverVolume(delta: Float) =
            CastController.setVolume(CastController.volume.value + delta)

        override fun onRemotePlaybackChanged() {
            val flags = FlagSet.Builder()
            val playWhenReady = getPlayWhenReady()
            val state = getPlaybackState()
            val playing = isPlaying()
            if (playWhenReady != reportedPlayWhenReady) {
                reportedPlayWhenReady = playWhenReady
                flags.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                listeners.forEach {
                    it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                }
            }
            if (state != reportedPlaybackState) {
                reportedPlaybackState = state
                flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                listeners.forEach { it.onPlaybackStateChanged(state) }
            }
            if (playing != reportedIsPlaying) {
                reportedIsPlaying = playing
                flags.add(Player.EVENT_IS_PLAYING_CHANGED)
                listeners.forEach { it.onIsPlayingChanged(playing) }
            }
            // Always: a controller extrapolates the position from the last one
            // it was given, and has to be re-anchored whenever the receiver
            // has been seeked, stalled or resumed.
            flags.add(Player.EVENT_POSITION_DISCONTINUITY)
            val position = Player.PositionInfo(
                null,
                currentMediaItemIndex,
                currentMediaItem,
                null,
                currentPeriodIndex,
                currentPosition,
                contentPosition,
                C.INDEX_UNSET,
                C.INDEX_UNSET,
            )
            listeners.forEach {
                it.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_INTERNAL)
            }
            val events = Player.Events(flags.build())
            listeners.forEach { it.onEvents(this, events) }
        }

        override fun onRemoteRouteChanged() {
            val info = getDeviceInfo()
            listeners.forEach { it.onDeviceInfoChanged(info) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_INFO_CHANGED).build())
            listeners.forEach { it.onEvents(this, events) }
            onRemoteVolumeChanged()
        }

        override fun onRemoteVolumeChanged() {
            val volume = getDeviceVolume()
            val muted = isDeviceMuted()
            listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())
            listeners.forEach { it.onEvents(this, events) }
        }

        /** Back, on a receiver: restart past the first seconds, else the previous track. */
        private fun castPrevious() {
            if (cast.positionMs > wrappedPlayer.maxSeekToPreviousPosition || !wrappedPlayer.hasPreviousMediaItem()) {
                cast.seekTo(0L)
            } else {
                wrappedPlayer.seekToPreviousMediaItem()
            }
        }

        override fun play() {
            if (cast.active) {
                cast.play()
                return
            }
            super.play()
        }

        override fun pause() {
            if (cast.active) {
                cast.pause()
                return
            }
            super.pause()
        }

        override fun stop() {
            super.stop()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (cast.active) {
                if (playWhenReady) cast.play() else cast.pause()
                return
            }
            super.setPlayWhenReady(playWhenReady)
        }

        override fun seekTo(positionMs: Long) {
            if (cast.active) {
                cast.seekTo(positionMs)
                return
            }
            super.seekTo(positionMs)
        }

        override fun seekBack() {
            if (cast.active) {
                cast.seekTo(cast.positionMs - seekBackIncrement)
                return
            }
            super.seekBack()
        }

        override fun seekForward() {
            if (cast.active) {
                cast.seekTo(cast.positionMs + seekForwardIncrement)
                return
            }
            super.seekForward()
        }

        override fun seekToPrevious() {
            if (cast.active) {
                castPrevious()
                return
            }
            super.seekToPrevious()
        }

        override fun seekToDefaultPosition() {
            if (cast.active) {
                cast.seekTo(0L)
                return
            }
            super.seekToDefaultPosition()
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            super.seekToDefaultPosition(mediaItemIndex)
        }

        override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
            super.setMediaItems(mediaItems, resetPosition)
        }

        override fun setMediaItems(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) {
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
            super.addMediaItems(index, mediaItems)
        }

        override fun addMediaItems(mediaItems: List<MediaItem>) {
            super.addMediaItems(mediaItems)
        }

        override fun removeMediaItem(index: Int) {
            super.removeMediaItem(index)
        }

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            super.removeMediaItems(fromIndex, toIndex)
        }

        override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
            super.moveMediaItem(currentIndex, newIndex)
        }

        override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
            super.moveMediaItems(fromIndex, toIndex, newIndex)
        }

        override fun replaceMediaItems(
            fromIndex: Int,
            toIndex: Int,
            mediaItems: List<MediaItem>,
        ) {
            super.replaceMediaItems(fromIndex, toIndex, mediaItems)
        }

        override fun clearMediaItems() {
            super.clearMediaItems()
        }

        override fun getMediaMetadata(): MediaMetadata {
            val base = wrappedPlayer.mediaMetadata
            val subtitle = getSubtitle()
            return if (!subtitle.isNullOrBlank()) {
                base.buildUpon().setSubtitle(subtitle).build()
            } else {
                base
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            crossfade.onSkipRequested()
            if (mediaItemIndex !in 0 until wrappedPlayer.mediaItemCount) return
            // The track already on the receiver: a position in it is the
            // receiver's to seek, not the paused phone's.
            if (cast.active && mediaItemIndex == wrappedPlayer.currentMediaItemIndex) {
                cast.seekTo(positionMs)
                return
            }
            val skipped = skippedByQueueJump(currentMediaItemIndex, mediaItemIndex)
            if (skipped == null) {
                wrappedPlayer.seekTo(mediaItemIndex, positionMs)
                return
            }

            // A direct choice of a later queue row bypasses items between current and target.
            // Invariant: A queue jump may consume/delete USER_QUEUE items only when jumping within
            // USER_QUEUE. Jumps to any other tier (CONTEXT or AUTOPLAY) must NEVER delete USER_QUEUE items.
            val targetTier = wrappedPlayer.getMediaItemAt(mediaItemIndex).queueTier
            val removableIndices = mutableListOf<Int>()
            for (i in skipped) {
                val tier = wrappedPlayer.getMediaItemAt(i).queueTier
                val isRemovable = if (targetTier == QueueTier.USER_QUEUE) {
                    tier == QueueTier.USER_QUEUE
                } else {
                    tier != QueueTier.USER_QUEUE
                }
                if (isRemovable) {
                    removableIndices.add(i)
                }
            }

            // Remove in reverse order so preceding indices remain valid during removal
            for (i in removableIndices.asReversed()) {
                wrappedPlayer.removeMediaItem(i)
            }
            var newTargetIndex = mediaItemIndex - removableIndices.count { it < mediaItemIndex }
            // Whatever was kept still sits between here and the target. Seeking
            // past it would leave it behind the playhead, where
            // [QueueCoordinator.consumePlayedUserQueue] deletes it on the next
            // transition — so the target moves up to play next instead, with
            // the kept rows following it, the same order an in-app tap gives.
            val nextIndex = wrappedPlayer.currentMediaItemIndex + 1
            if (newTargetIndex > nextIndex) {
                wrappedPlayer.moveMediaItem(newTargetIndex, nextIndex)
                newTargetIndex = nextIndex
            }
            wrappedPlayer.seekTo(newTargetIndex, positionMs)
        }

        override fun seekToPreviousMediaItem() {
            crossfade.onSkipRequested()
            if (cast.active) {
                castPrevious()
                return
            }
            wrappedPlayer.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNextMediaItem()
        }

        override fun seekToNext() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNext()
        }
    }

    /**
     * Handles browsing and content resolution for Android Auto and other MediaBrowser clients.
     *
     * Every folder here is built from the device's own library — the same lists
     * the app reads — so a browser never waits on a network and never sees a
     * track that is not on this device.
     */
    private inner class MediaLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(favoriteCommand)
                .add(shuffleCommand)
                .add(reorderQueueCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
                carControllers += controller
                refreshCustomLayouts()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) refreshCustomLayouts()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> toggleShuffleFromSession()
                ACTION_REORDER_QUEUE -> player?.let { QueueShuffle.reorderFromCommand(it, args) }
                ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem?.mediaId?.let {
                    toggleFavoriteFromNotification(it)
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val isRecent = params?.isRecent == true ||
                params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
                params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true

            val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
            val rootExtras = buildContentStyleBundle(
                CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            )
            val rootItem = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setExtras(rootExtras)
                        .build(),
                )
                .build()
            val rootParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val isGridFolder = parentId == MEDIA_ROOT_ID || parentId == MEDIA_PLAYLISTS_ID
            val items: List<MediaItem> = when (parentId) {
                MEDIA_ROOT_ID -> listOf(
                    createFolderItem(MEDIA_QUICK_PICKS_ID, getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                    createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                )
                MEDIA_RECENTS_ID -> {
                    val songs = recentSongs()
                    songItems(songs) {
                        createInfoItem(
                            "msg:empty_recents",
                            getString(R.string.auto_empty_recents_title),
                            getString(R.string.auto_empty_recents_subtitle),
                        )
                    }
                }
                MEDIA_QUICK_PICKS_ID -> {
                    val songs = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                        .shuffled()
                        .take(MAX_AUTO_QUICK_PICKS)
                    songItems(songs) {
                        createInfoItem(
                            "msg:empty_quick_picks",
                            getString(R.string.auto_empty_quick_picks_title),
                            getString(R.string.auto_empty_quick_picks_subtitle),
                        )
                    }
                }
                MEDIA_LIKED_ID -> {
                    val songs = LocalPlaylistStore.getFavorites().songs
                    songItems(songs) {
                        createInfoItem(
                            "msg:empty_liked",
                            getString(R.string.auto_empty_liked_title),
                            getString(R.string.auto_empty_liked_subtitle),
                        )
                    }
                }
                MEDIA_PLAYLISTS_ID -> {
                    val playlists = LocalPlaylistStore.playlists.value
                        .filterNot { it.id == LocalPlaylistStore.FAVORITES_ID }
                    if (playlists.isNotEmpty()) {
                        playlists.map(::playlistToGridItem)
                    } else {
                        listOf(
                            createInfoItem(
                                "msg:empty_playlists",
                                getString(R.string.auto_empty_playlists_title),
                                getString(R.string.auto_empty_playlists_subtitle),
                            ),
                        )
                    }
                }
                MEDIA_LOCAL_MUSIC_ID -> {
                    val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                    songItems(local) {
                        createInfoItem(
                            "msg:empty_local",
                            getString(R.string.auto_empty_offline_title),
                            getString(R.string.auto_empty_offline_subtitle),
                        )
                    }
                }
                else -> {
                    val songs = when {
                        parentId.startsWith("playlist:") ->
                            LocalPlaylistStore.getPlaylist(parentId.removePrefix("playlist:"))?.songs
                        else -> listOfNotNull(songCache[parentId])
                    }.orEmpty()
                    songItems(songs) { null }
                }
            }
            val returnParams = LibraryParams.Builder().setExtras(
                buildContentStyleBundle(
                    if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                ),
            ).build()
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), returnParams)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val item = when (mediaId) {
                MEDIA_ROOT_ID -> MediaItem.Builder()
                    .setMediaId(MEDIA_ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.app_name))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                            .build(),
                    ).build()
                MEDIA_RECENTS_ID -> createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_QUICK_PICKS_ID -> createFolderItem(MEDIA_QUICK_PICKS_ID, getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_PLAYLISTS_ID -> createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
                MEDIA_LIKED_ID -> createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_LOCAL_MUSIC_ID -> createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music))
                else -> {
                    if (mediaId.startsWith("msg:")) {
                        createInfoItem(
                            mediaId,
                            getString(R.string.auto_unavailable_title),
                            getString(R.string.auto_unavailable_subtitle),
                        )
                    } else {
                        val cached = songCache[mediaId]
                        if (cached != null) {
                            cached.toMediaItem()
                        } else if (mediaId.startsWith("playlist:")) {
                            createGridPlaylistItem(
                                mediaId = mediaId,
                                title = getString(R.string.playlists),
                                folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                            )
                        } else {
                            createInfoItem(
                                "msg:unavailable|$mediaId",
                                getString(R.string.auto_unavailable_title),
                                getString(R.string.auto_unavailable_subtitle),
                            )
                        }
                    }
                }
            }
            LibraryResult.ofItem(item, null)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            // 1. If the player currently holds items in its queue, resume from it.
            val activePlayer = player
            if (activePlayer != null && activePlayer.mediaItemCount > 0) {
                val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
                val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
                val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(currentItems),
                    startIndex,
                    startPositionMs,
                )
            }

            // 2. Otherwise the device's own library.
            val localSongs = LocalMediaRepository.getLocalMusic(this@PlaybackService)
            if (localSongs.isNotEmpty()) {
                val items = localSongs.mapNotNull { it.toMediaItemOrNull() }
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(items),
                    0,
                    0L,
                )
            }

            // Return empty instead of throwing an unhandled exception to prevent a crash.
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.of(),
                0,
                0L,
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
            val songs = searchLocal(query)
            storeSearchResults(query, songs)
            session.notifySearchResultChanged(browser, query, songs.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val songs = searchResults[query] ?: searchLocal(query).also { storeSearchResults(query, it) }
            songCache.putAll(songs.associateBy { it.videoId })
            val items = songs.mapNotNull { it.toMediaItemOrNull() }
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = resolveMediaItems(mediaItems)
            val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(resolved),
                validIndex,
                startPositionMs.coerceAtLeast(0L),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            resolveMediaItems(mediaItems)
        }

        /**
         * Turns the folder/playlist rows a controller asked to play into their
         * tracks, and drops anything with no file behind it.
         */
        private suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val id = item.mediaId
                val searchQuery = item.requestMetadata.searchQuery
                val songs: List<Song> = when {
                    !searchQuery.isNullOrBlank() -> {
                        searchResults[searchQuery] ?: searchLocal(searchQuery).also {
                            storeSearchResults(searchQuery, it)
                        }
                    }
                    id.startsWith("playlist:") ->
                        LocalPlaylistStore.getPlaylist(id.removePrefix("playlist:"))?.songs.orEmpty()
                    id == MEDIA_ROOT_ID || id == MEDIA_RECENTS_ID -> recentSongs()
                    id == MEDIA_QUICK_PICKS_ID ->
                        LocalMediaRepository.getLocalMusic(this@PlaybackService).shuffled()
                    id == MEDIA_LIKED_ID -> LocalPlaylistStore.getFavorites().songs
                    id == MEDIA_PLAYLISTS_ID ->
                        LocalPlaylistStore.playlists.value.flatMap { it.songs }
                    id == MEDIA_LOCAL_MUSIC_ID -> LocalMediaRepository.getLocalMusic(this@PlaybackService)
                    else -> listOfNotNull(songCache[id])
                }
                if (id in listOf(MEDIA_ROOT_ID, MEDIA_RECENTS_ID, MEDIA_QUICK_PICKS_ID, MEDIA_LIKED_ID,
                        MEDIA_PLAYLISTS_ID, MEDIA_LOCAL_MUSIC_ID) || id.startsWith("playlist:") ||
                    !searchQuery.isNullOrBlank()
                ) {
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.mapNotNull { it.toMediaItemOrNull() })
                } else {
                    val requestUri = item.requestMetadata.mediaUri
                    if (item.localConfiguration != null) {
                        resolved.add(item)
                    } else if (requestUri != null) {
                        resolved.add(item.buildUpon().setUri(requestUri).build())
                    } else {
                        item.toSong().toMediaItemOrNull()?.let(resolved::add)
                    }
                }
            }
            return if (resolved.isEmpty()) mediaItems.filter { it.localConfiguration != null } else resolved
        }
    }

    private fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
        val fromLong = page.toLong() * safePageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    private fun createGridPlaylistItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(folderType)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()

    private fun playlistToGridItem(playlist: LocalPlaylist): MediaItem = createGridPlaylistItem(
        mediaId = "playlist:${playlist.id}",
        title = playlist.title,
        subtitle = playlist.songs.size.toString(),
        artworkUrl = playlist.songs.firstOrNull()?.thumbnailUrl,
    )

    /** The tracks for [songs], with [orElse] standing in when none of them has a file. */
    private fun songItems(songs: List<Song>, orElse: () -> MediaItem?): List<MediaItem> {
        songs.forEach { songCache[it.videoId] = it }
        val items = songs.mapNotNull { it.toMediaItemOrNull() }
        return items.ifEmpty { listOfNotNull(orElse()) }
    }

    /** What has actually been played on this device, most recent first. */
    private suspend fun recentSongs(): List<Song> = runCatching {
        ListeningStats.getRecentPlayedTracks()
            .map { it.first }
            .distinctBy { it.videoId }
            .take(MAX_AUTO_RECENTS)
    }.getOrDefault(emptyList())

    /** Matches [query] against the device library, in memory. */
    private suspend fun searchLocal(query: String): List<Song> {
        val safeQuery = query.trim().take(MAX_AUTO_SEARCH_QUERY_CHARS)
        if (safeQuery.isEmpty()) return emptyList()
        val terms = safeQuery.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return LocalMediaRepository.getLocalMusic(this)
            .asSequence()
            .filter { song ->
                val haystack = listOfNotNull(song.title, song.artist, song.albumName)
                    .joinToString(" ")
                    .lowercase(Locale.ROOT)
                terms.all { haystack.contains(it) }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .take(MAX_AUTO_SEARCH_RESULTS)
            .toList()
    }

    private fun storeSearchResults(query: String, songs: List<Song>) {
        while (searchResults.size >= MAX_AUTO_SEARCH_CACHE_ENTRIES) {
            val key = searchResults.keys.firstOrNull() ?: break
            searchResults.remove(key)
        }
        searchResults[query] = songs
        songs.forEach { songCache[it.videoId] = it }
    }

    private fun createFolderItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
        isGrid: Boolean = false,
    ): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    private companion object {
        const val MEDIA_ROOT_ID = "root"
        const val MEDIA_RECENTS_ID = "recents"
        const val MEDIA_QUICK_PICKS_ID = "quick_picks"
        const val MEDIA_PLAYLISTS_ID = "playlists"
        const val MEDIA_LIKED_ID = "liked"
        const val MEDIA_LOCAL_MUSIC_ID = "local_music"
        const val MAX_AUTO_PAGE_SIZE = 50
        const val MAX_AUTO_SEARCH_QUERY_CHARS = 200
        const val MAX_AUTO_SEARCH_CACHE_ENTRIES = 20
        /** How many of the device's own tracks "Quick Picks" offers. */
        const val MAX_AUTO_QUICK_PICKS = 25
        /** How many local matches a browse search returns. */
        const val MAX_AUTO_SEARCH_RESULTS = 50
        /** How many recent plays "Recents" offers. */
        const val MAX_AUTO_RECENTS = 50

        // Android Auto Content Style Hints
        const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4
        /**
         * Shared by both players. Identical on purpose: they take turns being
         * the session, and a difference here would be an audible change of
         * routing at the handoff.
         */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        /** Length of a FLAC STREAMINFO block, which the format fixes at 34 bytes. */
        const val STREAM_INFO_BYTES = 34

        const val CHANNEL_ID = "bitchord_playback"
        const val SESSION_ID = "BitChordPlayback"

        /** How often played-seconds are sampled off the player. */
        const val PROGRESS_SAMPLE_MS = 5_000L

        /** Shortest gap "skip silence" is allowed to touch. */
        const val MIN_SILENCE_US = 1_000_000L

        /** Past any song, so the byte ceiling is what stops loading. */
        const val FAR_BUFFER_MS = 15 * 60 * 1000

        /** Enough of the track to cover a transition's read-ahead without hoarding memory. */
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024

        /**
         * A short nudge backwards, and no more: this shares the byte ceiling
         * above with the read-ahead it would otherwise starve.
         */
        const val BACK_BUFFER_MS = 30 * 1000

        /** Enough to cover the decoder's own latency, not seconds of dead air. */
        const val START_PLAYBACK_MS = 500

        /** More room after a stall than at the start — see the load control. */
        const val RESUME_PLAYBACK_MS = 2_000

        /** How many times one track is picked up off the floor — see [recoverFrom]. */
        const val MAX_RECOVERIES = 2

        /**
         * How many tracks in a row may be skipped for a plain playback error
         * before the queue is left alone — see [skipReason]. Sized to walk a
         * short run of broken tracks without walking an entire queue whose
         * files have all gone missing.
         */
        const val MAX_CONSECUTIVE_SKIPS = 4

        /**
         * The pause before a retry. Media3 lets go of a source asynchronously,
         * so the discard needs a moment to land before the same track is asked
         * for again.
         */
        const val RECOVERY_DELAY_MS = 350L
    }
}
