package com.music.bitchord.desktop

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/**
 * A page of tracks the desktop opens itself: a playlist kept here, or the songs filed under one
 * artist or one album.
 *
 * The phone's equivalent is a browsed album or playlist page, which does not exist any more: there
 * is no catalogue to browse, so every page this app opens is one of its own.
 */
internal data class DesktopCollection(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
    val songs: List<Song>,
    /** A playlist kept on this computer, which is the only kind an edit can be aimed at. */
    val owned: Boolean = false,
) {
    /** The playlist's own id, without the prefix its browse id carries. */
    val playlistId: String get() = browseId.removePrefix(LOCAL_PLAYLIST_PREFIX)
}

/** The library's own ids, so a card can say which page it opens. */
internal const val LOCAL_MUSIC_ID = "local:all"
internal const val LOCAL_ARTIST_PREFIX = "local:artist:"
internal const val LOCAL_ALBUM_PREFIX = "local:album:"
internal const val LOCAL_PLAYLIST_PREFIX = "desktop-playlist:"

/** A shelf on this app's pages is a row of cards, not a listing. */
private const val SHELF_ITEMS = 20

/** A track as a carousel card. */
internal fun Song.asShelfItem(): ShelfItem = ShelfItem(
    title = title,
    subtitle = artist,
    thumbnailUrl = thumbnailUrl,
    videoId = videoId,
    browseId = null,
)

/** A playlist kept here as a carousel card. */
internal fun DesktopPlaylist.asShelfItem(): ShelfItem = ShelfItem(
    title = title,
    subtitle = "Playlist • ${songs.size} songs",
    thumbnailUrl = songs.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
    videoId = null,
    browseId = LOCAL_PLAYLIST_PREFIX + id,
)

/** The name a track files under, for the artist and album shelves. */
private fun Song.artistName(): String =
    artist.takeIf(String::isNotBlank) ?: DesktopStrings["unknown_artist", "Unknown artist"]

/**
 * Listen Now, as this computer can build it: what was added lately, what was played lately, a
 * shuffled handful, and the artists and albums the library holds.
 *
 * Shuffled on every pass — the caller keys its remember on a refresh counter, so opening the app or
 * refreshing the page deals a fresh hand out of the same collection, the way the phone's Home does.
 */
internal fun localHomeShelves(
    songs: List<Song>,
    recentlyPlayed: List<Song>,
    playlists: List<DesktopPlaylist>,
): List<HomeShelf> {
    if (songs.isEmpty() && playlists.isEmpty() && recentlyPlayed.isEmpty()) return emptyList()
    return buildList {
        if (playlists.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["playlists", "Playlists"],
                    items = playlists.map(DesktopPlaylist::asShelfItem),
                ),
            )
        }
        if (recentlyPlayed.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["auto_recents", "Recent"],
                    items = recentlyPlayed.take(SHELF_ITEMS).map(Song::asShelfItem),
                ),
            )
        }
        if (songs.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["auto_quick_picks", "Quick picks"],
                    items = songs.shuffled().take(SHELF_ITEMS).map(Song::asShelfItem),
                ),
            )
            val artists = songs.groupBy(Song::artistName).entries.shuffled().take(SHELF_ITEMS)
            if (artists.isNotEmpty()) {
                add(
                    HomeShelf(
                        title = DesktopStrings["artists", "Artists"],
                        items = artists.map { (name, tracks) ->
                            ShelfItem(
                                title = name,
                                subtitle = "${tracks.size} ${DesktopStrings["songs", "Songs"]}",
                                thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                                videoId = null,
                                browseId = LOCAL_ARTIST_PREFIX + name,
                            )
                        },
                    ),
                )
            }
            val albums = songs
                .filter { !it.albumName.isNullOrBlank() }
                .groupBy { it.albumName.orEmpty() }
                .entries
                .shuffled()
                .take(SHELF_ITEMS)
            if (albums.isNotEmpty()) {
                add(
                    HomeShelf(
                        title = DesktopStrings["albums", "Albums"],
                        items = albums.map { (name, tracks) ->
                            ShelfItem(
                                title = name,
                                subtitle = tracks.firstOrNull()?.artistName().orEmpty(),
                                thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                                videoId = null,
                                browseId = LOCAL_ALBUM_PREFIX + name,
                            )
                        },
                    ),
                )
            }
        }
    }
}

/**
 * The Library, shelf by shelf, from what is on this computer.
 *
 * [likedSongs] carries the one collection the app keeps itself — the Favorites playlist — so the
 * shared page can draw it beside the shelves the scan produced.
 */
internal fun localLibraryPage(
    songs: List<Song>,
    recentlyPlayed: List<Song>,
    playlists: List<DesktopPlaylist>,
    likedSongs: List<Song> = emptyList(),
): LibraryPage {
    val shelves = buildList {
        if (playlists.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["playlists", "Playlists"],
                    items = playlists.map(DesktopPlaylist::asShelfItem),
                ),
            )
        }
        if (songs.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["songs", "Songs"],
                    items = songs.map(Song::asShelfItem),
                ),
            )
        }
        if (recentlyPlayed.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["auto_recents", "Recent"],
                    items = recentlyPlayed.take(SHELF_ITEMS).map(Song::asShelfItem),
                ),
            )
        }
        val artists = songs.groupBy(Song::artistName).entries
        if (artists.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["artists", "Artists"],
                    items = artists.sortedBy { it.key.lowercase() }.take(SHELF_ITEMS).map { (name, tracks) ->
                        ShelfItem(
                            title = name,
                            subtitle = "${tracks.size} ${DesktopStrings["songs", "Songs"]}",
                            thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                            videoId = null,
                            browseId = LOCAL_ARTIST_PREFIX + name,
                        )
                    },
                ),
            )
        }
        val albums = songs.filter { !it.albumName.isNullOrBlank() }.groupBy { it.albumName.orEmpty() }.entries
        if (albums.isNotEmpty()) {
            add(
                HomeShelf(
                    title = DesktopStrings["albums", "Albums"],
                    items = albums.sortedBy { it.key.lowercase() }.take(SHELF_ITEMS).map { (name, tracks) ->
                        ShelfItem(
                            title = name,
                            subtitle = tracks.firstOrNull()?.artistName().orEmpty(),
                            thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                            videoId = null,
                            browseId = LOCAL_ALBUM_PREFIX + name,
                        )
                    },
                ),
            )
        }
    }
    return LibraryPage(likedSongs = likedSongs, librarySongs = songs, shelves = shelves)
}

/** The songs one artist has on this computer, as a page. */
internal fun localArtistCollection(name: String, songs: List<Song>): DesktopCollection {
    val tracks = songs.filter { it.artistName().equals(name, ignoreCase = true) }
    return DesktopCollection(
        browseId = LOCAL_ARTIST_PREFIX + name,
        title = name,
        subtitle = DesktopStrings["artists", "Artists"],
        thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
        type = BrowseType.ARTIST,
        songs = tracks,
    )
}

/** The songs one album has on this computer, as a page. */
internal fun localAlbumCollection(name: String, songs: List<Song>): DesktopCollection {
    val tracks = songs.filter { it.albumName.equals(name, ignoreCase = true) }
    return DesktopCollection(
        browseId = LOCAL_ALBUM_PREFIX + name,
        title = name,
        subtitle = tracks.firstOrNull()?.artistName().orEmpty(),
        thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
        type = BrowseType.ALBUM,
        songs = tracks,
    )
}

/** One of the playlists kept here, as a page. */
internal fun localPlaylistCollection(playlist: DesktopPlaylist): DesktopCollection = DesktopCollection(
    browseId = LOCAL_PLAYLIST_PREFIX + playlist.id,
    title = playlist.title,
    subtitle = "Playlist • ${playlist.songs.size} songs",
    thumbnailUrl = playlist.songs.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
    type = BrowseType.PLAYLIST,
    songs = playlist.songs,
    owned = true,
)
