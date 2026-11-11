package com.music.beatchord

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.beatchord.data.LocalMediaRepository
import com.music.beatchord.data.playlists.M3uImport
import com.music.beatchord.data.spotify.LocalPlaylistStore
import com.music.beatchord.data.NerdStats
import com.music.beatchord.data.TrackLog
import com.music.beatchord.data.model.BrowseType
import com.music.beatchord.data.model.HomeShelf
import com.music.beatchord.data.model.LikeStatus
import com.music.beatchord.data.model.PlaybackSourceType
import com.music.beatchord.data.model.ShelfItem
import com.music.beatchord.data.model.Song
import com.music.beatchord.data.model.UiState
import com.music.beatchord.data.model.UserPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.music.beatchord.data.model.durationMillis
import com.music.beatchord.data.settings.AppSettings
import com.music.beatchord.data.settings.LibrarySort
import com.music.beatchord.data.settings.ThemeMode
import com.music.beatchord.ui.screens.EqualizerScreen
import com.music.beatchord.ui.screens.LibraryReplayEntry
import com.music.beatchord.ui.screens.SettingsScreen
import com.music.beatchord.playback.PlayerDeepLink
import com.music.beatchord.playback.QueueCoordinator
import com.music.beatchord.playback.QueueCoordinator.asQueueEntry
import com.music.beatchord.playback.QueueShuffle
import com.music.beatchord.playback.QueueSource
import com.music.beatchord.data.model.QueueTier
import com.music.beatchord.playback.playSongs
import com.music.beatchord.playback.toMediaItem
import com.music.beatchord.playback.toSong
import com.music.beatchord.playback.toggleShuffle
import com.music.beatchord.ui.components.BrowseActionsSheet
import com.music.beatchord.ui.components.BrowseTarget
import com.music.beatchord.ui.components.ConfirmationAlert
import com.music.beatchord.ui.screens.PlaylistsTabScreen
import com.music.beatchord.ui.screens.LibraryCollectionScreen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.music.beatchord.ui.components.PlaylistPickerSheet
import com.music.beatchord.ui.components.ReorderPlaylistSheet
import com.music.beatchord.ui.components.LongPressOrigin
import com.music.beatchord.ui.components.SongActionsPresentation
import com.music.beatchord.ui.components.SongActionsSheet
import com.music.beatchord.ui.components.HeldContextMenu
import com.music.beatchord.ui.components.HeldItem
import com.music.beatchord.playback.rememberMediaController
import com.music.beatchord.playback.rememberPlayerState
import com.music.beatchord.ui.MainViewModel
import com.music.beatchord.ui.components.BottomFadeScrim
import com.music.beatchord.ui.components.FloatingBarsTapGuard
import com.music.beatchord.ui.components.BottomTab
import com.music.beatchord.ui.components.FLOATING_BAR_MAX_WIDTH
import com.music.beatchord.ui.components.FloatingBottomBar
import com.music.beatchord.ui.components.GlassNavBar
import com.music.beatchord.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.music.beatchord.ui.components.FrostedTopBar
import com.music.beatchord.ui.components.LocalAppBackdrop
import com.music.beatchord.ui.components.LocalLiquidGlassEnabled
import com.music.beatchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.beatchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.beatchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.beatchord.ui.components.isGlassSupported
import com.music.beatchord.ui.components.MiniPlayer
import com.music.beatchord.ui.components.QueueActionNotice
import com.music.beatchord.ui.components.QueueActionNoticeHost
import com.music.beatchord.ui.components.SearchField
import com.music.beatchord.sharedui.resources.Res as SharedRes
import com.music.beatchord.sharedui.resources.search_library_hint
import com.music.beatchord.ui.components.optimizedHazeEffect
import com.music.beatchord.ui.components.topBarContentPadding
import com.music.beatchord.ui.components.AppLanguageDialog
import com.music.beatchord.ui.components.TranslationLanguageDialog
import com.music.beatchord.ui.components.LyricsSourcesDialog
import com.music.beatchord.ui.icons.BeatChordIcons
import androidx.media3.common.Player
import com.music.beatchord.ui.player.NowPlayingScreen
import com.music.beatchord.ui.player.PlayerSheetMotion
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import com.music.beatchord.ui.components.MiniPlayerPull
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onSizeChanged
import com.music.beatchord.ui.player.LocalPlayerDock
import com.music.beatchord.ui.player.PlayerDock
import com.music.beatchord.ui.screens.DetailScreen
import com.music.beatchord.ui.screens.LocalMusicScreen
import com.music.beatchord.ui.screens.HomeScreen
import com.music.beatchord.ui.screens.LibraryGridPage
import com.music.beatchord.ui.screens.LibraryScreen
import com.music.beatchord.ui.screens.SearchScreen
import com.music.beatchord.data.settings.SongSort
import com.music.beatchord.ui.replay.ReplayScreen
import com.music.beatchord.ui.replay.cards
import com.music.beatchord.ui.replay.ReplayShareSheet
import com.music.beatchord.ui.replay.ReplayStories
import com.music.beatchord.ui.replay.ReplayStoryPage
import com.music.beatchord.ui.replay.rememberReplayState
import com.music.beatchord.ui.theme.BeatChordTheme
import com.music.beatchord.ui.theme.rememberArtworkPalette
import com.music.beatchord.ui.theme.SystemBarIcons
import com.music.beatchord.ui.utils.guardSheetFromContentTouches
import com.music.beatchord.ui.utils.rememberIosOverscrollFactory
import com.music.beatchord.ui.performance.resolvePerformanceRefreshRate
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** How much of an artist page's header scrolls away before the top fade begins. */
private const val TOP_FADE_START = 0.55f

/** How much of the header's height the fade takes to reach full strength. */
private const val TOP_FADE_RAMP = 0.3f


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before the composition, so a cold launch from a widget's artwork has
        // the request already standing by the time BeatChordApp first reads it.
        PlayerDeepLink.consume(intent)
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
            val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
            val iosOverscrollFactory = rememberIosOverscrollFactory()
            val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
            val composeView = LocalView.current
            LaunchedEffect(highPerformance, performanceRefreshRate, composeView) {
                applyPerformanceMode(highPerformance, performanceRefreshRate, composeView)
            }
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            BeatChordTheme(darkTheme = darkTheme) {
                // The glass surfaces sample this layer, and a layer records only
                // what is drawn into it — which, for BeatChord, is a page that
                // paints no background of its own. Everywhere a page is not
                // showing artwork the recording is transparent, so the glass had
                // nothing to blur there and you saw straight through it to the
                // sharp page underneath: album art came through the bar blurred
                // and text came through it untouched. The window's background is
                // the floor the pages have always been drawn against, so it is
                // laid down here too and the recording is opaque like the screen.
                val windowBackground = MaterialTheme.colorScheme.background
                val paintBackdrop: ContentDrawScope.() -> Unit = remember(windowBackground) {
                    {
                        drawRect(windowBackground)
                        drawContent()
                    }
                }
                val appBackdrop = rememberLayerBackdrop(onDraw = paintBackdrop)
                CompositionLocalProvider(
                    LocalOverscrollFactory provides iosOverscrollFactory,
                    LocalLiquidGlassEnabled provides liquidGlassEnabled,
                    LocalAppBackdrop provides appBackdrop,
                ) {
                // The window's width, measured rather than asked for.
                //
                // `Configuration.screenWidthDp` is the wrong question here: in a
                // freeform or desktop window it can report the display rather
                // than the window it is actually in, and it lands a beat late
                // when that window is dragged. The layout downstream splits in
                // two on the strength of this number and sizes both halves from
                // it, so a stale one is a player pane sized for a window that no
                // longer exists and a page squeezed to a sliver to pay for it.
                // A measured constraint cannot be stale — it is the very width
                // the split is about to be laid out in.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    BeatChordApp(
                        darkTheme = darkTheme,
                        windowWidth = maxWidth,
                        windowHeight = maxHeight,
                        appBackdrop = appBackdrop,
                    )
                }
                }
            }
        }
    }

    /**
     * Requests a window refresh rate without forcing a display mode or
     * resolution. Android may still lower it for temperature, battery state or
     * hardware limits, which is why Settings describes this as a preference.
     */
    private fun applyPerformanceMode(enabled: Boolean, refreshRate: Int, composeView: View) {
        val supportedRefreshRate = composeView.display.resolvePerformanceRefreshRate(refreshRate)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) supportedRefreshRate.toFloat() else 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(!enabled)
            composeView.requestedFrameRate = if (enabled) {
                supportedRefreshRate.toFloat()
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    /**
     * The other half of the relay. This activity is `singleTask`, so once it is
     * running a second tap on the widget does not rebuild anything — it arrives
     * here, and [onCreate] never runs again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replaces what getIntent() returns, so the extra this consumes is the
        // one that just arrived and not the one the task was started with.
        setIntent(intent)
        PlayerDeepLink.consume(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeatChordApp(
    darkTheme: Boolean,
    /** The width of the window this is laid out in — see the call site. */
    windowWidth: Dp,
    /**
     * The window's height, measured the same way and for the same reason as
     * [windowWidth] — and needed alongside it for exactly one thing: telling
     * a portrait window apart from a landscape one. Width alone can't; a
     * big tablet's portrait width comfortably clears a phone's landscape
     * width, so the two-column player (see [landscapePlayerAvailable])
     * would fire in portrait too if it only ever asked about width.
     */
    windowHeight: Dp,
    appBackdrop: LayerBackdrop,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    // Recording the backdrop layer costs a draw pass, so it only runs when a
    // liquid-glass surface (the nav bar or artwork-page back button) can sample it.
    val glassActive = LocalLiquidGlassEnabled.current && isGlassSupported()
    // "Reduce dynamic blur" keeps the glass bar's *shape* — the folding
    // now-playing-and-tabs component is a layout, not an effect, and dropping
    // back to the two stacked bars would be answering a question about material
    // with a different screen. What it drops is the sampling: the surfaces fill
    // solid (see [Modifier.liquidGlass]) and the whole-page layer recording
    // below goes with them, which is the part that costs a draw pass.
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassSamplesBackdrop = glassActive && !reduceDynamicBlur
    // What folds [GlassNavBar] between its expanded and inline shapes. Held here
    // rather than inside the bar because the page's scroll is what drives it,
    // and the page is a sibling of the bar rather than a child.
    val navBarScroll = rememberFloatingTabBarScrollConnection()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    /**
     * Whether the player's sheet is up. The player is always a full-screen
     * take-over raised over the page, on every window size — the library
     * stays full-screen behind a mini player rather than losing a lane to a
     * permanent pane.
     */
    var showNowPlaying by remember { mutableStateOf(false) }
    // Where the two ends of opening and closing the player meet: the mini
    // player's cover reports itself here, the sheet its offset, and the player
    // flies its artwork between them — see [PlayerDock].
    val playerDock = remember { PlayerDock() }
    // Who moves the player sheet when the app does rather than M3: a tap's
    // open, the pull up from the mini player, and every close — see
    // [PlayerSheetMotion].
    val playerSheetScope = rememberCoroutineScope()
    val playerSheetMotion = remember {
        PlayerSheetMotion(
            scope = playerSheetScope,
            dock = playerDock,
            shown = { showNowPlaying },
            setShown = { showNowPlaying = it },
        )
    }
    playerSheetMotion.windowInfo = LocalWindowInfo.current
    // "Reduce animation" keeps the player as it always was: M3's own slide up
    // and down, its own scrim and window animations, no pull from the mini
    // player and no artwork flying between the two. Everything below that
    // takes part in the docking asks for [activeDock] / this, never for the
    // dock or the motion directly.
    val reducePlayerMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val activeDock = playerDock.takeIf { !reducePlayerMotion }
    playerSheetMotion.flingVelocity = with(LocalDensity.current) { PLAYER_PULL_FLING_VELOCITY.toPx() }
    // How the app takes the player down, whatever for: a back press, or a page
    // opened from inside it. Set straight to false, the sheet left in one frame
    // on its window's own exit animation — the old fast drop, with a copy of the
    // artwork slipping down out of the mini player's cover after it.
    val dismissPlayer: () -> Unit = {
        if (reducePlayerMotion) showNowPlaying = false else playerSheetMotion.close()
    }
    // The far end of the relay from a widget's artwork. Cleared here rather than
    // where it was set, so the request is spent by being served — see
    // [PlayerDeepLink.handled]. The sheet itself is gated on there being a track,
    // so on a cold launch this simply arms it and it opens as the controller
    // connects.
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            showNowPlaying = true
            PlayerDeepLink.handled()
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Replay: the page, the stories over it, and the share sheet over those.
    // Three states rather than one enum because they stack — the stories are
    // opened from the page and the share sheet from either, and closing one
    // has to reveal what it was opened from.
    var showReplay by rememberSaveable { mutableStateOf(false) }
    // Library cards use the same Replay page as every other entry point, but
    // category cards ask it to start at their matching ranked section.
    var replayLandingPage by rememberSaveable { mutableStateOf(ReplayStoryPage.INTRO) }
    var replayStory by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by rememberSaveable { mutableStateOf(false) }
    /** Which story card the share sheet is for, or null for the whole Replay. */
    var replaySharePage by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    // Track which sub-screen was opened from Settings so AnimatedContent keeps
    // SettingsSheet mounted underneath and back navigation restores scroll.
    // null = no sub-screen overlay; non-null = that key is rendered as overlay.
    var settingsSubScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var showEqualizer by rememberSaveable { mutableStateOf(false) }
    // A Library shelf's "Show all" — the shelf it was opened from, so its own
    // cards can be laid out again as a full-screen grid. See [LibraryGridPage].
    var libraryShowAll by remember { mutableStateOf<HomeShelf?>(null) }
    /** The section of the open page shown as its own list, by title. */
    var detailActiveShelf by remember { mutableStateOf<String?>(null) }
    var librarySortMenuOpen by remember { mutableStateOf(false) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showAppLanguage by remember { mutableStateOf(false) }
    var showTranslationLanguage by remember { mutableStateOf(false) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var showLyricsOffset by remember { mutableStateOf(false) }
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by remember { mutableStateOf(false) }
    /**
     * The row or card that was held to open the track menu, or null when it
     * was opened any other way. Non-null lifts that item into
     * [HeldContextMenu]; null keeps the bottom sheet — which is what the ⋮ on
     * the very same row still opens.
     */
    var songMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    /** Holding a row anywhere but the player — the menu without the player's rows. */
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songMenuOrigin = LongPressOrigin.consume()
        songActions = song
    }
    // Which track the playlist picker is adding, or null when it's closed.
    // Separate from [songActions] so the menu can close behind it — the picker
    // is the next step, not a second sheet stacked on the first.
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    // The picker opened from the Library tab, where there is no track and
    // creating the playlist is the whole errand.
    var creatingPlaylist by remember { mutableStateOf(false) }
    var deletingSong by remember { mutableStateOf<Song?>(null) }
    var movingSong by remember { mutableStateOf<Song?>(null) }
    var editingSongTags by remember { mutableStateOf<Song?>(null) }
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    var browseActions by remember { mutableStateOf<BrowseTarget?>(null) }
    /** The card held to open [browseActions] — see [songMenuOrigin]. */
    var browseMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    /** Rename asked for from the popup, which hands it on to the sheet's form. */
    var browseRenameInSheet by remember { mutableStateOf(false) }
    /** The playlist being rearranged, or null when the reorder sheet is shut. */
    var reorderTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    /** Its entries as the store has them now — read fresh when the sheet opens. */
    var reorderEntries by remember { mutableStateOf<UiState<List<Song>>>(UiState.Loading) }
    var reorderSaving by remember { mutableStateOf(false) }
    /** Holding an album or playlist: the popup when it was a hold, else the sheet. */
    val openBrowseMenu: (BrowseTarget) -> Unit = { target ->
        browseMenuOrigin = LongPressOrigin.consume()
        browseRenameInSheet = false
        browseActions = target
    }
    // Set each time the search tab is tapped, which SearchScreen uses as a
    // signal to focus the input field.
    var searchFocusRequested by remember { mutableStateOf(false) }
    val searchFieldFocus = remember { FocusRequester() }

    // The modal player owns light status glyphs and its own contrast scrim.
    // Every other surface follows the theme; Replay's page and stories remain
    // dark artwork either way.
    SystemBarIcons(dark = !darkTheme && !showNowPlaying && !showReplay && replayStory == null)

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val homeLoadingMore by viewModel.homeLoadingMore.collectAsStateWithLifecycle()
    val homeRecentlyPlayedLoading by viewModel.homeRecentlyPlayedLoading.collectAsStateWithLifecycle()

    val query by viewModel.query.collectAsStateWithLifecycle()
    val libraryState by viewModel.library.collectAsStateWithLifecycle()
    val libraryResults by viewModel.libraryResults.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val lyricsProviderStates by viewModel.lyricsProviderStates.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchScrollReset by viewModel.searchScrollReset.collectAsStateWithLifecycle()
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val detail = detailStack.lastOrNull()
    // Local Music has no artwork to wash the top inset in, so it renders with
    // the ordinary bounded status bar rather than the artwork gradient used by
    // album/artist/playlist pages. The tab row it carries sits directly under
    // the bar, so it needs that same plain treatment rather than a
    // release-style colour wash over its tabs.
    val isLocalDetail = detail?.browseId.isDeviceFolder()
    // Not keyed on the browse id and not remembered here: each page's choice
    // lives in AppSettings keyed by that page — Spotify-style, one playlist's
    // order never imposes itself on another, and every page keeps its own
    // across visits.
    val detailSongSorts by AppSettings.detailSongSorts.collectAsStateWithLifecycle()
    val songSort = detail?.browseId?.let { detailSongSorts[it] } ?: SongSort.DEFAULT
    var songSortMenuOpen by remember { mutableStateOf(false) }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()

    // Settings has no tab of its own — it sits on top of whatever tab was
    // selected. A pushed album/artist page (from the player, search, etc.)
    // should surface above it rather than being hidden behind it.
    LaunchedEffect(detail) { if (detail != null) showSettings = false }
    LaunchedEffect(detail?.browseId) { detailActiveShelf = null }

    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    // Playlists live in the app's own store: nothing about them needs an account.
    val localPlaylists by com.music.beatchord.data.spotify.LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    // A local playlist's page and the folder listing are snapshots taken when
    // they were opened. Deleting a track, or importing a playlist, changes what
    // belongs on them — so they are taken again whenever the local store or the
    // folder's own settings change.
    LaunchedEffect(detail?.browseId, localMusicFolderUri, filterNonMusicAudio) {
        val openPage = detail ?: return@LaunchedEffect
        val open = openPage.browseId
        if (openPage.songs !is UiState.Loading &&
            (open == "local:all" || com.music.beatchord.data.spotify.LocalPlaylistStore.getPlaylist(open) != null)
        ) {
            viewModel.reloadLocalDetail(open)
        }
    }
    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)
    val playPauseBusy = player.isLoading
    var queueNotice by remember { mutableStateOf<QueueActionNotice?>(null) }
    var queueNoticeId by remember { mutableIntStateOf(0) }
    val showQueueNotice: (String) -> Unit = { message ->
        queueNoticeId += 1
        queueNotice = QueueActionNotice(queueNoticeId, message)
    }
    LaunchedEffect(queueNotice?.id) {
        val shown = queueNotice ?: return@LaunchedEffect
        delay(3_000)
        if (queueNotice?.id == shown.id) queueNotice = null
    }
    /**
     * The one play/pause every surface presses.
     */
    val togglePlayPause: () -> Unit = {
        controller?.let { c ->
            if (c.isPlaying) {
                c.pause()
            } else {
                c.play()
            }
        }
    }
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()

    // Lyrics follow whatever is playing; duration lands a beat after the track.
    // Keyed on the lyric settings too, so turning a source on or off applies to
    // the track already playing rather than only the next one.
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    LaunchedEffect(player.song?.videoId, player.durationMs, syncedLyricsEnabled, lyricsSources) {
        player.song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                // The player's own length, and the file's tag where it has
                // none yet. Paused, ExoPlayer never finishes preparing the
                // track it was skipped to, so it reports no duration at all —
                // and a lookup that waits for one waits for ever, which left
                // the lyrics of a paused track loading until it was played.
                player.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMillis(),
                it.albumName,
                it.localUri,
                it.localPath,
            )
        }
    }

    val homeListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val currentListState = when (selectedTab) {
        TAB_HOME -> homeListState
        TAB_EXPLORE -> exploreListState
        TAB_LIBRARY -> libraryListState
        else -> searchListState
    }

    // Pull-to-refresh: the drag lives with the feed, but the indicator is the
    // line under the top bar, so the state has to be visible to both.
    val homePull = rememberPullToRefreshState()
    val libraryPull = rememberPullToRefreshState()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currentFeed = when {
        showSettings || detail != null -> null
        selectedTab == TAB_HOME -> MainViewModel.Feed.HOME
        selectedTab == TAB_LIBRARY -> MainViewModel.Feed.LIBRARY
        else -> null
    }
    // The lead shelf is listening history, so opening Home after playing
    // something is exactly when it needs re-fetching.
    LaunchedEffect(currentFeed) {
        if (currentFeed == MainViewModel.Feed.HOME) viewModel.onHomeShown()
        // Likewise for Library: a playlist created or a song liked since it
        // was last fetched is a change to exactly this page.
        if (currentFeed == MainViewModel.Feed.LIBRARY) viewModel.onLibraryShown()
    }

    val currentPull = when (currentFeed) {
        MainViewModel.Feed.HOME -> homePull
        MainViewModel.Feed.LIBRARY -> libraryPull
        else -> null
    }
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    // A pushed album/artist/playlist page has a large header of its own — the
    // sleeve, or an artist's photo running edge to edge — which owns the title
    // until it is scrolled away, exactly as a tab's big heading does. The state
    // is hoisted because the bar lives beside that page rather than inside it,
    // and is rebuilt per page: pushing a second one must not inherit the
    // first's scroll offset.
    // As [detailListState], for Replay: its own large heading owns the title
    // until it is scrolled away, and the bar lives out here rather than on the
    // page. Rebuilt per opening so reopening starts at the top.
    val replayListState = remember(showReplay, replayLandingPage) { LazyListState() }
    val replayScrolled by remember(replayListState) {
        derivedStateOf {
            replayListState.firstVisibleItemIndex > 0 ||
                replayListState.firstVisibleItemScrollOffset > 24
        }
    }

    // AnimatedContent keeps the outgoing page composed during its fade. A
    // single state remembered from only the *current* detail id is therefore
    // handed to both the outgoing and incoming LazyColumns for that interval.
    // Compose lazy state is one-layout state: sharing it between those lists
    // can leave the incoming album attached to the disappearing artist/grid
    // layout and unable to consume scroll gestures. Keep one state per page
    // while it is on the navigation stack instead.
    val detailListStates = remember { mutableMapOf<String, LazyListState>() }
    val detailListState = detail?.browseId?.let { browseId ->
        detailListStates.getOrPut(browseId) { LazyListState() }
    } ?: remember { LazyListState() }
    LaunchedEffect(detailStack.map { it.browseId }) {
        detailListStates.keys.retainAll(detailStack.mapTo(HashSet()) { it.browseId })
    }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    // Held, not rebuilt. `listOf` hands back a new instance on every pass, and a
    // List is not a type the compiler can call stable, so under strong skipping
    // the bar this is handed to compares it by identity, never matches, and so
    // can never skip. This composable re-runs on every frame of a scroll — it
    // reads [scrolled] — which made the whole floating bar, both of its states
    // and every glass surface on them recompose once per frame for the length of
    // a fold. Keyed on the labels so a locale change still rebuilds it.
    val homeLabel = stringResource(R.string.home)
    val playLabel = stringResource(R.string.play)
    val playlistsLabel = stringResource(R.string.playlists)
    val libraryLabel = stringResource(R.string.library)
    val searchLabel = stringResource(R.string.search)
    val replayLabel = stringResource(R.string.replay)
    val queueLabel = stringResource(R.string.queue)
    val tabs = remember(homeLabel, playlistsLabel, libraryLabel, searchLabel) {
        listOf(
            BottomTab(homeLabel, BeatChordIcons.Home),
            BottomTab(playlistsLabel, BeatChordIcons.Queue),
            BottomTab(libraryLabel, BeatChordIcons.TabLibrary),
            BottomTab(searchLabel, BeatChordIcons.TabSearch),
        )
    }

    val scope = rememberCoroutineScope()

    val playFrom: (List<Song>, Int, QueueSource) -> Unit = playFrom@{ allSongs, allIndex, source ->
        val songs = allSongs
        val index = allIndex
        scope.launch {
            val c = controller ?: return@launch
            val currentTimeline = player.queue.takeIf { it.size == c.mediaItemCount }
                ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
            val currentIndex = c.currentMediaItemIndex

            val result = QueueCoordinator.buildContextQueue(
                currentTimeline = currentTimeline,
                currentIndex = currentIndex,
                newContextSongs = songs,
                selectedIndex = index,
                contextSource = source,
            )
            c.playSongs(result.timeline, result.startIndex)
            // Start playback in the mini-player; the user opens the full view by tapping it.
        }
    }
    // Kept for entry points whose rows already carry their origin (notably a
    // collection action fetched before this callback). The explicit wrappers
    // below are preferred because a track's album is not necessarily where it
    // was played from.
    val play: (List<Song>, Int) -> Unit = { songs, index ->
        val first = songs.getOrNull(index)
        val source = QueueSource(
            title = first?.playbackSource ?: first?.albumName ?: queueLabel,
            type = first?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            id = first?.playbackSourceId,
        )
        playFrom(songs, index, source)
    }
    val addToQueue: (Song) -> Unit = { song ->
        scope.launch {
            // The end of what the user queued, not the end of the queue: a song
            // asked for by name outranks whatever else was appended behind it.
            controller?.let {
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = false,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_added_to_queue))
            }
        }
    }
    val playNext: (Song) -> Unit = { song ->
        scope.launch {
            controller?.let {
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = true,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_will_play_next))
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    /**
     * A whole album, playlist or library list onto the queue in one go.
     *
     * [next] picks which of the two positions the single-track menu already
     * offers it lands in — straight after the current track, or behind
     * everything else the user queued (see [addToQueue]). The list keeps its
     * own running order either way: this
     * *adds* a release, it doesn't start one, so [QueueShuffle] has no say here.
     *
     * There is usually nothing on screen to show for it — the queue panel is
     * shut, the current track carries on — so the count is said out loud.
     */
    val queueSongs: (List<Song>, Boolean) -> Unit = { songs, next ->
        if (songs.isNotEmpty()) {
            scope.launch {
                    val c = controller
                if (c == null || c.mediaItemCount == 0) {
                    // Nothing to queue behind. "Add to queue" on a silent
                    // player can only mean start here — and adding without
                    // preparing would leave the list sitting in a player that
                    // never gets round to it.
                    play(songs, 0)
                } else {
                    val timeline = player.queue.takeIf { q -> q.size == c.mediaItemCount }
                        ?: (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
                    val at = QueueCoordinator.findUserQueueInsertionIndex(
                        timeline = timeline,
                        currentIndex = c.currentMediaItemIndex,
                        isNext = next,
                    )
                    val current = c.currentMediaItem?.toSong()
                    c.addMediaItems(
                        at,
                        songs.map {
                            it.copy(
                                radioName = current?.radioName,
                                playbackSource = current?.playbackSource ?: queueLabel,
                                playbackSourceType = current?.playbackSourceType
                                    ?: PlaybackSourceType.QUEUE,
                                playbackSourceId = current?.playbackSourceId,
                            ).asQueueEntry(QueueTier.USER_QUEUE).toMediaItem()
                        },
                    )
                    val message = context.resources.getQuantityString(
                        if (next) R.plurals.songs_will_play_next else R.plurals.songs_added_to_queue,
                        songs.size,
                        songs.size,
                    )
                    showQueueNotice(message)
                }
            }
        }
    }
    val addSongsToQueue: (List<Song>) -> Unit = { songs -> queueSongs(songs, false) }
    val playSongsNext: (List<Song>) -> Unit = { songs -> queueSongs(songs, true) }

    // ---- Album / playlist menu ----

    /**
     * Holding an album or playlist card, wherever one is drawn.
     *
     * Artists are left out. An artist page is a selection of their work rather
     * than a running order, and "add Radiohead to the queue" has no answer that
     * isn't a guess — so holding an artist card does nothing, as it did before.
     */
    val onBrowseLongPress: (ShelfItem) -> Unit = { item ->
        val id = item.browseId
        val type = id?.let { viewModel.browseTypeOf(it) }
        if (id != null && type != BrowseType.ARTIST) {
            openBrowseMenu(
                BrowseTarget(
                    browseId = id,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = type ?: BrowseType.OTHER,
                ),
            )
        }
    }

    /**
     * The track a song card stands for, or null if the card is a collection.
     *
     * The card's own subtitle is billed as "Song • Chelsea Wolfe"; only the
     * credit belongs in the field the player, mini player and everything
     * downstream read.
     */
    val shelfSong: (ShelfItem) -> Song? = { item ->
        item.videoId?.let { videoId ->
            val isLocal = videoId.startsWith("content://") || videoId.startsWith("file://") || videoId.startsWith("/")
            Song(
                videoId = videoId,
                title = item.title,
                artist = artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
                localUri = if (isLocal) videoId else null,
            )
        }
    }

    /**
     * Holding a card on a feed whose shelves mix tracks with collections —
     * Quick picks and Recently played are songs, Listen again is either.
     *
     * [onBrowseLongPress] alone answered only half of them: a track card
     * carries a videoId and no browse id, so holding one fell through its
     * null check and nothing opened. Dispatched on the same test as the tap
     * below, so a card that plays a song offers the track menu and a card that
     * opens a page offers the album / playlist one.
     */
    val onShelfLongPress: (ShelfItem) -> Unit = { item ->
        val song = shelfSong(item)
        if (song != null) openSongMenu(song) else onBrowseLongPress(item)
    }

    /**
     * Hands [action] the target's whole track list.
     *
     * A release page's overflow passes the rows it is already showing, and this
     * is immediate. A card has no tracks behind it — its page was never opened —
     * so the list is read back out of the app's own playlist store instead,
     * which is the only place a local running order lives.
     *
     * Album rows arrive with no album name of their own, the same way they do on
     * the page (see `withAlbum` below), so the title is stamped on here too —
     * otherwise a track queued from an album card reaches the player with
     * nothing to file it under.
     */
    val withBrowseSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit = { target, action ->
        val stamp: (List<Song>) -> Unit = { songs ->
            action(
                songs.map { song ->
                    song.copy(
                        albumName = if (target.type == BrowseType.ALBUM) {
                            song.albumName ?: target.title
                        } else {
                            song.albumName
                        },
                        playbackSource = target.title,
                        playbackSourceType = PlaybackSourceType.BROWSE,
                        playbackSourceId = target.browseId,
                    )
                },
            )
        }
        val fromStore = target.browseId
            ?.let { com.music.beatchord.data.spotify.LocalPlaylistStore.getPlaylist(it) }
            ?.songs
            .orEmpty()
        when {
            target.songs.isNotEmpty() -> stamp(target.songs)
            fromStore.isNotEmpty() -> stamp(fromStore)
            else ->
                Toast.makeText(context, context.getString(R.string.no_tracks_here), Toast.LENGTH_SHORT).show()
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
            viewModel.loadLibrarySongs()
        } else {
            Toast.makeText(context, context.getString(R.string.storage_required_read), Toast.LENGTH_SHORT).show()
        }
    }
    var pendingExportPlaylist by remember { mutableStateOf<com.music.beatchord.data.spotify.LocalPlaylist?>(null) }
    var pendingExportFormat by remember { mutableStateOf("m3u8") }
    val playlistExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destinationUri ->
        if (destinationUri == null) return@rememberLauncherForActivityResult
        val pl = pendingExportPlaylist ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
                    if (pendingExportFormat == "mpl") {
                        val bytes = com.music.beatchord.data.musicolet.MusicoletManager.writeMpl(pl.title, pl.songs, encrypt = false)
                        out.write(bytes)
                    } else {
                        val text = com.music.beatchord.data.musicolet.MusicoletManager.writeM3u8(pl.title, pl.songs)
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Playlist exported successfully", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    // Two local formats, picked apart by extension: M3U is what every other
    // player writes, and a Musicolet backup is what this app could already
    // read. Neither leaves the device.
    val playlistImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val name = displayNameOf(context, uri)
            val isM3u = name?.let { it.endsWith(".m3u", true) || it.endsWith(".m3u8", true) } == true
            val result = runCatching {
                if (isM3u) {
                    val entries = M3uImport.parse(M3uImport.read(context, uri))
                    val songs = M3uImport.match(entries, LocalMediaRepository.getLocalMusic(context))
                    if (songs.isNotEmpty()) {
                        LocalPlaylistStore.savePlaylist(M3uImport.titleOf(name), songs)
                    }
                    songs.size
                } else {
                    com.music.beatchord.data.musicolet.MusicoletManager.importIntoBeatChord(context, uri)
                        .totalPlaysImported
                }
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { count ->
                        viewModel.loadLibrary()
                        val message = if (count > 0) {
                            context.getString(R.string.playlist_imported, M3uImport.titleOf(name))
                        } else {
                            context.getString(R.string.playlist_import_empty)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    },
                    onFailure = {
                        val reason = it.message ?: context.getString(R.string.something_went_wrong)
                        Toast.makeText(
                            context,
                            context.getString(R.string.playlist_import_failed, reason),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                )
            }
        }
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    // Shared by the Library tab itself and by a shelf's "Show all" page, so a
    // card opens the same way from either.
    val onLibraryItemClick: (ShelfItem) -> Unit = { item ->
        item.browseId?.let { id ->
            if (id == "local:all" && !LocalMediaRepository.hasStoragePermission(context)) {
                mediaPermissionLauncher.launch(mediaPermission)
            }
            // Left set rather than cleared: a card opened from a shelf's
            // "Show all" page stacks a detail page over it exactly as one
            // opened from the Library tab stacks over that, so back from the
            // release lands on the grid rather than skipping past it. Every
            // place that reads `libraryShowAll` alongside `detail` favours
            // `detail` while both are set — see the AnimatedContent below.
            viewModel.openDetail(
                browseId = id,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
    }
    // Content padding leaves room for the frosted bar above and the tab bar
    // (plus mini player) below, so nothing is ever trapped under the glass.
    //
    // The top is measured off the bar rather than guessed at: the bar is pinned
    // under the status bar inset, and that inset varies by device and by window,
    // so a fixed number only ever lines up on the one device it was picked on.
    // See [topBarContentPadding].
    val listPadding = PaddingValues(
        top = topBarContentPadding(),
        bottom = if (player.song != null) 210.dp else 140.dp,
    )

    // What colour the page currently under the bars is. The fades either end
    // of the screen are flat colour wherever their blur has least to say, so
    // handing them the theme's background puts a black band on a page that is
    // washed in an artwork's colour instead. Off a detail page this resolves
    // to the theme's background anyway, which is exactly right there.
    //
    val detailPalette = rememberArtworkPalette(imageUrl = detail?.thumbnailUrl)

    // One set of numbers for the cards, the page, the stories and the shared
    // picture, so they cannot disagree. Read while any of them is on screen —
    // which includes the Library tab, since the cards live at the top of it.
    // See [rememberReplayState].
    val replayOpen = showReplay || replayStory != null || showReplayShare ||
        (selectedTab == TAB_LIBRARY && detail == null && !showSettings)
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)
    val replayCards = remember(replay.summary) {
        replay.summary?.takeUnless { it.isEmpty }?.cards(context).orEmpty()
    }

    // ---- The track in the player ----
    // A local file carries its own title, artist and album tags, so there is
    // nothing to look up and fill in: what the queue was handed is everything
    // the player below draws with.
    val playerSong = player.song

    // The player's whole parameter list, kept apart from the sheet that
    // mounts it so the sheet's own setup reads on its own.
    val nowPlaying: @Composable (Song) -> Unit = { song ->
        NowPlayingScreen(
            song = song,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            isPlaying = player.isPlaying,
            isLoading = playPauseBusy,
            position = player.position,
            durationMs = player.durationMs,
            onPlayPause = {
                togglePlayPause()
            },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPrevious() },
            onSeekFraction = { fraction ->
                controller?.let { player ->
                    // Read at the moment of the seek, not from the
                    // polled snapshot the screen draws with: a track
                    // change updates the current item before it updates
                    // the duration, so a fraction dropped seconds after
                    // a transition would otherwise be scaled by the
                    // previous song's length.
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo(
                            (fraction * duration).toLong()
                                .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                        )
                    }
                }
            },
            onSeek = { target ->
                controller?.let { player ->
                    // Clamped here rather than at each caller because
                    // not every caller can clamp. The scrubber's target
                    // is a fraction of the duration and cannot overrun,
                    // but a tapped lyric line seeks to a timestamp from
                    // whichever transcription matched on title, artist
                    // and duration — and a match against a slightly
                    // longer master puts every line late, so a tap near
                    // the end asks for a position past the end of this
                    // stream. Media3 answers that by clamping to the
                    // final millisecond, which ends the track and starts
                    // the next one: tapping the last line of a song
                    // skipped it.
                    val duration = player.duration
                    player.seekTo(
                        if (duration > 0) {
                            target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                        } else {
                            target.coerceAtLeast(0L)
                        },
                    )
                }
            },
            queue = player.queue,
            queueIndex = player.queueIndex,
            hasPrevious = player.hasPrevious,
            hasNext = player.hasNext,
            repeatMode = player.repeatMode,
            shuffleEnabled = shuffleEnabled,
            likeStatus = if (com.music.beatchord.data.spotify.LocalPlaylistStore.isFavorite(song)) LikeStatus.LIKE else LikeStatus.INDIFFERENT,
            onToggleLike = { com.music.beatchord.data.spotify.LocalPlaylistStore.toggleFavorite(song) },
            onAddToPlaylist = { playlistTarget = song },
            // The service owns both the queue and the Shuffle state. Keeping
            // the toggle on that side prevents the UI from changing the icon
            // before its asynchronous reorder command has actually landed.
            onToggleShuffle = { controller?.toggleShuffle() },
            onCycleRepeat = {
                controller?.let {
                    val next = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    // Persist the new repeat mode so it survives app restarts.
                    AppSettings.setRepeatMode(next)
                    // Nothing else to do here: PlaybackService watches the
                    // repeat mode itself and holds the queue to it — and,
                    // unlike this screen, is still around when the loop
                    // ends.
                    it.repeatMode = next
                }
            },
            onJumpTo = { index ->
                controller?.let { c ->
                    QueueCoordinator.jumpToQueueItem(c, index, player.queue)
                }
            },
            onRemoveFromQueue = { controller?.removeMediaItem(it) },
            onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
            onOpenMenu = {
                menuFromPlayer = true
                songMenuOrigin = null
                songActions = song
            },
            onOpenAlbum = { id ->
                dismissPlayer()
                viewModel.openDetail(
                    id,
                    song.albumName ?: song.title,
                    song.artist,
                    song.thumbnailUrl,
                    BrowseType.ALBUM,
                )
            },
            // A credit with a local artist id opens that artist's page straight
            // away; a credit with none opens nothing, which beats guessing at a
            // page that belongs to a different act of the same name.
            //
            // No artwork: this track's cover isn't the artist's
            // picture, and the page fills its own in once loaded.
            onOpenArtist = { id, name ->
                dismissPlayer()
                if (id != null) {
                    viewModel.openDetail(
                        id,
                        name,
                        context.getString(R.string.artist),
                        null,
                        BrowseType.ARTIST,
                    )
                }
            },
            onOpenPlaybackSource = openSource@{
                val sourceType = song.playbackSourceType ?: PlaybackSourceType.QUEUE
                val sourceTitle = song.playbackSource
                    ?: song.albumName
                    ?: queueLabel
                val sourceId = song.playbackSourceId

                // A context link is navigation, not another page stacked over
                // Now Playing. Clear the current route before restoring it.
                dismissPlayer()
                viewModel.clearDetail()
                showSettings = false
                showEqualizer = false
                showReplay = false
                settingsSubScreen = null
                libraryShowAll = null

                when (sourceType) {
                    PlaybackSourceType.BROWSE -> {
                        val id = sourceId ?: return@openSource
                        viewModel.openDetail(id, sourceTitle)
                    }
                    PlaybackSourceType.HOME -> selectedTab = TAB_HOME
                    PlaybackSourceType.SEARCH -> selectedTab = TAB_SEARCH
                    PlaybackSourceType.REPLAY -> {
                        replayLandingPage = ReplayStoryPage.INTRO
                        showReplay = true
                    }
                    // HISTORY, EXPLORE, SHARED_LINK and QUEUE no longer name a
                    // page this app can reach — the first three were YouTube's,
                    // and the queue is handled inside NowPlayingScreen by
                    // opening it.
                    else -> Unit
                }
            },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = viewModel::selectLyricsProvider,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsOffsetOpen = showLyricsOffset,
            onDismissLyricsOffset = { showLyricsOffset = false },
            onClearQueue = {
                // Keep what's playing and the surrounding context; drop user-queued tracks.
                controller?.let { c ->
                    QueueCoordinator.clearUserQueue(c)
                }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // A pushed album/artist/playlist page replaces the tab content but
        // leaves the tab bar and mini player in place.
        // Replay's three layers unwind in the order they were opened. Ahead of
        // every other handler because they are drawn over everything else.
        BackHandler(enabled = showReplayShare) { showReplayShare = false }
        BackHandler(enabled = replayStory != null && !showReplayShare) { replayStory = null }
        BackHandler(
            enabled = showReplay && replayStory == null && !showReplayShare,
        ) {
            if (settingsSubScreen == "replay") {
                // Dismiss Replay overlay and return to SettingsSheet beneath.
                showReplay = false
                settingsSubScreen = null
            } else {
                showReplay = false
            }
        }
        BackHandler(
            enabled = detail != null && !showSettings && !showEqualizer && !showReplay,
        ) { viewModel.closeDetail() }
        BackHandler(enabled = showEqualizer) {
            showEqualizer = false
            if (settingsSubScreen == "equalizer") settingsSubScreen = null
        }
        // One back step out of Settings, or out of any tab but Home, lands on
        // Home rather than exiting — only Home itself hands back to the system,
        // which is what actually closes/minimizes the app.
        BackHandler(enabled = showSettings && !showEqualizer && !showReplay) {
            showSettings = false
            // Only when Settings was the whole of what was on screen. Opened
            // over Replay or over a release page, closing it reveals that again
            // rather than throwing both away.
            if (detail == null && !showReplay) selectedTab = TAB_HOME
        }
        BackHandler(
            enabled = detail == null && !showSettings &&
                !showEqualizer && !showReplay &&
                selectedTab != TAB_HOME,
        ) {
            selectedTab = TAB_HOME
        }
        // Disabled while a detail page is open over the grid: that one's own
        // BackHandler below has to close first, or back would skip past it
        // straight to Library. See [onLibraryItemClick].
        BackHandler(enabled = libraryShowAll != null && detail == null) { libraryShowAll = null }
        BackHandler(enabled = detailActiveShelf != null) { detailActiveShelf = null }

        // On a tablet the page and the player stand side by side rather than
        // one over the other: everything a phone stacks in a single column —
        // the feed, the frosted bars, the tab row — becomes the left half of
        // a row, and the player is the right. Off a tablet the row has the
        // one child it always had and changes nothing.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = when {
                        // `&& detail == null`: a card opened from the grid
                        // stacks a detail page over it exactly as one opened
                        // from the Library tab does — see
                        // [onLibraryItemClick] — so with both set this must
                        // give way to the `detail != null` branch below it
                        // rather than keep showing the grid underneath.
                        libraryShowAll != null && detail == null -> "library_show_all"
                        // When a sub-screen overlay is active, keep SettingsSheet
                        // mounted so its scroll position survives.  The overlay is
                        // rendered below the AnimatedContent block.
                        settingsSubScreen != null -> "settings"
                        showEqualizer -> "equalizer"
                        // Above Replay, not below it: Settings can be opened
                        // from the Replay overlay itself, and with Replay
                        // winning the tie the way back was live, hit, and
                        // changed nothing on screen.
                        showSettings -> "settings"
                        showReplay -> "replay"
                        detail != null -> detail.browseId
                        else -> "$TAB_KEY$selectedTab"
                    },
                    // Tabs swap outright; everything else crossfades.
                    //
                    // A tab is not a place you travel to — the bar is the whole
                    // navigation and it carries its own movement — so a fade
                    // between two of them only ever reads as a stutter. And it
                    // cannot read as anything else: neither page paints a
                    // background, so a crossfade dissolves both through to the
                    // window and the switch dips through a dimmer frame in the
                    // middle. Pushing a page or raising Settings is a real
                    // change of context and keeps the fade.
                    //
                    // "Show all" swapping with the Library tab underneath it is
                    // the same case as a tab swap, not a pushed page: it's still
                    // that tab, just laid out as a grid instead of a row, sharing
                    // its background rather than painting its own — so this one
                    // pair gets the tab's no-fade swap too, in both directions, or
                    // the dip through a dim frame shows up on every hold of a
                    // card there. A card opened *from* the grid is a real page
                    // and keeps the fade, same as one opened from the row.
                    transitionSpec = {
                        val tabSwap = initialState.startsWith(TAB_KEY) && targetState.startsWith(TAB_KEY)
                        val libraryTabKey = "$TAB_KEY$TAB_LIBRARY"
                        val libraryShowAllSwap = (initialState == "library_show_all" && targetState == libraryTabKey) ||
                            (targetState == "library_show_all" && initialState == libraryTabKey)
                        if (tabSwap || libraryShowAllSwap) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        }
                    },
                    modifier = Modifier
                        .hazeSource(hazeState)
                        .then(
                            if (glassActive) {
                                Modifier
                                    // Not under "reduce dynamic blur": nothing
                                    // samples the layer then, and recording a
                                    // whole page into one for no reader is the
                                    // cost that setting exists to remove.
                                    .then(
                                        if (glassSamplesBackdrop) {
                                            Modifier.layerBackdrop(appBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    // Every page's scroll passes through here, so
                                    // the glass bar collapses on all of them
                                    // without each one having to know about it.
                                    .nestedScroll(navBarScroll)
                            } else {
                                Modifier
                            },
                        ),
                    label = "content",
                ) { key ->
                    // Every branch below reads `key` rather than the state that
                    // produced it. The two are the same thing only for the page
                    // being entered: the one on its way out is still composed,
                    // and asking it what is selected *now* has it redraw itself
                    // as its own replacement — which then fades out from under
                    // the identical copy fading in behind it.
                    val live = detailStack.lastOrNull()?.takeIf {
                        it.browseId == key && key != "settings" &&
                            key != "discord" && key != "replay" &&
                            key != "library_show_all"
                    }
                    // Held for the same reason, one step further on: a popped
                    // page is off the stack before it has finished animating
                    // out, so `live` goes null under it and it would spend its
                    // exit drawing whatever is underneath instead of itself.
                    // Per slot, since each is remembered against its own key.
                    val held = remember(key) { mutableStateOf(live) }
                    if (live != null) held.value = live
                    val page = held.value
                    // This state belongs to this AnimatedContent slot, not to
                    // whichever detail happens to be at the top of the stack
                    // while the slot is fading out.
                    val pageDetailListState = remember(key) {
                        detailListStates.getOrPut(key) { LazyListState() }
                    }
                    if (key == "library_show_all") {
                        libraryShowAll?.let { shelf ->
                            val allLocalSongs = (libraryState as? com.music.beatchord.data.model.UiState.Success)
                                ?.data?.librarySongs.orEmpty()
                            LibraryCollectionScreen(
                                shelf = shelf,
                                localSongs = allLocalSongs,
                                onBack = { libraryShowAll = null },
                                onPlaySong = { songs, index ->
                                    scope.launch { controller?.playSongs(songs, index) }
                                },
                                onShufflePlay = { songs ->
                                    scope.launch { controller?.playSongs(songs.shuffled(), 0) }
                                },
                                onSongLongPress = { song ->
                                    songMenuOrigin = null
                                    songActions = song
                                },
                                contentPadding = listPadding,
                            )
                        }
                    } else if (key == "replay") {
                        ReplayScreen(
                            state = replay,
                            holder = stringResource(R.string.app_name),
                            onPeriodChange = setReplayPeriod,
                            onOpenStory = { replayStory = it },
                            // A track tapped on a chart is one the user already
                            // knows they like, so it starts a queue off itself
                            // rather than the chart it was on.
                            onPlaySong = { song ->
                                playFrom(listOf(song), 0, QueueSource(replayLabel, PlaybackSourceType.REPLAY))
                            },
                            onOpenArtist = { id, name ->
                                showReplay = false
                                if (id != null) {
                                    viewModel.openDetail(
                                        id,
                                        name,
                                        context.getString(R.string.artist),
                                        null,
                                        BrowseType.ARTIST,
                                    )
                                }
                            },
                            onOpenAlbum = { id, title, artist, art ->
                                showReplay = false
                                if (id != null) {
                                    viewModel.openDetail(id, title, artist.orEmpty(), art, BrowseType.ALBUM)
                                }
                            },
                            onShare = {
                                replaySharePage = null
                                showReplayShare = true
                            },
                            contentPadding = listPadding,
                            listState = replayListState,
                            landingPage = replayLandingPage,
                        )
                    } else if (key == "equalizer") {
                        EqualizerScreen(contentPadding = listPadding)
                    } else if (key == "settings") {
                        SettingsScreen(
                            windowWidth = windowWidth,
                            onEqualizer = {
                                settingsSubScreen = "equalizer"
                                showEqualizer = true
                            },
                            onOpenReplay = {
                                settingsSubScreen = "replay"
                                replayLandingPage = ReplayStoryPage.INTRO
                                showReplay = true
                            },
                            onLyricsSources = { showLyricsSources = true },
                            onTranslationLanguage = { showTranslationLanguage = true },
                            onAppLanguage = { showAppLanguage = true },
                            contentPadding = listPadding,
                        )
                    } else if (page != null && page.browseId.isDeviceFolder()) {
                        // Local Music — the tabbed Songs / Artists / Albums view
                        // of the files already on the device. There is nothing
                        // to distinguish it from any other folder, so all it has
                        // to say is what to say when it is empty.
                        val localState = page.songs
                        val localSongs = (localState as? com.music.beatchord.data.model.UiState.Success)
                            ?.data.orEmpty()
                        LocalMusicScreen(
                            songs = localSongs,
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            onSongClick = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            onSongLongPress = openSongMenu,
                            onSongSwipe = onSongSwipe,
                            onShuffle = { songs ->
                                QueueShuffle.enableForNextQueue()
                                playFrom(
                                    songs,
                                    songs.indices.random(),
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            emptyMessage = (localState as? com.music.beatchord.data.model.UiState.Error)
                                ?.message,
                            // An album or artist here is a grouping of rows rather than
                            // a page, so the menu is handed the rows themselves — there
                            // is no id anything could be fetched with.
                            onCollectionLongPress = { label, grouped ->
                                openBrowseMenu(
                                    BrowseTarget(
                                        browseId = null,
                                        title = label,
                                        subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                            .takeUnless { it == label }
                                            .orEmpty(),
                                        thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                        songs = grouped,
                                    ),
                                )
                            },
                            contentPadding = listPadding,
                        )
                    } else if (page != null) {
                        // An album page's rows carry no album name of their own — the
                        // release is billed once, in the header the rows hang under — so
                        // the page title is stamped on as they leave for the track menu.
                        // Without it every track opened from an album reaches the player
                        // with nothing to file it under.
                        val withAlbum: (Song) -> Song = { song ->
                            if (page.type == BrowseType.ALBUM) {
                                song.copy(albumName = song.albumName ?: page.title)
                            } else {
                                song
                            }
                        }
                        DetailScreen(
                            page = page,
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            listState = pageDetailListState,
                            activeShelf = detailActiveShelf,
                            onActiveShelfChange = { detailActiveShelf = it },
                            onSongClick = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            onSongLongPress = { openSongMenu(withAlbum(it)) },
                            onSongSwipe = onSongSwipe,
                            onShuffle = { songs ->
                                // Shuffle goes on first so the queue is built shuffled
                                // as it is set — the random pick here only decides
                                // which track leads it.
                                QueueShuffle.enableForNextQueue()
                                playFrom(
                                    songs,
                                    songs.indices.random(),
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            onSectionItemClick = { item ->
                                item.browseId?.let { id ->
                                    viewModel.openDetail(
                                        browseId = id,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                        type = BrowseType.ALBUM,
                                    )
                                }
                            },
                            onSectionItemLongPress = onBrowseLongPress,
                            onArtistClick = { id, name ->
                                viewModel.openDetail(
                                    id,
                                    name,
                                    context.getString(R.string.artist),
                                    null,
                                    BrowseType.ARTIST,
                                )
                            },
                            songSort = songSort,
                            contentPadding = listPadding,
                        )
                    } else when (key.removePrefix(TAB_KEY).toIntOrNull() ?: selectedTab) {
                        TAB_HOME -> HomeScreen(
                            state = homeState,
                            listState = homeListState,
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            title = stringResource(R.string.listen_now),
                            onItemClick = { item, shelfTitle ->
                                val song = shelfSong(item)
                                // Hoisted because ShelfItem lives in :shared, and
                                // Kotlin will not smart-cast a public nullable
                                // property declared in another module.
                                val browseId = item.browseId
                                when {
                                    song != null -> playFrom(
                                        listOf(song),
                                        0,
                                        QueueSource(shelfTitle, PlaybackSourceType.HOME),
                                    )
                                    browseId != null -> viewModel.openDetail(
                                        browseId = browseId,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                    )
                                }
                            },
                            onItemLongPress = onShelfLongPress,
                            onRetry = viewModel::loadHome,
                            refreshing = MainViewModel.Feed.HOME in refreshing,
                            onRefresh = { viewModel.refresh(MainViewModel.Feed.HOME) },
                            pullState = homePull,
                            contentPadding = listPadding,
                            onLoadMore = viewModel::loadMoreHome,
                            loadingMore = homeLoadingMore,
                            recentlyPlayedLoading = homeRecentlyPlayedLoading,
                        )
                        TAB_EXPLORE -> PlaylistsTabScreen(
                            playlists = localPlaylists,
                            onPlaylistClick = { playlist ->
                                viewModel.openDetail(
                                    browseId = playlist.id,
                                    title = playlist.title,
                                    subtitle = "${playlist.songs.size} tracks",
                                    thumbnailUrl = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                                )
                            },
                            onPlaylistLongPress = { playlist ->
                                openBrowseMenu(
                                    BrowseTarget(
                                        browseId = playlist.id,
                                        title = playlist.title,
                                        subtitle = "${playlist.songs.size} tracks",
                                        thumbnailUrl = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                                        type = BrowseType.PLAYLIST,
                                    )
                                )
                            },
                            onNewPlaylist = { creatingPlaylist = true },
                            onImportPlaylist = { playlistImportLauncher.launch(arrayOf("*/*")) },
                            contentPadding = listPadding,
                        )
                        TAB_SEARCH -> SearchScreen(
                            query = query,
                            onQueryChange = viewModel::onQueryChange,
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            listState = searchListState,
                            scrollResetTrigger = searchScrollReset,
                            focusRequested = searchFocusRequested,
                            onFocusHandled = { searchFocusRequested = false },
                            history = searchHistory,
                            onSongLongPress = openSongMenu,
                            onSongSwipe = onSongSwipe,
                            // Tapping a remembered search asks it again rather than
                            // navigating: the record is local, and the only thing it
                            // can name now is a name on this device.
                            onHistoryClick = { entity -> viewModel.searchFor(entity.title) },
                            onHistoryRemove = viewModel::removeSearch,
                            onHistoryClear = viewModel::clearSearchHistory,
                            contentPadding = listPadding,
                            topPadding = topBarContentPadding(),
                            // The field is in the top bar; see the bar's accessory.
                            showField = false,
                            libraryResults = libraryResults,
                            // The same queue opening the Local Music folder and
                            // tapping the row there would give.
                            onLibrarySongClick = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(
                                        context.getString(R.string.local_music),
                                        PlaybackSourceType.BROWSE,
                                        "local:all",
                                    ),
                                )
                            },
                        )
                        else -> LibraryScreen(
                            state = libraryState,
                            listState = libraryListState,
                            onShelfItemClick = onLibraryItemClick,
                            // Every shelf here has a menu behind it: holding a card
                            // opens the local playlist one. Holding an artist still
                            // does nothing; see [onBrowseLongPress].
                            onShelfItemLongPress = onBrowseLongPress,
                            onNewPlaylist = { creatingPlaylist = true },
                            onImportPlaylist = { playlistImportLauncher.launch(arrayOf("*/*")) },
                            onShowAll = { shelf -> libraryShowAll = shelf },
                            replay = {
                                LibraryReplayEntry(
                                    cards = replayCards,
                                    loading = replay.loading,
                                    holder = stringResource(R.string.app_name),
                                    memberSince = replay.memberSince,
                                    onOpenReplay = { page ->
                                        replayLandingPage = page
                                        showReplay = true
                                    },
                                )
                            },
                            onRetry = viewModel::loadLibrary,
                            refreshing = MainViewModel.Feed.LIBRARY in refreshing,
                            onRefresh = { viewModel.refresh(MainViewModel.Feed.LIBRARY) },
                            pullState = libraryPull,
                            contentPadding = listPadding,
                        )
                    }
                }

                // ---- Settings sub-screen overlays ----
                // When a sub-screen is opened from Settings, AnimatedContent keeps
                // "settings" as target so SettingsSheet stays mounted with its scroll.
                // Each overlay is wrapped in an opaque Surface so it fully obscures
                // the preserved SettingsSheet beneath — without it, both screens bleed
                // through each other and text becomes unreadable.
                //
                // Composed ahead of the top bar, inside the page's own Box: out at
                // the root they were drawn over the bar and swallowed its taps, so
                // these pages had no status-bar scrim, title or back button.
                when (settingsSubScreen) {
                    "equalizer" -> {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            EqualizerScreen(contentPadding = listPadding)
                        }
                    }
                    "replay" -> {
                        if (showReplay && !showReplayShare) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background,
                            ) {
                                ReplayScreen(
                                    state = replay,
                                    holder = stringResource(R.string.app_name),
                                    onPeriodChange = setReplayPeriod,
                                    onOpenStory = { replayStory = it },
                                    onPlaySong = { song ->
                                        playFrom(
                                            listOf(song),
                                            0,
                                            QueueSource(replayLabel, PlaybackSourceType.REPLAY),
                                        )
                                    },
                                    onOpenArtist = { id, name ->
                                        settingsSubScreen = null
                                        showReplay = false
                                        if (id != null) {
                                            viewModel.openDetail(
                                                id,
                                                name,
                                                context.getString(R.string.artist),
                                                null,
                                                BrowseType.ARTIST,
                                            )
                                        }
                                    },
                                    onOpenAlbum = { id, title, artist, art ->
                                        settingsSubScreen = null
                                        showReplay = false
                                        if (id != null) {
                                            viewModel.openDetail(id, title, artist.orEmpty(), art, BrowseType.ALBUM)
                                        }
                                    },
                                    onShare = {
                                        replaySharePage = null
                                        showReplayShare = true
                                    },
                                    contentPadding = listPadding,
                                    listState = replayListState,
                                    landingPage = replayLandingPage,
                                )
                            }
                        }
                    }
                }

                // The top-bar footprint is transparent on every page so the
                // shared app-level gradient is continuous; its controls float
                // as separate circles in both the glass and blur materials.
                val isDetailVisible = detail != null &&
                    (detail.type == BrowseType.ALBUM ||
                        detail.type == BrowseType.PLAYLIST ||
                        detail.type == BrowseType.ARTIST) &&
                    !isLocalDetail && !showSettings &&
                    !showEqualizer && !showReplay
                val chromePageColor = if (isDetailVisible) {
                    detailPalette.background
                } else {
                    MaterialTheme.colorScheme.background
                }
                // This is the bottom floor itself turned upside down, not a
                // separately maintained approximation. Both edges therefore
                // share the same curve, height and page-aware colour — including
                // the white theme background in light mode.
                // On an artist page the photograph and logo run up under the status
                // bar, and a fade across them would only muddy them. It comes in as
                // the page scrolls past that header, when content starts passing
                // under the bar and needs the cover.
                val artistListState = detail
                    ?.takeIf { isDetailVisible && it.type == BrowseType.ARTIST }
                    ?.let { detailListStates.getOrPut(it.browseId) { LazyListState() } }
                val topFadeAlpha by remember(artistListState) {
                    derivedStateOf {
                        val list = artistListState ?: return@derivedStateOf 1f
                        if (list.firstVisibleItemIndex > 0) return@derivedStateOf 1f
                        val header = list.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == 0 }?.size?.toFloat()
                            ?: return@derivedStateOf 0f
                        ((list.firstVisibleItemScrollOffset - header * TOP_FADE_START) /
                            (header * TOP_FADE_RAMP)).coerceIn(0f, 1f)
                    }
                }
                BottomFadeScrim(
                    pageColor = chromePageColor,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .rotate(180f)
                        .graphicsLayer { alpha = topFadeAlpha },
                )

                FrostedTopBar(
                    title = when {
                        libraryShowAll != null && detail == null -> libraryShowAll?.title.orEmpty()
                        showEqualizer -> stringResource(R.string.equalizer)
                        showSettings -> stringResource(R.string.settings)
                        showReplay -> stringResource(R.string.replay)
                        detail != null && detailActiveShelf != null -> detailActiveShelf.orEmpty()
                        detail != null -> detail.title
                        else -> tabs[selectedTab].let {
                            if (it.label == "Play") stringResource(R.string.listen_now) else it.label
                        }
                    },
                    // Every page, in either material, uses separated floating
                    // circles over the shared gradient — no full-width pane.
                    transparentBackdrop = true,
                    artworkPageChrome = true,
                    backButtonHazeState = hazeState,
                    trailingTitle = if (detail != null && detailActiveShelf != null) detail.title else null,
                    // Search has no large in-list header to hand the title back to —
                    // the field takes that space — so its bar title is always up.
                    scrolled = when {
                        showSettings || showEqualizer ||
                            (libraryShowAll != null && detail == null) ||
                            (detail != null && detailActiveShelf != null) -> true
                        // The page leads with its own large "Replay", so the bar
                        // stays out of the way until that has been scrolled off.
                        showReplay -> replayScrolled
                        detail != null -> detailScrolled
                        else -> scrolled || selectedTab == TAB_SEARCH
                    },
                    refreshing = currentFeed != null && currentFeed in refreshing,
                    pullFraction = { currentPull?.distanceFraction ?: 0f },
                    onBack = when {
                        libraryShowAll != null && detail == null -> ({ libraryShowAll = null })
                        showEqualizer -> ({ showEqualizer = false })
                        settingsSubScreen != null -> ({ settingsSubScreen = null })
                        showSettings -> ({ showSettings = false })
                        showReplay -> ({ showReplay = false })
                        detailActiveShelf != null -> ({ detailActiveShelf = null })
                        detail != null -> ({ viewModel.closeDetail(); Unit })
                        else -> null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                    // Only on the Search tab itself, not on anything pushed over it.
                    accessory = if (
                        selectedTab == TAB_SEARCH && detail == null &&
                        libraryShowAll == null && !showSettings &&
                        !showEqualizer && !showReplay
                    ) {
                        {
                            // The search field lives up here in the bar, so it answers
                            // the nav bar's Search tap itself: focus and keyboard, the
                            // way the page used to.
                            val keyboard = LocalSoftwareKeyboardController.current
                            LaunchedEffect(searchFocusRequested) {
                                if (searchFocusRequested) {
                                    searchFieldFocus.requestFocus()
                                    keyboard?.show()
                                    searchFocusRequested = false
                                }
                            }
                            SearchField(
                                query = query,
                                onQueryChange = viewModel::onQueryChange,
                                // Nothing to submit: the query is answered from the
                                // device as it changes. The magnifier only drops the
                                // keyboard on the matches.
                                onSubmit = {},
                                focusRequester = searchFieldFocus,
                                placeholder = org.jetbrains.compose.resources.stringResource(
                                    SharedRes.string.search_library_hint,
                                ),
                            )
                        }
                    } else null,
                    actions = {
                        // Only worth surfacing where there's room for it and it won't
                        // be mistaken for a per-page action — Home, at rest.
                        if (!showSettings) {
                            // Only on a Library "Show all" grid: the one thing a
                            // playlist or album card there carries is a title, and
                            // a title is what this orders the grid by.
                            if (libraryShowAll != null && detail == null) {
                                Box {
                                    IconButton(onClick = { librarySortMenuOpen = true }) {
                                        Icon(
                                            Icons.Rounded.Sort,
                                            contentDescription = stringResource(R.string.sort_library),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = librarySortMenuOpen,
                                        onDismissRequest = { librarySortMenuOpen = false },
                                    ) {
                                        LibrarySort.entries.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.localizedLabel()) },
                                                trailingIcon = if (option == librarySort) {
                                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                                } else null,
                                                onClick = {
                                                    AppSettings.setLibrarySort(option)
                                                    librarySortMenuOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            // Only on an album or playlist page — an artist page
                            // has no single track list to reorder, and the device
                            // folders already carry this same control themselves
                            // (see `LocalSearchField`).
                            if (detail != null && !isLocalDetail && detail.type != BrowseType.ARTIST) {
                                // The menu itself is [FrostedSortMenu], composed
                                // with the app's other frosted overlays further
                                // down — in the main hierarchy, where the haze
                                // can see the content it blurs.
                                IconButton(onClick = { songSortMenuOpen = true }) {
                                    Icon(
                                        Icons.Rounded.Sort,
                                        contentDescription = stringResource(R.string.sort_songs),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    },
                )

                // Drawn before the bars so their own glass reads on top of it.
                BottomFadeScrim(
                    withMiniPlayer = player.song != null,
                    // Not the wash: by the foot of the screen the page has finished
                    // easing out of it and into this, so this is what is actually
                    // under the tab bar.
                    pageColor = chromePageColor,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // Whichever bar is drawing reports its height here, for the
                // guard to read at layout — never in composition, see the guard.
                val floatingBarsHeight = remember { mutableIntStateOf(0) }
                FloatingBarsTapGuard(
                    barsHeight = { floatingBarsHeight.intValue },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // One tab handler, whichever bar is drawing it.
                val onTabSelected: (Int) -> Unit = { index ->
                    viewModel.clearDetail()
                    showSettings = false
                    showEqualizer = false
                    showReplay = false
                    settingsSubScreen = null
                    libraryShowAll = null
                    selectedTab = index

                    // Every search tab tap resets the field, focuses it, and opens
                    // the keyboard through SearchScreen's focus request.
                    if (index == TAB_SEARCH) {
                        viewModel.onQueryChange("")
                        searchFocusRequested = true
                    }
                }

                if (glassActive) Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                ) {
                    QueueActionNoticeHost(queueNotice)
                    // Liquid glass replaces the two stacked bars with the single
                    // component they are stacked to imitate: the now playing
                    // controls dock into the tab bar rather than riding above it,
                    // and the pair folds together on scroll. See [GlassNavBar].
                    GlassNavBar(
                        tabs = tabs,
                        selectedIndex = selectedTab,
                        onTabSelected = onTabSelected,
                        scrollConnection = navBarScroll,
                        song = player.song,
                        isPlaying = player.isPlaying,
                        isLoading = playPauseBusy,
                        onPlayPause = {
                            togglePlayPause()
                        },
                        onNext = { controller?.seekToNextMediaItem() },
                        onPrevious = { controller?.seekToPrevious() },
                        onExpand = if (reducePlayerMotion) {
                            { showNowPlaying = true }
                        } else {
                            playerSheetMotion::open
                        },
                        dock = activeDock,
                        pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Capped and centred rather than run to the page's edges
                        // — see [FLOATING_BAR_MAX_WIDTH]. It sits on the Column
                        // rather than on each bar so the two are held to the same
                        // width and keep the shared left and right edge they have
                        // on a phone. Before fillMaxWidth, so the fill has
                        // already been bounded by the time it is applied.
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QueueActionNoticeHost(queueNotice)
                    player.song?.let { song ->
                        MiniPlayer(
                            song = song,
                            isPlaying = player.isPlaying,
                            isLoading = playPauseBusy,
                            hazeState = hazeState,
                            onPlayPause = {
                                togglePlayPause()
                            },
                            onNext = { controller?.seekToNextMediaItem() },
                            onPrevious = { controller?.seekToPrevious() },
                            onExpand = if (reducePlayerMotion) {
                                { showNowPlaying = true }
                            } else {
                                playerSheetMotion::open
                            },
                            dock = activeDock,
                            pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    FloatingBottomBar(
                        tabs = tabs,
                        selectedIndex = selectedTab,
                        hazeState = hazeState,
                        onTabSelected = onTabSelected,
                    )
                }
            }

        }

        val playerRaised = showNowPlaying && playerSong != null

        // ---- Now Playing ----
        if (playerRaised) {
            // What rememberModalBottomSheetState builds, but able to start open,
            // and with every hide routed through [playerSheetMotion] — a drag
            // released low, a back press, a tap on the scrim. Refused here and
            // carried out there, on a curve slow enough to watch the artwork
            // fly home on, rather than on the sheet's own.
            val sheetDensity = LocalDensity.current
            val confirmSheetValue: (SheetValue) -> Boolean = { value ->
                // Read when asked, not when the sheet was made: the setting
                // can change while the player is up.
                if (value == SheetValue.Hidden && !reducePlayerMotion) {
                    playerSheetMotion.close()
                    false
                } else {
                    true
                }
            }
            val nowPlayingSheetState = rememberSaveable(
                saver = SheetState.Saver(
                    skipPartiallyExpanded = true,
                    confirmValueChange = confirmSheetValue,
                    density = sheetDensity,
                    skipHiddenState = false,
                ),
            ) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = sheetDensity,
                    // Already open when the app is the one raising it: its
                    // content is held down, and slid up, by the motion.
                    initialValue = if (playerSheetMotion.holding) SheetValue.Expanded else SheetValue.Hidden,
                    confirmValueChange = confirmSheetValue,
                )
            }
            DisposableEffect(nowPlayingSheetState) {
                playerSheetMotion.sheet = nowPlayingSheetState
                playerDock.sheetOffset = playerSheetMotion::position
                onDispose {
                    playerDock.sheetOffset = null
                    playerSheetMotion.onSheetGone()
                }
            }
            ModalBottomSheet(
                onDismissRequest = { showNowPlaying = false },
                sheetState = nowPlayingSheetState,
                // None of M3's: it follows the sheet's own animation, which is
                // no longer what moves the player. The player draws its own,
                // off where the player actually is — see the content below.
                scrimColor = if (reducePlayerMotion) BottomSheetDefaults.ScrimColor else Color.Transparent,
                // The player fills the screen and paints its own background to
                // the very top, so the sheet's default 28.dp top corners would
                // only cut two notches out of the artwork behind the status bar.
                // Square, and not clipped along its top — see [PlayerSheetShape].
                shape = PlayerSheetShape,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                // M3 caps a bottom sheet at [BottomSheetDefaults.SheetMaxWidth]
                // (640.dp) and centres it once the window is wider than that —
                // built for a sheet that's meant to look like a sheet next to
                // visible content either side. This one is the whole player;
                // capped at 640dp on a tablet it renders as a narrow card with
                // the page it's supposed to be covering visible down both
                // sides. Unspecified opts out of the cap entirely, so the
                // sheet always spans the full window this app draws it for.
                sheetMaxWidth = Dp.Unspecified,
                // Back is the app's to carry out, like every other close — see
                // the handler below. Left to M3, a back press is the one close
                // that never asks `confirmValueChange`: the sheet hides itself
                // straight away on its own curve, then the window leaves on its
                // exit animation with the artwork still drawn in it.
                properties = ModalBottomSheetProperties(shouldDismissOnBackPress = reducePlayerMotion),
            ) {
                // With M3's own back off, the dialog's dispatcher ends here.
                // Composed ahead of the player, so the player's own handlers —
                // the lyrics and the queue putting themselves away first — are
                // newer and are asked before this one.
                BackHandler(enabled = !reducePlayerMotion) { playerSheetMotion.close() }
                // Keeps a sheet still "settling" after a lyrics or queue
                // scroll from taking the next touch meant for that list.
                // See [PlayerSheetMotion.attachWindow].
                val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                DisposableEffect(sheetWindow, reducePlayerMotion) {
                    if (!reducePlayerMotion) playerSheetMotion.attachWindow(sheetWindow)
                    onDispose { }
                }
                Box(
                    Modifier
                        // Where the app is holding the player, if it is — see
                        // [PlayerSheetMotion.contentOffsetPx]. Placement only.
                        .offset { IntOffset(0, playerSheetMotion.contentOffsetPx()) }
                        // The dim behind the player, from the window's top to
                        // its bottom whatever the player's offset, and as deep
                        // as the player is open.
                        .drawBehind {
                            val open = playerDock.openFraction()
                            // Not at all under a portrait player that is fully up:
                            // its own background is opaque, and a full-screen
                            // blend behind it is paid on every frame the window
                            // draws for nothing anyone can see.
                            if (!reducePlayerMotion && open > 0f && !(open >= 1f && playerDock.attached)) {
                                val top = playerSheetMotion.position().takeUnless { it.isNaN() } ?: 0f
                                drawRect(
                                    color = Color.Black.copy(alpha = PLAYER_SCRIM_ALPHA * open),
                                    topLeft = Offset(0f, -top.toInt().toFloat()),
                                    size = Size(size.width, size.height),
                                )
                            }
                        }
                        .guardSheetFromContentTouches(nowPlayingSheetState)
                        // The sheet fills the window, so its height is the
                        // whole of its travel: hidden sits that far down.
                        .onSizeChanged {
                            playerDock.sheetTravel = it.height.toFloat()
                            playerSheetMotion.laidOut = true
                        },
                ) {
                    CompositionLocalProvider(LocalPlayerDock provides activeDock) {
                        nowPlaying(playerSong)
                    }
                }
            }
        }

        // ---- Replay stories ----
        // Mounted out here rather than inside the pane above, because a story
        // covers the window: on a tablet the page is only the left half of the
        // row, and a story laid out inside it would run alongside the player
        // instead of over it.
        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { replayStory = null },
                    onShare = { card ->
                        replaySharePage = card
                        showReplayShare = true
                    },
                    paused = showReplayShare,
                )
            }
        }

        // ---- Share the Replay ----
        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    // Straight to full height. The sheet is a picture and two
                    // buttons, and half-open it showed the picture with both
                    // buttons below the fold — a sheet whose only two controls
                    // need a drag to reach.
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    ReplayShareSheet(
                        summary = summary,
                        holder = stringResource(R.string.app_name),
                        memberSince = replay.memberSince,
                        page = replaySharePage,
                        onDismiss = { showReplayShare = false },
                    )
                }
            }
        }

        // ---- Album / playlist detail ----
        // ---- Long-press track actions ----
        // One body for both presentations of the track menu: the sheet the ⋮
        // and the player open, and the popup a held row lifts into. Every
        // callback below is the same in either; only the frame differs.
        val songMenuBody: @Composable (Song, SongActionsPresentation) -> Unit = { song, presentation ->
            // Set by whoever opened it — see [menuFromPlayer]. It cannot be
            // read off the player's own visibility any more, because on a
            // tablet the player is visible whatever the menu was opened from.
            val fromPlayer = menuFromPlayer
            // Navigating has to take the player down with the sheet, or the
            // page it opens lands behind a still-covering player.
            // The track's cover stands in for an album's, but never for an
            // artist's picture — that page loads its own.
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                songActions = null
                dismissPlayer()
                val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
                viewModel.openDetail(id, title, sub, art, type)
            }
            SongActionsSheet(
                song = song,
                presentation = presentation,
                onAddToQueue = { addToQueue(song); songActions = null },
                onDeleteMusic = {
                    songActions = null
                    deletingSong = song
                },
                onMoveMusic = {
                    songActions = null
                    movingSong = song
                },
                onEditTags = {
                    songActions = null
                    editingSongTags = song
                },
                onAddToPlaylist = {
                    songActions = null
                    playlistTarget = song
                },
                onOpenAlbum = { id ->
                    openPage(
                        id,
                        song.albumName ?: song.title,
                        song.artist,
                        BrowseType.ALBUM,
                    )
                },
                onOpenArtist = { id ->
                    openPage(id, song.artist, context.getString(R.string.artist), BrowseType.ARTIST)
                },
                showSleepTimer = fromPlayer,
                onCopyLog = if (fromPlayer) {
                    {
                        songActions = null
                        scope.launch {
                            val text = TrackLog.forTrack(song, NerdStats.current.value)
                            clipboard.setText(AnnotatedString(text))
                            // The line count, not just "copied": it is the
                            // one thing the system's own paste confirmation
                            // doesn't say, and an empty log is a real
                            // outcome worth seeing rather than a silent one.
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.log_copied_line_count,
                                    text.lineSequence().count(),
                                    text.lineSequence().count(),
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                } else {
                    null
                },
                onLyricsOffset = if (fromPlayer) {
                    {
                        songActions = null
                        showLyricsOffset = true
                    }
                } else {
                    null
                },
            )
        }
        songActions?.takeIf { songMenuOrigin == null }?.let { song ->
            ModalBottomSheet(
                onDismissRequest = { songActions = null },
                // The sheet paints itself in the track's own colours, corners
                // and drag handle included — see SongActionsSheet.
                containerColor = Color.Transparent,
                dragHandle = null,
            ) {
                songMenuBody(song, SongActionsPresentation.Sheet)
            }
        }
        // Drawn over everything, tab bar and mini player included, and kept
        // composed through its own exit — so it is not gated on songActions.
        HeldContextMenu(
            item = songActions,
            held = songMenuOrigin,
            hazeState = hazeState,
            onDismiss = { songActions = null },
            // Tapping the lifted track opens its album, as Apple Music's does.
            onPreviewClick = { song ->
                song.albumId?.let { id ->
                    songActions = null
                    viewModel.openDetail(id, song.albumName ?: song.title, song.artist, song.thumbnailUrl, BrowseType.ALBUM)
                }
            },
        ) { song ->
            songMenuBody(song, SongActionsPresentation.Menu)
        }
        // A closed menu forgets where it was held, so whatever opens it next
        // — the ⋮, the player — starts from the sheet.
        LaunchedEffect(songActions == null) {
            if (songActions == null) songMenuOrigin = null
        }

        // ---- Add to playlist / new playlist ----
        // One sheet for both, because they are one decision: the list of
        // playlists with a way to make another. `creatingPlaylist` opens it
        // straight onto the form, which is what the Library tile means.
        if (playlistTarget != null || creatingPlaylist) {
            val target = playlistTarget
            val dismiss = {
                playlistTarget = null
                creatingPlaylist = false
            }
            ModalBottomSheet(
                onDismissRequest = dismiss,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                PlaylistPickerSheet(
                    playlists = playlists,
                    loading = playlistsLoading,
                    song = target,
                    startCreating = target == null,
                    onAdd = { picked ->
                        target?.let { song ->
                            viewModel.addToPlaylists(picked, song) { added, alreadyThere, failed ->
                                // One line for the whole batch, saying the
                                // outcome that matters most: what went in, else
                                // that it was all there already, else that it
                                // didn't work.
                                showQueueNotice(
                                    when {
                                        added > 1 -> context.resources.getQuantityString(
                                            R.plurals.added_to_playlists_notice, added, added,
                                        )
                                        added == 1 -> context.getString(R.string.song_added_to_playlist)
                                        alreadyThere > 0 && failed == 0 ->
                                            context.getString(R.string.song_already_in_playlist)
                                        else -> context.getString(R.string.failed)
                                    },
                                )
                            }
                        }
                        dismiss()
                    },
                    onCreate = { title, privacy ->
                        viewModel.createPlaylist(title, privacy, target)
                        dismiss()
                    },
                )
            }
        }

        // ---- Album / playlist actions ----
        // Opened by holding a card on any tab, or from the release page's own
        // overflow. What a track's long-press menu is to one song, this is to
        // the whole release — the queue rows above all.
        // One body for the sheet and the popup alike — see songMenuBody.
        val browseMenuBody: @Composable (BrowseTarget, SongActionsPresentation) -> Unit = { target, presentation ->
            // Every row here closes the menu first: the tracks may still have to
            // be read out of the store, and leaving the sheet up over a request
            // nothing on it reports on reads as a tap that didn't land.
            val act: ((List<Song>) -> Unit) -> () -> Unit = { action ->
                {
                    browseActions = null
                    withBrowseSongs(target, action)
                }
            }
            // Whose playlist this is, read off the app's own store rather than
            // asked for: every playlist this app can offer a menu for is one it
            // owns, so Rename and Delete are simply there whenever the card
            // names a playlist it knows.
            val playlist = target.browseId
                ?.let { id -> playlists.firstOrNull { it.browseId == id || it.playlistId == id } }
            val pinnedPlaylists by AppSettings.pinnedPlaylists.collectAsStateWithLifecycle()
            val pinnableId = target.browseId?.takeIf { target.type == BrowseType.PLAYLIST }
            BrowseActionsSheet(
                // The playlist as the store has it now, not as the target was
                // built — a rename lands while the sheet is up.
                target = target.copy(playlist = playlist),
                presentation = presentation,
                onRenameInSheet = {
                    browseRenameInSheet = true
                    browseMenuOrigin = null
                },
                startRenaming = browseRenameInSheet,
                onPlayNext = act(playSongsNext),
                onAddToQueue = act(addSongsToQueue),
                onPlay = act { songs -> play(songs, 0) }.takeIf { target.fromCard },
                onShuffle = act { songs ->
                    // As on a release page: shuffle goes on before the queue
                    // is built, so it is built shuffled rather than played
                    // out of order.
                    QueueShuffle.enableForNextQueue()
                    play(songs, songs.indices.random())
                }.takeIf { target.fromCard },
                onOpen = target.browseId
                    ?.takeIf { target.fromCard }
                    ?.let { id ->
                        {
                            browseActions = null
                            viewModel.openDetail(
                                browseId = id,
                                title = target.title,
                                subtitle = target.subtitle,
                                thumbnailUrl = target.thumbnailUrl,
                                type = target.type,
                            )
                        }
                    },
                onExportM3u8 = act { songs ->
                    val pl = com.music.beatchord.data.spotify.LocalPlaylist(
                        id = target.browseId ?: "export",
                        title = target.title,
                        songs = songs,
                    )
                    pendingExportPlaylist = pl
                    pendingExportFormat = "m3u8"
                    val suggested = "${pl.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.m3u8"
                    playlistExportLauncher.launch(suggested)
                }.takeIf { target.type == BrowseType.PLAYLIST },
                onExportMpl = act { songs ->
                    val pl = com.music.beatchord.data.spotify.LocalPlaylist(
                        id = target.browseId ?: "export",
                        title = target.title,
                        songs = songs,
                    )
                    pendingExportPlaylist = pl
                    pendingExportFormat = "mpl"
                    val suggested = "${pl.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.mpl"
                    playlistExportLauncher.launch(suggested)
                }.takeIf { target.type == BrowseType.PLAYLIST },
                isPinned = pinnableId != null && pinnableId in pinnedPlaylists,
                onTogglePin = pinnableId?.let { id ->
                    {
                        val nowPinned = AppSettings.togglePinnedPlaylist(id)
                        if (!nowPinned && id !in pinnedPlaylists) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.pinned_playlist_limit,
                                    AppSettings.MAX_PINNED_PLAYLISTS,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        browseActions = null
                    }
                },
                onRename = playlist?.let { p ->
                    { name: String ->
                        browseActions = null
                        viewModel.renamePlaylist(p, name)
                    }
                },
                onReorder = playlist?.let { p ->
                    {
                        browseActions = null
                        reorderTarget = p
                        reorderEntries = UiState.Loading
                        reorderSaving = false
                        viewModel.loadPlaylistEntries(p) { result ->
                            if (reorderTarget != p) return@loadPlaylistEntries
                            reorderEntries = result.fold(
                                onSuccess = { UiState.Success(it) },
                                onFailure = { UiState.Error(context.getString(R.string.failed)) },
                            )
                        }
                    }
                },
                onDelete = playlist?.let { p ->
                    {
                        browseActions = null
                        viewModel.deletePlaylist(p)
                    }
                },
            )
        }
        browseActions?.takeIf { browseMenuOrigin == null }?.let { target ->
            ModalBottomSheet(
                onDismissRequest = { browseActions = null },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                browseMenuBody(target, SongActionsPresentation.Sheet)
            }
        }
        HeldContextMenu(
            item = browseActions,
            held = browseMenuOrigin,
            hazeState = hazeState,
            onDismiss = { browseActions = null },
            // Tapping the lifted card opens it, as a tap on it in the list
            // would have. Not for a Local Music grouping, which has no page.
            onPreviewClick = { target ->
                target.browseId?.let { id ->
                    browseActions = null
                    viewModel.openDetail(
                        browseId = id,
                        title = target.title,
                        subtitle = target.subtitle,
                        thumbnailUrl = target.thumbnailUrl,
                        type = target.type,
                    )
                }
            },
        ) { target ->
            browseMenuBody(target, SongActionsPresentation.Menu)
        }
        LaunchedEffect(browseActions == null) {
            if (browseActions == null) {
                browseMenuOrigin = null
                browseRenameInSheet = false
            }
        }

        // ---- Reorder playlist ----
        // Full height, and the list never hands its leftover scroll to the
        // sheet: every vertical drag inside it is meant for a row or the list,
        // and one that pulled the sheet down instead would throw the new order
        // away. (Material3 1.3 has no sheetGesturesEnabled; the row drags
        // consume their own events, so the list's overscroll is the only path.)
        reorderTarget?.let { target ->
            val close = { reorderTarget = null }
            val keepSheetStill = remember {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
                    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
                }
            }
            ModalBottomSheet(
                onDismissRequest = close,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                ReorderPlaylistSheet(
                    modifier = Modifier.nestedScroll(keepSheetStill),
                    playlist = target,
                    entries = reorderEntries,
                    saving = reorderSaving,
                    onClose = close,
                    onSave = { reordered ->
                        val original = (reorderEntries as? UiState.Success)?.data.orEmpty()
                        reorderSaving = true
                        viewModel.reorderPlaylist(target, original, reordered) { saved ->
                            reorderSaving = false
                            showQueueNotice(
                                context.getString(if (saved) R.string.playlist_reordered else R.string.reorder_failed),
                            )
                            if (saved && reorderTarget == target) reorderTarget = null
                        }
                    },
                )
            }
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (songSortMenuOpen) {
            BackHandler { songSortMenuOpen = false }
            FrostedSortMenu(
                hazeState = hazeState,
                selected = songSort,
                onSelect = { option ->
                    detail?.browseId?.let { AppSettings.setDetailSongSort(it, option) }
                    songSortMenuOpen = false
                },
                // Flipping the date direction deliberately leaves the menu up:
                // closing it here would cut the arrow's rotation animation off
                // before it played, and the open menu lets the direction flip
                // read against the list reordering behind the frost.
                onFlipDateDirection = {
                    detail?.browseId?.let { browseId ->
                        val next = when (songSort) {
                            SongSort.DATE_ADDED_DESC -> SongSort.DATE_ADDED_ASC
                            SongSort.DATE_ADDED_ASC -> SongSort.DATE_ADDED_DESC
                            else -> SongSort.DATE_ADDED_DESC
                        }
                        AppSettings.setDetailSongSort(browseId, next)
                    }
                },
                onDismiss = { songSortMenuOpen = false },
            )
        }

        if (showAppLanguage) {
            BackHandler { showAppLanguage = false }
            AppLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showAppLanguage = false },
            )
        }

        if (showTranslationLanguage) {
            BackHandler { showTranslationLanguage = false }
            TranslationLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showTranslationLanguage = false },
            )
        }

        // At the root for the same two reasons every full-window alert is: it
        // is a haze card that has to sample the backdrop it is *not* inside,
        // and a full-window scrim that has to be full-window.
        deletingSong?.let { song ->
            ConfirmationAlert(
                hazeState = hazeState,
                title = "Delete Music",
                description = "Are you sure you want to permanently delete \"${song.title}\" from your device?",
                confirmLabel = "Delete",
                onConfirm = {
                    scope.launch {
                        val success = com.music.beatchord.data.LocalFileManager.deleteSong(context, song)
                        if (success) {
                            showQueueNotice("Deleted \"${song.title}\"")
                            viewModel.refresh(MainViewModel.Feed.LIBRARY)
                            viewModel.refresh(MainViewModel.Feed.HOME)
                        } else {
                            showQueueNotice("Failed to delete \"${song.title}\"")
                        }
                    }
                    deletingSong = null
                },
                onDismiss = { deletingSong = null },
            )
        }

        movingSong?.let { song ->
            val folders = remember { com.music.beatchord.data.LocalFileManager.getCommonMusicFolders(context) }
            AlertDialog(
                onDismissRequest = { movingSong = null },
                title = { Text("Move Music to Folder") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Choose a destination folder for \"${song.title}\":",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        folders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val ok = com.music.beatchord.data.LocalFileManager.moveSong(context, song, folder)
                                            if (ok) {
                                                showQueueNotice("Moved to ${folder.name}")
                                                viewModel.refresh(MainViewModel.Feed.LIBRARY)
                                            } else {
                                                showQueueNotice("Failed to move music")
                                            }
                                        }
                                        movingSong = null
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = folder.parentFile?.name?.let { ".../$it/${folder.name}" } ?: folder.absolutePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { movingSong = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        editingSongTags?.let { song ->
            var titleInput by remember(song) { mutableStateOf(song.title) }
            var artistInput by remember(song) { mutableStateOf(song.artist) }
            var albumInput by remember(song) { mutableStateOf(song.albumName ?: "") }

            AlertDialog(
                onDismissRequest = { editingSongTags = null },
                title = { Text("Edit Music Tags") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = titleInput,
                            onValueChange = { titleInput = it },
                            label = { Text("Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = artistInput,
                            onValueChange = { artistInput = it },
                            label = { Text("Artist") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = albumInput,
                            onValueChange = { albumInput = it },
                            label = { Text("Album") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = com.music.beatchord.data.LocalFileManager.editSongTags(
                                    context = context,
                                    song = song,
                                    newTitle = titleInput.trim(),
                                    newArtist = artistInput.trim(),
                                    newAlbum = albumInput.trim(),
                                )
                                if (ok) {
                                    showQueueNotice("Tags updated")
                                    viewModel.refresh(MainViewModel.Feed.LIBRARY)
                                    viewModel.refresh(MainViewModel.Feed.HOME)
                                } else {
                                    showQueueNotice("Failed to update tags")
                                }
                            }
                            editingSongTags = null
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingSongTags = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

    }
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

@Composable
private fun LibrarySort.localizedLabel(): String = when (this) {
    LibrarySort.DEFAULT -> stringResource(R.string.sort_default)
    LibrarySort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LibrarySort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
}

/**
 * The track-list sort menu, styled after the app's other frosted overlays: a full-screen
 * scrim to catch the dismissal tap, and the options on a frosted panel that
 * blurs the page behind it. Composed here in the main hierarchy rather than
 * as a popup window — which is exactly what lets the haze see the content it
 * is blurring.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FrostedSortMenu(
    hazeState: HazeState,
    selected: SongSort,
    onSelect: (SongSort) -> Unit,
    onFlipDateDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 56.dp, end = 20.dp)
                .width(IntrinsicSize.Max)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // Date added is one row, Spotify-style: the arrow on it shows
                // the direction — up for newest first, down for oldest — and
                // tapping flips it, the rotation animating the flip. Up is
                // also where a fresh activation lands, newest first being the
                // point of the feature.
                val dateActive = selected == SongSort.DATE_ADDED_ASC ||
                    selected == SongSort.DATE_ADDED_DESC
                val arrowRotation by animateFloatAsState(
                    targetValue = if (selected == SongSort.DATE_ADDED_ASC) 180f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "dateAddedArrow",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onFlipDateDirection() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.sort_date_added_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (dateActive) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(arrowRotation),
                        )
                    }
                }
                SongSort.entries
                    .filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }
                    .forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button) { onSelect(option) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.localizedLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (option == selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
}

/** The picked file's own name, which is all a playlist import needs from it. */
private fun displayNameOf(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment

/**
 * The credit on a shelf card's subtitle, which reads "Kind • Artist".
 *
 * Only the credit belongs in the field the player, mini player and everything
 * downstream read, so the kind is dropped where one is named.
 */
private fun artistFromSubtitle(subtitle: String?): String =
    subtitle?.substringAfterLast('•')?.trim().orEmpty()

/**
 * Whether this page id is the Local Music folder — `local:all`, the tabbed
 * Songs / Artists / Albums view of the files already on the device.
 *
 * Asked by name rather than `startsWith("local:")`, because that prefix also
 * covers a local playlist's own page (`local:playlist:…`), which is a plain
 * track listing under its own cover and wants the same chrome every other
 * release page gets.
 */
private fun String?.isDeviceFolder(): Boolean = this == "local:all"

/**
 * How far short of the end a seek is allowed to land.
 *
 * Seeking to the final millisecond is indistinguishable from the track running
 * out, so it starts the next song — which is not what anyone dragging to the end
 * of the bar, or tapping the last line of a lyric, is asking for. A second back
 * from the end plays the outro instead.
 */
private const val SEEK_END_GUARD_MS = 1_000L

/**
 * How far a detail page scrolls before its title moves up into the bar.
 *
 * Roughly the height of the sleeve and the credit stacked above the Play pair,
 * so the two titles hand over as the header one leaves rather than sitting on
 * screen together. The bar cross-fades over 220ms, which absorbs the difference
 * between that estimate and a particular page's real header.
 */
private val DETAIL_TITLE_DROP = 320.dp

/**
 * How fast, a second, a mini-player pull has to be moving when the finger
 * lifts to decide open or closed on its own, wherever it got to — see
 * [MiniPlayerPull]. A flick is a whole gesture; only a slow pull is judged by
 * the distance it covered.
 */
private val PLAYER_PULL_FLING_VELOCITY = 400.dp

/** M3's own scrim strength, which the player's dim stands in for. */
private const val PLAYER_SCRIM_ALPHA = 0.32f

/**
 * The player sheet's shape: square, as [RectangleShape] was, but reaching a
 * whole sheet's height above the sheet's top edge.
 *
 * The sheet's Surface clips its content to this shape, and on the way down to
 * the mini player the artwork travels ahead of the sheet carrying it — up
 * above the sheet's top edge, where a plain rectangle cut it off mid-air,
 * leaving only its lower part to arrive in the bar. Nothing else is drawn
 * outside the sheet (its background is transparent and it casts no shadow),
 * so at rest this is the rectangle it replaces.
 */
private object PlayerSheetShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, -size.height, size.width, size.height))
}

private const val TAB_HOME = 0
private const val TAB_EXPLORE = 1
private const val TAB_LIBRARY = 2
private const val TAB_SEARCH = 3

/**
 * What a tab's key is prefixed with in the content switcher above.
 *
 * The index is read back off it there rather than off `selectedTab`, so the
 * prefix has to be the one thing both the writing and the reading agree on.
 */
private const val TAB_KEY = "tab:"
