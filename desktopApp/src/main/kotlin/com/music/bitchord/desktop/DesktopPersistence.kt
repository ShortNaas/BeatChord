package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences

/** Small OS-backed store for desktop state; no Android Context is required. */
class DesktopPersistence {
    internal val preferences = Preferences.userRoot().node("com.music.bitchord.desktop")

    fun likedIds(): Set<String> = readLines(KEY_LIKED_IDS).toSet()

    fun saveLikedIds(ids: Set<String>) = writeLines(KEY_LIKED_IDS, ids.toList())

    /** Every lyric database, in the order they are asked. */
    fun lyricsSourceOrder(): List<String> =
        // The phone's rule for a saved order meeting a newer build: hidden sources drop out, a new
        // one slots in beside its declared neighbour.
        LyricsSource.ordered(readNames(DesktopLyricsClient.KEY_LYRICS_ORDER).mapNotNull(::lyricsSourceNamed))
            .map { it.label }

    fun saveLyricsSourceOrder(names: List<String>) =
        saveString(DesktopLyricsClient.KEY_LYRICS_ORDER, names.joinToString(","))

    /** The ticked sources. */
    fun lyricsEnabledSources(): Set<String> {
        val known = DesktopLyricsClient.sources.map { it.name }
        val stored = string(DesktopLyricsClient.KEY_LYRICS_SOURCES, UNSET)
        if (stored == UNSET) return known.toSet()
        val ticked = stored.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        val offered = readNames(DesktopLyricsClient.KEY_LYRICS_SOURCES_SEEN).toSet()
        return ticked + known.filterNot { it in offered }
    }

    fun saveLyricsEnabledSources(names: Set<String>) {
        saveString(DesktopLyricsClient.KEY_LYRICS_SOURCES, names.joinToString(","))
        // Whatever was on screen when this was saved has now been offered, so a source left
        // unticked here is a decision rather than an omission.
        saveString(
            DesktopLyricsClient.KEY_LYRICS_SOURCES_SEEN,
            DesktopLyricsClient.sources.joinToString(",") { it.name },
        )
    }

    private fun readNames(key: String): List<String> =
        string(key, "").split(',').map(String::trim).filter(String::isNotBlank)

    /**
     * Distinguishes "never chosen" from "chosen to be empty", which a plain blank string cannot.
     */
    private val UNSET = "\u0000unset"

    fun history(): List<Song> = readSongs(KEY_HISTORY)

    fun saveHistory(songs: List<Song>) = writeSongs(KEY_HISTORY, songs.take(MAX_HISTORY))

    fun queue(): List<Song> = readSongs(KEY_QUEUE)

    fun saveQueue(songs: List<Song>) = writeSongs(KEY_QUEUE, songs.take(MAX_QUEUE))

    fun playlists(): List<DesktopPlaylist> = readLines(KEY_PLAYLISTS).mapNotNull(::decodePlaylist)

    fun savePlaylists(playlists: List<DesktopPlaylist>) = writeLines(KEY_PLAYLISTS, playlists.map(DesktopPlaylist::toPreferenceLine))

    fun boolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    fun saveBoolean(key: String, value: Boolean) = preferences.putBoolean(key, value)

    fun int(key: String, default: Int): Int = preferences.getInt(key, default)

    fun saveInt(key: String, value: Int) = preferences.putInt(key, value)

    fun float(key: String, default: Float): Float = preferences.getFloat(key, default)

    fun saveFloat(key: String, value: Float) = preferences.putFloat(key, value)

    fun string(key: String, default: String = ""): String =
        DesktopPreferenceChunks.read(preferences, key) ?: default

    fun saveString(key: String, value: String) =
        DesktopPreferenceChunks.write(preferences, key, value)

    private fun readSongs(key: String): List<Song> = readLines(key).mapNotNull(::decodeSong)

    private fun writeSongs(key: String, songs: List<Song>) =
        writeLines(key, songs.map(::encodeSong))

    private fun readLines(key: String): List<String> =
        DesktopPreferenceChunks.read(preferences, key).orEmpty()
            .split('\n')
            .filter(String::isNotBlank)

    private fun writeLines(key: String, lines: List<String>) =
        DesktopPreferenceChunks.write(preferences, key, lines.joinToString("\n"))

    private fun encodeSong(song: Song): String = listOf(
        song.videoId,
        song.title,
        song.artist,
        song.thumbnailUrl.orEmpty(),
        song.durationText.orEmpty(),
        song.artistId.orEmpty(),
        song.albumId.orEmpty(),
        song.albumName.orEmpty(),
        song.localUri.orEmpty(),
        song.localPath.orEmpty(),
        song.isVideo.toString(),
        song.isVideoOrigin.toString(),
        song.setVideoId.orEmpty(),
        song.fromAutoplay.toString(),
        song.localDateAddedSeconds?.toString().orEmpty(),
        song.localDateModifiedSeconds?.toString().orEmpty(),
        song.sourceQuality.orEmpty(),
        song.isExplicit?.toString().orEmpty(),
    ).joinToString(DELIMITER, transform = ::encode)

    private fun decodeSong(value: String): Song? {
        val fields = value.split(DELIMITER).map(::decode)
        if (fields.size < 10 || fields[0].isBlank()) return null
        val isVideo = fields.getOrNull(10)?.toBooleanStrictOrNull() ?: false
        return Song(
            videoId = fields[0],
            title = fields[1],
            artist = fields[2],
            thumbnailUrl = fields[3].ifBlank { null },
            durationText = fields[4].ifBlank { null },
            artistId = fields[5].ifBlank { null },
            albumId = fields[6].ifBlank { null },
            albumName = fields[7].ifBlank { null },
            localUri = fields[8].ifBlank { null },
            localPath = fields[9].ifBlank { null },
            isVideo = isVideo,
            isVideoOrigin = fields.getOrNull(11)?.toBooleanStrictOrNull() ?: isVideo,
            setVideoId = fields.getOrNull(12)?.ifBlank { null },
            fromAutoplay = fields.getOrNull(13)?.toBooleanStrictOrNull() ?: false,
            localDateAddedSeconds = fields.getOrNull(14)?.toLongOrNull(),
            localDateModifiedSeconds = fields.getOrNull(15)?.toLongOrNull(),
            sourceQuality = fields.getOrNull(16)?.ifBlank { null },
            isExplicit = fields.getOrNull(17)?.toBooleanStrictOrNull(),
        )
    }

    private fun decodePlaylist(value: String): DesktopPlaylist? {
        val fields = value.split(PLAYLIST_DELIMITER)
        if (fields.size < 3) return null
        val id = decode(fields[0]).ifBlank { return null }
        val title = decode(fields[1]).ifBlank { return null }
        val songs = fields[2].split(SONG_DELIMITER).filter(String::isNotBlank).mapNotNull(::decodeSong)
        return DesktopPlaylist(id, title, songs)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrDefault("")

    private companion object {
        const val KEY_LIKED_IDS = "liked_ids"
        const val KEY_HISTORY = "history"
        const val KEY_QUEUE = "queue"
        const val KEY_PLAYLISTS = "playlists"
        const val MAX_HISTORY = 100
        const val MAX_QUEUE = 200
        const val DELIMITER = "|"
        const val PLAYLIST_DELIMITER = "#"
        const val SONG_DELIMITER = ";"
    }
}

data class DesktopPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val songs: List<Song> = emptyList(),
)

private fun DesktopPlaylist.toPreferenceLine(): String = listOf(
    Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8)),
    Base64.getUrlEncoder().withoutPadding().encodeToString(title.toByteArray(StandardCharsets.UTF_8)),
    songs.joinToString(";") { song ->
        listOf(
            song.videoId,
            song.title,
            song.artist,
            song.thumbnailUrl.orEmpty(),
            song.durationText.orEmpty(),
            song.artistId.orEmpty(),
            song.albumId.orEmpty(),
            song.albumName.orEmpty(),
            song.localUri.orEmpty(),
            song.localPath.orEmpty(),
            song.isVideo.toString(),
            song.isVideoOrigin.toString(),
            song.setVideoId.orEmpty(),
            song.fromAutoplay.toString(),
            song.localDateAddedSeconds?.toString().orEmpty(),
            song.localDateModifiedSeconds?.toString().orEmpty(),
            song.sourceQuality.orEmpty(),
            song.isExplicit?.toString().orEmpty(),
        ).joinToString("|", transform = { field ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(field.toByteArray(StandardCharsets.UTF_8))
        })
    },
).joinToString("#")
