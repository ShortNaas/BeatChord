package com.music.beatchord.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.beatchord.R
import com.music.beatchord.data.LocalMediaRepository
import com.music.beatchord.data.lyrics.EmbeddedLyrics
import com.music.beatchord.data.lyrics.LyricLine
import com.music.beatchord.data.lyrics.LyricsRepository
import com.music.beatchord.data.lyrics.LyricsSource
import com.music.beatchord.data.model.BrowseType
import com.music.beatchord.data.model.DetailPage
import com.music.beatchord.data.model.HomeShelf
import com.music.beatchord.data.model.LibraryPage
import com.music.beatchord.data.model.PlaylistPrivacy
import com.music.beatchord.data.model.SearchHistoryEntity
import com.music.beatchord.data.model.ShelfItem
import com.music.beatchord.data.model.Song
import com.music.beatchord.data.model.UiState
import com.music.beatchord.data.model.UserPlaylist
import com.music.beatchord.data.settings.AppSettings
import com.music.beatchord.data.settings.SearchHistory
import com.music.beatchord.data.spotify.LocalPlaylist
import com.music.beatchord.data.spotify.LocalPlaylistStore
import com.music.beatchord.data.stats.ListeningStats
import com.music.beatchord.ui.screens.matchesSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    /**
     * Always false: the local feed is one page. Every shelf in it comes from a
     * single read of the device, so there is never a next page on its way.
     * Kept because the feed draws its tail off this.
     */
    val homeLoadingMore: StateFlow<Boolean> = MutableStateFlow(false)

    /** Always false, for the same reason: no shelf fills in behind the first one. */
    val homeRecentlyPlayedLoading: StateFlow<Boolean> = MutableStateFlow(false)

    /** Guards a feed read that a later one has already replaced. */
    private val homeLoadGeneration = AtomicLong(0L)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Increments once per first-page request so the UI can reset its list. */
    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    /** Every track in the Local Music folder, read when the Library source is picked. */
    private val _librarySongs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)

    /**
     * The Library source's answer for the current query: the Local Music
     * folder narrowed by the same match its own filter box uses, so the two
     * places never disagree about what is on the device. Null while the field
     * is empty — there is nothing asked yet.
     */
    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_librarySongs, _query) { state, query ->
        val term = query.trim()
        when {
            term.isEmpty() -> null
            state is UiState.Success -> UiState.Success(state.data.filter { it.matchesSearch(term) })
            else -> state
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    /** Completed hits are retained for the playing track so choosing one is instant. */
    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: Pair<String, Set<LyricsSource>>? = null

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a track's own tags may already carry
     * the lyrics, and asking the servers again is a round trip to arrive at a
     * string that is on disk — and one that fails outright with the connection
     * off, which is what made a local song show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
        localPath: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = videoId to sources
        if (lyricsFor == key) return
        // The duration lands a beat after the track, and a database match needs
        // it. Turned away here rather than inside the job below: claiming the
        // lookup first and giving it up asynchronously means the very re-trigger
        // that carries the duration can arrive while the claim still stands, be
        // dropped as a duplicate, and leave the track marked as being looked up
        // by nobody — which is what left a paused track loading for ever, since
        // pausing is when the duration is most likely to arrive a frame late.
        if (localUri == null && durationMs <= 0L) return
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(videoId, title, artist, durationMs, album)
        selectedLyricsSource = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    _lyrics.value = embedded
                    // No source to name: what the file records is the lyrics,
                    // not which of the eight services they came from months ago.
                    _lyricsSource.value = null
                    _lyricsChecked.value = true
                    return@launch
                }
            }

            // Check offline cached lyrics before attempting network access
            val cachedLyrics = com.music.beatchord.data.lyrics.LocalLyricsStore.get(
                getApplication(),
                videoId,
                title,
                artist,
                localUri,
                localPath,
            )
            if (cachedLyrics != null) {
                _lyrics.value = cachedLyrics.lines
                _lyricsSource.value = cachedLyrics.source
                _lyricsChecked.value = true
                return@launch
            }

            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result ->
                    providerFinished(generation, source, result)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            _lyrics.value = selected?.lines
            _lyricsSource.value = selected?.source
            _lyricsChecked.value = true
            selected?.lines?.let { lines ->
                com.music.beatchord.data.lyrics.LocalLyricsStore.save(
                    context = getApplication(),
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    localUri = localUri,
                    localPath = localPath,
                    lines = lines,
                    source = selected.source,
                )
            }
        }
    }

    /**
     * Selects a provider from the player drawer. Completed hits are applied
     * from memory; misses are inert; only an untouched provider goes online.
     */
    fun selectLyricsProvider(source: LyricsSource) {
        val request = currentLyricsRequest ?: return
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                // Apply it as soon as the already-running automatic attempt completes.
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED, null -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = viewModelScope.launch {
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result ->
                    providerFinished(generation, provider, result)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            _lyricsProviderStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        if (selectedLyricsSource == source) showLyricsResult(result)
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        // A tap may have selected a provider while the priority race was still
        // using it. If that race then cancels the loser, honour the tap with a
        // dedicated request instead of leaving the row stuck at "Fetching".
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _lyricsSource.value = result.source
        _lyricsChecked.value = true
    }

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    // ---- Library and playlists ---------------------------------------------

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /** The device's own playlists, for the picker and the playlist page. */
    val playlists: StateFlow<List<UserPlaylist>> =
        LocalPlaylistStore.playlists
            .map { list -> list.map { it.toUserPlaylist() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Always false: [playlists] follows the local store, so there is never a
     * fetch to wait on. Kept because the picker draws a spinner off it.
     */
    val playlistsLoading: StateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Which playlists the user may rename or delete, by the id a surface names
     * them by.
     *
     * Every playlist the app can show is one it made — there is no library to
     * have saved a stranger's into — so this is the store's own list, keyed
     * under both the raw id a card carries and the `local:playlist:` page id.
     * Observable because a card's menu opens without having fetched anything
     * and has to recompose when the answer arrives.
     */
    val playlistOwned: StateFlow<Map<String, Boolean>> =
        LocalPlaylistStore.playlists
            .map { list -> list.flatMap { listOf(it.id, it.browseId) }.associateWith { true } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The list is the store's own and follows it — see [playlists] — so there
     * is nothing left to fetch. Kept for the call sites that used to.
     */
    fun loadPlaylists() {
    }

    /**
     * Adds [song] to every playlist in [playlists] — the picker's ticked rows —
     * and reports how each went, once, when they all have.
     *
     * Everything here is local, so there is no request to be refused and no
     * rate limit to pace: a playlist that already holds the track is counted as
     * such rather than added to twice, and a playlist's own page is updated in
     * place rather than re-read.
     */
    fun addToPlaylists(
        playlists: List<UserPlaylist>,
        song: Song,
        onResult: (added: Int, alreadyThere: Int, failed: Int) -> Unit = { _, _, _ -> },
    ) {
        if (playlists.isEmpty()) return
        var added = 0
        var alreadyThere = 0
        var failed = 0
        playlists.forEach { target ->
            val playlist = localPlaylist(target.playlistId)
            when {
                playlist == null -> failed += 1
                playlist.songs.any { it.isEntryFor(song) } -> alreadyThere += 1
                else -> {
                    LocalPlaylistStore.addSongsToPlaylist(playlist.id, listOf(song))
                    appendToOpenPlaylist(playlist, song)
                    added += 1
                }
            }
        }
        onResult(added, alreadyThere, failed)
    }

    /**
     * Creates a playlist, seeded with [song] when the flow started from a
     * track's menu — one write, so it can't half-succeed into an empty playlist
     * the user has to add to again.
     *
     * [privacy] is what the YouTube-era call site passes; a playlist on this
     * device has none, and is never sent anywhere.
     */
    fun createPlaylist(title: String, privacy: PlaylistPrivacy, song: Song? = null) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        LocalPlaylistStore.savePlaylist(name, listOfNotNull(song))
    }

    /**
     * Saves a playlist built from [songs] to the device.
     *
     * [videoIds] and [privacy] are what the old call site carried; a local
     * playlist is its rows and nothing else, and [onResult] can only ever
     * report it as saved locally — there is nowhere else for it to go.
     */
    fun createPlaylistWithVideoIds(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
        songs: List<Song> = emptyList(),
        onResult: ((browseId: String?, title: String, savedLocally: Boolean) -> Unit)? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        val created = LocalPlaylistStore.savePlaylist(name, songs)
        onResult?.invoke(created.browseId, created.title, true)
    }

    /**
     * Drops [song] from the local playlist [browseId] names, and takes the row
     * out from under the reader rather than waiting for a re-read.
     */
    fun removeFromPlaylist(browseId: String, song: Song) {
        val playlist = localPlaylist(browseId) ?: return
        val index = playlist.songs.indexOfFirst { it.isEntryFor(song) }
        if (index < 0) return
        LocalPlaylistStore.removeSongFromPlaylist(playlist.id, index)
        // Restated by id rather than by index: the reader may have opened this
        // page by either of the ids it answers to.
        val names = setOf(browseId, playlist.id, playlist.browseId)
        _detailStack.value = _detailStack.value.map { page ->
            val songs = (page.songs as? UiState.Success<List<Song>>)?.data
            if (page.browseId !in names || songs == null) {
                page
            } else {
                val remaining = songs.toMutableList()
                val at = remaining.indexOfFirst { it.isEntryFor(song) }
                if (at >= 0) remaining.removeAt(at)
                page.copy(songs = UiState.Success(remaining))
            }
        }
    }

    /**
     * Puts [song] at the end of [playlist]'s page, if that page is open —
     * where the store puts it, so the order survives the next read.
     *
     * An empty playlist counts as open: it renders as an empty-state message,
     * and the first track added to one has to replace that message rather than
     * be dropped for want of a list to join. Any other error is a page that
     * failed to load, whose real contents are unknown, and answering it with a
     * one-track listing would be a playlist invented out of a failure. A page
     * still loading is left alone too: the read in flight is newer than this
     * and will land with the addition already in it.
     */
    private fun appendToOpenPlaylist(playlist: LocalPlaylist, song: Song) {
        // Either spelling of the page id — a card on the Playlists tab pushes
        // the raw one and a link elsewhere pushes the `local:playlist:` form.
        val names = setOf(playlist.browseId, playlist.id)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId !in names) return@map page
            val songs = when (val state = page.songs) {
                is UiState.Success -> state.data
                is UiState.Error ->
                    if (state.message == text(R.string.spotify_import_empty_playlist)) emptyList() else return@map page
                UiState.Loading -> return@map page
            }
            // Already there — a duplicate row is not something the user asked
            // for, however the addition got here.
            if (songs.any { it.isEntryFor(song) }) return@map page
            page.copy(songs = UiState.Success(songs + song))
        }
    }

    /**
     * Renames a playlist, and says so on its own page — see [setPlaylistTitle].
     * Renaming is nearly always done from the playlist's page or its card, so
     * there is always something on screen still showing the old name.
     */
    fun renamePlaylist(playlist: UserPlaylist, title: String) {
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        val stored = localPlaylist(playlist.playlistId) ?: return
        LocalPlaylistStore.renamePlaylist(stored.id, name)
        setPlaylistTitle(stored, name)
    }

    /**
     * Restates a playlist's name on its own open page — header and top bar
     * both, which read [DetailPage.title]. Everywhere else reads the store
     * directly, and the rename has already been written there.
     */
    private fun setPlaylistTitle(playlist: LocalPlaylist, title: String) {
        _detailStack.value = _detailStack.value.map {
            if (it.browseId == playlist.browseId || it.browseId == playlist.id) {
                it.copy(title = title)
            } else {
                it
            }
        }
    }

    /** The playlist's entries as they stand on the device, for the reorder sheet. */
    fun loadPlaylistEntries(playlist: UserPlaylist, onResult: (Result<List<Song>>) -> Unit) {
        val stored = localPlaylist(playlist.playlistId)
        if (stored == null) {
            onResult(Result.failure(IllegalStateException("No such playlist")))
        } else {
            onResult(Result.success(stored.songs))
        }
    }

    /**
     * Saves a new running order for [playlist], and puts the open page — if
     * this playlist's is — into that order straight away rather than after a
     * re-read.
     *
     * The store has no "replace this playlist's rows" verb, so the new order is
     * written as the rows coming out and going back in order. That is one write
     * per row, which is nothing for a hand-ordered playlist, and it leaves the
     * playlist's own id — and so every page and link that names it — intact.
     */
    fun reorderPlaylist(
        playlist: UserPlaylist,
        original: List<Song>,
        reordered: List<Song>,
        onResult: (Boolean) -> Unit = {},
    ) {
        val stored = localPlaylist(playlist.playlistId) ?: return onResult(false)
        // The sheet's order was built against a list of this length. A store
        // that has moved since means the order it is offering is for rows that
        // are no longer all there, and writing it would drop the difference.
        if (stored.songs.size != original.size || stored.songs.size != reordered.size) {
            return onResult(false)
        }
        stored.songs.indices.reversed().forEach { index ->
            LocalPlaylistStore.removeSongFromPlaylist(stored.id, index)
        }
        LocalPlaylistStore.addSongsToPlaylist(stored.id, reordered)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId != stored.browseId && page.browseId != stored.id) {
                page
            } else if (page.songs !is UiState.Success) {
                page
            } else {
                page.copy(songs = UiState.Success(reordered))
            }
        }
        onResult(true)
    }

    fun deletePlaylist(playlist: UserPlaylist) {
        val stored = localPlaylist(playlist.playlistId) ?: return
        LocalPlaylistStore.deletePlaylist(stored.id)
        // Its page may be the one open; a deleted playlist has nothing left to
        // show.
        _detailStack.value = _detailStack.value.filterNot {
            it.browseId == stored.browseId || it.browseId == stored.id
        }
    }

    /**
     * The playlist [browseId] names, when the user may rename or delete it.
     *
     * Every playlist on the device is the user's own, so this is the playlist
     * itself or nothing — there is no "saved from someone else's library" left
     * to rule out.
     */
    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null) return null
        return localPlaylist(browseId)?.toUserPlaylist()
    }

    /**
     * A no-op: every playlist on the device is owned by construction, and
     * [playlistOwned] already says so. Kept because the menus still ask when
     * they open.
     */
    fun resolvePlaylistOwnership(browseId: String?) {
    }

    /**
     * The local playlist a browse id — or a raw playlist id — names.
     *
     * Both spellings arrive: the Playlists tab opens a card by its own id, while
     * a page pushed from elsewhere carries the `local:playlist:` form, and
     * [UserPlaylist] synthesises a `VL` prefix over the raw id. The store
     * understands each, so this only has to shed the one prefix it does not.
     */
    private fun localPlaylist(id: String): LocalPlaylist? =
        LocalPlaylistStore.getPlaylist(id.removePrefix("VL"))

    private fun LocalPlaylist.toUserPlaylist(): UserPlaylist = UserPlaylist(
        playlistId = id,
        title = title,
        subtitle = trackCount(songs.size),
        thumbnailUrl = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
    )

    /** Whether [other] is already a row of this playlist. */
    private fun Song.isEntryFor(other: Song): Boolean =
        videoId == other.videoId || (localUri != null && localUri == other.localUri)

    /**
     * Whether the library needs re-reading. Set by anything that changes which
     * files a shelf would list, and acted on when the tab is next opened:
     * rearranging a page under whoever is reading it is worse than showing it a
     * moment out of date.
     */
    private var libraryStale = false

    /** As [libraryStale], for the home feed. */
    private var homeStale = false

    init {
        loadHome()
        loadLibrary()
        viewModelScope.launch {
            // A play changes the counts behind three of the Library's five
            // shelves and the Recents shelf on Home. Both are re-read when the
            // tab is next shown rather than now: the page that is up is about
            // the track that is playing, and rebuilding it mid-track would
            // shuffle rows under the reader.
            ListeningStats.revision.drop(1).collect {
                homeStale = true
                libraryStale = true
            }
        }
        loadLibrarySongs()
        viewModelScope.launch {
            // drop(1): the current value is just the setting as it stands, not
            // a change to it.
            AppSettings.filterNonMusicAudio.drop(1).collect {
                // Which files are music decides every shelf, so both feeds are
                // now out of date — and the folder page, if it is open.
                homeStale = true
                libraryStale = true
                if (_detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
    }

    /**
     * Call when the home tab becomes visible.
     *
     * Nothing goes stale on its own here: the feed reads the device and nothing
     * else, so a play changes none of its shelves and the network is not there
     * to drift. A pull-to-refresh, a scan, or a setting that changes which files
     * are music is what re-reads it — and a feed that failed is retried, so a
     * permission granted since shows its shelves.
     */
    fun onHomeShown() {
        val stale = homeStale
        homeStale = false
        if (stale || _home.value is UiState.Error) loadHome()
    }

    /** Call when the library tab becomes visible. */
    fun onLibraryShown() {
        if (!libraryStale) return
        libraryStale = false
        if (_library.value is UiState.Success) refresh(Feed.LIBRARY)
    }

    /**
     * A feed that can be pulled down to refresh. Tracked per feed rather than
     * as one flag: a pull on Library while Home is still refreshing in the
     * background shouldn't leave the wrong tab showing a loader.
     */
    enum class Feed { HOME, EXPLORE, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    /**
     * Re-reads [feed] in place. Unlike the `load*` entry points this leaves
     * the current content on screen rather than dropping back to the loading
     * state — a refresh that swapped the page for a spinner would be a worse
     * experience than the stale content it replaces.
     */
    fun refresh(feed: Feed) {
        if (feed in _refreshing.value) return
        _refreshing.value = _refreshing.value + feed
        viewModelScope.launch {
            when (feed) {
                Feed.HOME -> loadHome()
                // The Playlists tab reads the local playlist store directly, so
                // there is no feed of ours behind it to re-read.
                Feed.EXPLORE -> Unit
                Feed.LIBRARY -> fetchLibrary()
            }
            _refreshing.value = _refreshing.value - feed
        }
    }

    /** Tapping a tab should leave any pushed page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
    }

    fun loadHome() {
        val generation = homeLoadGeneration.incrementAndGet()
        _home.value = UiState.Loading

        viewModelScope.launch {
            val context = getApplication<Application>()
            if (!LocalMediaRepository.hasStoragePermission(context)) {
                if (isCurrentHomeLoad(generation)) {
                    _home.value = UiState.Error(text(R.string.storage_required_read))
                }
                return@launch
            }
            val localSongs = runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())
            if (localSongs.isEmpty()) {
                if (isCurrentHomeLoad(generation)) {
                    _home.value = UiState.Error(text(R.string.no_local_audio_found))
                }
                return@launch
            }

            val shelves = mutableListOf<HomeShelf>()

            // 1. Recently Added (sorted by localDateAddedSeconds DESC)
            val recentlyAdded = localSongs.sortedByDescending { it.localDateAddedSeconds ?: 0L }.take(15)
            if (recentlyAdded.isNotEmpty()) {
                shelves.add(
                    HomeShelf(
                        title = text(R.string.shelf_recently_added),
                        items = recentlyAdded.map { it.toLocalShelfItem() },
                    )
                )
            }

            // 2. Artists (shuffled each time app opens)
            val artists = localSongs.groupBy { it.artist }
                .filterKeys { it.isNotBlank() && it != "Unknown Artist" }
                .map { (artist, songs) ->
                    ShelfItem(
                        title = artist,
                        subtitle = trackCount(songs.size),
                        thumbnailUrl = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                        videoId = null,
                        browseId = "local:artist:$artist",
                    )
                }
                .shuffled()
                .take(15)
            if (artists.isNotEmpty()) {
                shelves.add(HomeShelf(title = text(R.string.shelf_artists), items = artists))
            }

            // 3. Albums (shuffled each time app opens)
            val albums = localSongs.groupBy { it.albumName }
                .filterKeys { !it.isNullOrBlank() }
                .map { (album, songs) ->
                    ShelfItem(
                        title = album!!,
                        subtitle = songs.firstOrNull()?.artist ?: "",
                        thumbnailUrl = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                        videoId = null,
                        browseId = "local:album:$album",
                    )
                }
                .shuffled()
                .take(15)
            if (albums.isNotEmpty()) {
                shelves.add(HomeShelf(title = text(R.string.shelf_albums), items = albums))
            }

            // 4. Quick Picks (shuffled each time app opens)
            shelves.add(
                HomeShelf(
                    title = text(R.string.shelf_quick_picks),
                    items = localSongs.shuffled().take(15).map { it.toLocalShelfItem() },
                )
            )

            if (isCurrentHomeLoad(generation)) {
                _home.value = UiState.Success(shelves)
            }
        }
    }

    private fun Song.toLocalShelfItem(): ShelfItem = ShelfItem(
        title = title,
        subtitle = artist,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId,
        browseId = null,
    )

    private fun isCurrentHomeLoad(generation: Long) = generation == homeLoadGeneration.get()

    /**
     * The local feed is one page — every shelf in it came from a single read of
     * the device — so there is never a next one. Kept because the feed still
     * asks.
     */
    fun loadMoreHome() {
    }

    fun loadLibrary() {
        _library.value = UiState.Loading
        viewModelScope.launch { fetchLibrary() }
    }

    private suspend fun fetchLibrary() {
        val context = getApplication<Application>()
        if (!LocalMediaRepository.hasStoragePermission(context)) {
            _library.value = UiState.Error(text(R.string.storage_required_read))
            return
        }
        val localSongs = runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())

        val shelves = mutableListOf<HomeShelf>()
        val playsMap = runCatching { ListeningStats.getTrackPlaysMap() }.getOrDefault(emptyMap())

        // 1. All Songs shelf
        if (localSongs.isNotEmpty()) {
            val allSongsItems = localSongs.take(15).map { song ->
                ShelfItem(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = "library:all_songs",
                )
            }
            shelves.add(
                HomeShelf(
                    title = text(R.string.shelf_all_songs),
                    subtitle = trackCount(localSongs.size),
                    items = allSongsItems,
                    key = "all_songs",
                )
            )
        }

        // 2. Most Played shelf
        val mostPlayed = localSongs
            .map { it to (playsMap[it.videoId] ?: playsMap[it.localUri] ?: 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        if (mostPlayed.isNotEmpty()) {
            val mostPlayedItems = mostPlayed.take(15).map { song ->
                val p = playsMap[song.videoId] ?: playsMap[song.localUri] ?: 0
                ShelfItem(
                    title = song.title,
                    subtitle = "${playCount(p)} • ${song.artist}",
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = "library:most_played",
                )
            }
            shelves.add(
                HomeShelf(
                    title = text(R.string.shelf_most_played),
                    subtitle = text(R.string.shelf_subtitle_top_tracks),
                    items = mostPlayedItems,
                    key = "most_played",
                )
            )
        }

        // 3. Recently Played shelf
        val recentPairs = runCatching { ListeningStats.getRecentPlayedTracks() }.getOrDefault(emptyList())
        val recentSongs = recentPairs.map { it.first }
        if (recentSongs.isNotEmpty()) {
            val recentItems = recentSongs.take(15).map { song ->
                ShelfItem(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = "library:recently_played",
                )
            }
            shelves.add(
                HomeShelf(
                    title = text(R.string.shelf_recently_played),
                    subtitle = text(R.string.shelf_subtitle_recent_tracks),
                    items = recentItems,
                    key = "recently_played",
                )
            )
        }

        // 4. Recently Added shelf
        val recentlyAdded = localSongs
            .filter { (it.localDateAddedSeconds ?: 0L) > 0 }
            .sortedByDescending { it.localDateAddedSeconds ?: 0L }
        if (recentlyAdded.isNotEmpty()) {
            val addedItems = recentlyAdded.take(15).map { song ->
                ShelfItem(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = "library:recently_added",
                )
            }
            shelves.add(
                HomeShelf(
                    title = text(R.string.shelf_recently_added),
                    subtitle = text(R.string.shelf_subtitle_new_additions),
                    items = addedItems,
                    key = "recently_added",
                )
            )
        }

        // 5. Never Played shelf
        val neverPlayed = localSongs.filter {
            (playsMap[it.videoId] ?: playsMap[it.localUri] ?: 0) == 0
        }
        if (neverPlayed.isNotEmpty()) {
            val neverItems = neverPlayed.take(15).map { song ->
                ShelfItem(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = "library:never_played",
                )
            }
            shelves.add(
                HomeShelf(
                    title = text(R.string.shelf_never_played),
                    subtitle = unplayedCount(neverPlayed.size),
                    items = neverItems,
                    key = "never_played",
                )
            )
        }

        _library.value = UiState.Success(
            LibraryPage(
                likedSongs = emptyList(),
                librarySongs = localSongs,
                shelves = shelves,
            )
        )
    }

    companion object {
        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            browseId.startsWith("local:artist:") -> BrowseType.ARTIST
            browseId.startsWith("local:album:") -> BrowseType.ALBUM
            browseId.startsWith("local:playlist:") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) {
        // A fast double tap used to push two identical loading pages and launch
        // two identical reads. The page is pushed synchronously, so this closes
        // that window without suppressing a deliberate revisit after the first
        // page loads.
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            // Callers that open an artist from a track — the player, the
            // long-press menu — only have that track's cover art and its credit
            // to hand, so the page swaps in the artist's own picture and name
            // once they are read off the files.
            val page = localPage(browseId)
            // An id this app cannot resolve at all — a stale link, a page from
            // a build that could still read a catalogue — says so rather than
            // loading for ever.
            val songs: UiState<List<Song>> =
                page?.songs ?: UiState.Error(text(R.string.no_tracks_here))
            // Update by id — the user may have pushed another page meanwhile.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId && it.songs is UiState.Loading) {
                    it.copy(
                        songs = songs,
                        thumbnailUrl = page?.artwork ?: it.thumbnailUrl,
                        title = page?.title ?: it.title,
                        subtitle = page?.credit ?: it.subtitle,
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * A local browse page: what it lists, and the header it has to supply.
     *
     * The cards that open one only know an id and whatever text was on the
     * card, so an artist, album, folder or playlist page reads its own name,
     * credit and cover back off the files.
     */
    private class LocalPage(
        val songs: UiState<List<Song>>,
        val title: String? = null,
        val credit: String? = null,
        val artwork: String? = null,
    )

    /**
     * Reads the page a local browse id names, or null for an id this app does
     * not know — there is no catalogue left to ask.
     */
    private suspend fun localPage(browseId: String): LocalPage? {
        val context = getApplication<Application>()
        val playlist = localPlaylist(browseId)
        if (playlist != null) {
            return LocalPage(
                songs = if (playlist.songs.isEmpty()) {
                    UiState.Error(text(R.string.spotify_import_empty_playlist))
                } else {
                    UiState.Success(playlist.songs)
                },
                title = playlist.title,
                credit = context.getString(R.string.local_playlist_subtitle, playlist.songs.size),
                artwork = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            )
        }
        if (browseId == "local:all") {
            if (!LocalMediaRepository.hasStoragePermission(context)) {
                return LocalPage(songs = UiState.Error(text(R.string.storage_required_read)))
            }
            val songs = localSongs()
            return LocalPage(
                songs = if (songs.isEmpty()) {
                    UiState.Error(text(R.string.no_local_audio_found))
                } else {
                    UiState.Success(songs)
                },
            )
        }
        if (browseId.startsWith("local:artist:")) {
            val artist = browseId.removePrefix("local:artist:")
            val songs = localSongs().filter { it.artist.equals(artist, ignoreCase = true) }
            return LocalPage(
                songs = if (songs.isEmpty()) {
                    UiState.Error(text(R.string.no_local_audio_found))
                } else {
                    UiState.Success(songs)
                },
                title = artist,
                credit = trackCount(songs.size),
                artwork = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            )
        }
        if (browseId.startsWith("local:album:")) {
            val album = browseId.removePrefix("local:album:")
            val songs = localSongs().filter { it.albumName.equals(album, ignoreCase = true) }
            return LocalPage(
                songs = if (songs.isEmpty()) {
                    UiState.Error(text(R.string.no_local_audio_found))
                } else {
                    UiState.Success(songs)
                },
                title = album,
                credit = songs.firstOrNull()?.artist ?: "",
                artwork = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            )
        }
        if (browseId.startsWith("local:folder:")) {
            val folder = browseId.removePrefix("local:folder:")
            val songs = localSongs().filter { it.localPath?.startsWith(folder) == true }
            return LocalPage(
                songs = if (songs.isEmpty()) {
                    UiState.Error(text(R.string.no_local_audio_found))
                } else {
                    UiState.Success(songs)
                },
                title = folder.substringAfterLast('/'),
                credit = trackCount(songs.size),
                artwork = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            )
        }
        if (browseId.startsWith("local:format:")) {
            val ext = browseId.removePrefix("local:format:")
            val songs = localSongs().filter { it.localPath?.endsWith(ext, ignoreCase = true) == true }
            return LocalPage(
                songs = if (songs.isEmpty()) {
                    UiState.Error(text(R.string.no_local_audio_found))
                } else {
                    UiState.Success(songs)
                },
                title = ext.uppercase(Locale.ROOT),
                credit = trackCount(songs.size),
                artwork = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            )
        }
        return null
    }

    /** Every track on the device, as the local pages read it. */
    private suspend fun localSongs(): List<Song> =
        runCatching { LocalMediaRepository.getLocalMusic(getApplication()) }.getOrDefault(emptyList())

    /**
     * Re-reads a page that is already on the stack — after the files under it
     * changed, say — leaving its header as it was, since that is what the user
     * opened.
     */
    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val page = localPage(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) it.copy(songs = page.songs) else it
            }
        }
    }

    /**
     * Home and search cards don't say what they point at. The local browse ids
     * are prefixed by kind, so use that.
     *
     * Public because the long-press menus ask the same question of a card
     * before offering to queue what is behind it — an artist is not a running
     * order, so it gets no queue actions.
     */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        return true
    }

    /** Recent searches, kept on device as rich entity records. */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    /**
     * Records a full entity payload in the history — used when the user taps
     * a suggestion, result row, or typeahead card so the recents list shows
     * real artwork and metadata instead of raw text.
     */
    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    /**
     * Runs a term the user picked out of a list rather than typed — a recent
     * search. There is nothing to submit: [libraryResults] answers every query
     * from the device as it changes.
     */
    fun searchFor(term: String) {
        _query.value = term
    }

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) {
        _query.value = newValue
        if (newValue.isBlank()) {
            // The list is about to switch from matches to recent searches (or
            // the empty state) — without this it can keep whatever scroll
            // offset the matches were left at, landing the new, much shorter
            // list somewhere other than the top.
            _searchScrollReset.value += 1
        }
    }

    /**
     * Reads the Local Music folder for the Library source. Re-read on every
     * switch to it, so files added since — or a permission granted since —
     * are in the next answer; the last list stays up meanwhile.
     */
    fun loadLibrarySongs() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _librarySongs.value = if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                runCatching { LocalMediaRepository.getLocalMusic(context) }.fold(
                    onSuccess = { songs ->
                        if (songs.isEmpty()) UiState.Error(text(R.string.no_local_audio_found))
                        else UiState.Success(songs)
                    },
                    onFailure = { UiState.Error(text(R.string.no_local_audio_found)) },
                )
            }
        }
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun quantity(id: Int, count: Int): String =
        getApplication<Application>().resources.getQuantityString(id, count, count)

    private fun trackCount(count: Int): String = quantity(R.plurals.shelf_track_count, count)

    private fun playCount(count: Int): String = quantity(R.plurals.shelf_play_count, count)

    private fun unplayedCount(count: Int): String = quantity(R.plurals.shelf_unplayed_count, count)
}
