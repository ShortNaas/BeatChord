package com.music.bitchord.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.LibraryViewType
import kotlinx.coroutines.flow.StateFlow

/**
 * What the shared pages — Home, Library, Search and the rows they are built
 * from — need from the app around them: a handful of the user's settings. The
 * phone answers from `AppSettings`, the desktop from its own stores; the pages
 * read the same values either way. Installed once at start, beside
 * [com.music.bitchord.ui.player.PlayerPlatform].
 */
interface AppUiHost {
    /** A swipe on a track row plays it next rather than adding it to the end of the queue. */
    val swipeToPlayNext: StateFlow<Boolean>

    /** Home's Recents shelf as a paged list of rows or as a row of cards. */
    val homeRecentsViewType: StateFlow<LibraryViewType>

    fun setHomeRecentsViewType(value: LibraryViewType)

    /** Playlist browse ids pinned to the front of the Library's Playlists shelf, in pin order. */
    val pinnedPlaylists: StateFlow<List<String>>

    /** How a Library "Show all" grid is ordered. */
    val librarySort: StateFlow<LibrarySort>
}

object AppUi {
    @Volatile
    private var installed: AppUiHost? = null

    fun install(host: AppUiHost) {
        installed = host
    }

    val host: AppUiHost
        get() = checkNotNull(installed) { "AppUi.install was not called" }
}

/**
 * Whether the feeds answer a pull at the top with a refresh.
 *
 * On a phone that is a drag with a finger. A window has no such gesture — only
 * the mouse wheel, which scrolling up past the top of a page would turn into a
 * refresh nobody asked for — so the desktop switches it off.
 */
val LocalPullToRefreshEnabled = staticCompositionLocalOf { true }
