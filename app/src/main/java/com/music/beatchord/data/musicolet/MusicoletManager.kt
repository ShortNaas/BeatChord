package com.music.beatchord.data.musicolet

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.music.beatchord.data.model.Song
import com.music.beatchord.data.spotify.LocalPlaylist
import com.music.beatchord.data.spotify.LocalPlaylistStore
import com.music.beatchord.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class MusicoletPlaylist(
    val title: String,
    val songs: List<Song>,
)

data class MusicoletSongRecord(
    val path: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val totalPlays: Int,
    val weeklyPlays: Int,
    val monthlyPlays: Int,
    val yearlyPlays: Int,
    val lastPlayedEpochMs: Long,
)

data class MusicoletBackupData(
    val playlists: List<MusicoletPlaylist>,
    val songRecords: List<MusicoletSongRecord>,
)

data class MusicoletImportSummary(
    val playlistsImported: Int,
    val songsWithHistory: Int,
    val totalPlaysImported: Int,
)

object MusicoletManager {
    private const val TAG = "MusicoletManager"
    private const val BLOWFISH_KEY = "JSTMUSIC_2"

    /**
     * Decrypts Musicolet data using Blowfish/ECB/PKCS5Padding.
     * If the payload is already unencrypted (e.g. plain JSON/M3U), returns it directly.
     */
    fun decryptBlowfish(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val firstChar = data[0].toInt().toChar()
        if (firstChar == '{' || firstChar == '[' || firstChar == '#') {
            return data
        }
        return runCatching {
            val keySpec = SecretKeySpec(BLOWFISH_KEY.toByteArray(Charsets.UTF_8), "Blowfish")
            val cipher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            cipher.doFinal(data)
        }.getOrElse {
            // Fallback: maybe no padding was used
            runCatching {
                val keySpec = SecretKeySpec(BLOWFISH_KEY.toByteArray(Charsets.UTF_8), "Blowfish")
                val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
                val dec = cipher.doFinal(data)
                val pad = dec.lastOrNull()?.toInt() ?: 0
                if (pad in 1..16) dec.copyOfRange(0, dec.size - pad) else dec
            }.getOrDefault(data)
        }
    }

    /**
     * Encrypts data using Blowfish/ECB/PKCS5Padding with key JSTMUSIC_2.
     */
    fun encryptBlowfish(data: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(BLOWFISH_KEY.toByteArray(Charsets.UTF_8), "Blowfish")
        val cipher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(data)
    }

    /**
     * Parses a Musicolet .mpl playlist file (encrypted or plain).
     */
    fun parseMpl(bytes: ByteArray, defaultTitle: String): MusicoletPlaylist {
        val decrypted = decryptBlowfish(bytes)
        val jsonStr = decrypted.toString(Charsets.UTF_8)
        val obj = JSONObject(jsonStr)

        val title = obj.optString("NAME", defaultTitle).takeIf { it.isNotBlank() } ?: defaultTitle
        val paths = obj.optJSONArray("S_P") ?: JSONArray()
        val titles = obj.optJSONArray("S_T")
        val albums = obj.optJSONArray("S_AL")
        val artists = obj.optJSONArray("S_AR")
        val durations = obj.optJSONArray("S_D")

        val songs = mutableListOf<Song>()
        for (i in 0 until paths.length()) {
            val path = paths.optString(i, "")
            if (path.isBlank()) continue
            val songTitle = titles?.optString(i)?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringBeforeLast('.')
            val songArtist = artists?.optString(i)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val songAlbum = albums?.optString(i)?.takeIf { it.isNotBlank() }
            val durationMs = durations?.optLong(i, 0L) ?: 0L

            songs.add(
                Song(
                    videoId = path,
                    title = songTitle,
                    artist = songArtist,
                    albumName = songAlbum,
                    thumbnailUrl = null,
                    durationText = formatDuration(durationMs),
                    localUri = path,
                )
            )
        }
        return MusicoletPlaylist(title = title, songs = songs)
    }

    /**
     * Writes a playlist in Musicolet .mpl JSON format.
     */
    fun writeMpl(title: String, songs: List<Song>, encrypt: Boolean = false): ByteArray {
        val obj = JSONObject()
        obj.put("NAME", title)

        val pathsArray = JSONArray()
        val titlesArray = JSONArray()
        val albumsArray = JSONArray()
        val artistsArray = JSONArray()
        val durationsArray = JSONArray()

        for (song in songs) {
            val path = song.localUri ?: song.videoId
            pathsArray.put(path)
            titlesArray.put(song.title)
            albumsArray.put(song.albumName ?: "")
            artistsArray.put(song.artist)
            durationsArray.put(parseDurationToMs(song.durationText))
        }

        obj.put("S_P", pathsArray)
        obj.put("S_T", titlesArray)
        obj.put("S_AL", albumsArray)
        obj.put("S_AR", artistsArray)
        obj.put("S_D", durationsArray)

        val raw = obj.toString().toByteArray(Charsets.UTF_8)
        return if (encrypt) encryptBlowfish(raw) else raw
    }

    /**
     * Parses standard M3U / M3U8 content.
     */
    fun parseM3u(content: String, defaultTitle: String): MusicoletPlaylist {
        var playlistTitle = defaultTitle
        val songs = mutableListOf<Song>()

        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingDurationSec: Long = 0L

        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                playlistTitle = line.substringAfter(":").trim()
            } else if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val metadata = line.removePrefix("#EXTINF:").removePrefix("#extinf:").trim()
                val durationPart = metadata.substringBefore(",")
                val titlePart = metadata.substringAfter(",", "")
                pendingDurationSec = durationPart.toLongOrNull() ?: 0L
                if (titlePart.contains(" - ")) {
                    pendingArtist = titlePart.substringBefore(" - ").trim()
                    pendingTitle = titlePart.substringAfter(" - ").trim()
                } else {
                    pendingTitle = titlePart.trim()
                }
            } else if (!line.startsWith("#")) {
                val path = line
                val title = pendingTitle?.takeIf { it.isNotBlank() }
                    ?: path.substringAfterLast('/').substringBeforeLast('.')
                val artist = pendingArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                songs.add(
                    Song(
                        videoId = path,
                        title = title,
                        artist = artist,
                        thumbnailUrl = null,
                        durationText = formatDuration(pendingDurationSec * 1000L),
                        localUri = path,
                    )
                )
                pendingTitle = null
                pendingArtist = null
                pendingDurationSec = 0L
            }
        }
        return MusicoletPlaylist(title = playlistTitle, songs = songs)
    }

    /**
     * Writes standard M3U8 content.
     */
    fun writeM3u8(title: String, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:").append(title).append("\n")
        for (song in songs) {
            val durSec = parseDurationToMs(song.durationText) / 1000L
            sb.append("#EXTINF:").append(durSec).append(",").append(song.artist).append(" - ").append(song.title).append("\n")
            sb.append(song.localUri ?: song.videoId).append("\n")
        }
        return sb.toString()
    }

    /**
     * Reads a Musicolet backup archive (zip or file) and extracts playlists and play stats.
     */
    suspend fun parseMusicoletBackup(context: Context, uri: Uri): MusicoletBackupData = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup stream")
        val allBytes = inputStream.use { it.readBytes() }

        val playlists = mutableListOf<MusicoletPlaylist>()
        val records = mutableListOf<MusicoletSongRecord>()

        runCatching {
            ZipInputStream(ByteArrayInputStream(allBytes)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    val entryBytes = zis.readBytes()

                    if (name.endsWith(".mpl", ignoreCase = true)) {
                        runCatching {
                            val pl = parseMpl(entryBytes, name.removeSuffix(".mpl"))
                            playlists.add(pl)
                        }.onFailure { Log.w(TAG, "Failed to parse mpl $name", it) }
                    } else if (name == "DB_SONGS_LOG") {
                        runCatching {
                            val dec = decryptBlowfish(entryBytes)
                            val tempFile = File.createTempFile("musicolet_db", ".sqlite", context.cacheDir)
                            FileOutputStream(tempFile).use { it.write(dec) }

                            val db = SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                            val cursor = db.rawQuery(
                                "SELECT COL_PATH, COL_TITLE, COL_ARTIST, COL_ALBUM, COL_DURATION, " +
                                "COL_NUM_PLAYED, COL_NUM_PLAYED_W, COL_NUM_PLAYED_M, COL_NUM_PLAYED_Y, COL_LAST_PLAYED " +
                                "FROM TABLE_SONGS", null
                            )
                            cursor.use { c ->
                                val iPath = c.getColumnIndex("COL_PATH")
                                val iTitle = c.getColumnIndex("COL_TITLE")
                                val iArtist = c.getColumnIndex("COL_ARTIST")
                                val iAlbum = c.getColumnIndex("COL_ALBUM")
                                val iDur = c.getColumnIndex("COL_DURATION")
                                val iPlay = c.getColumnIndex("COL_NUM_PLAYED")
                                val iPlayW = c.getColumnIndex("COL_NUM_PLAYED_W")
                                val iPlayM = c.getColumnIndex("COL_NUM_PLAYED_M")
                                val iPlayY = c.getColumnIndex("COL_NUM_PLAYED_Y")
                                val iLast = c.getColumnIndex("COL_LAST_PLAYED")

                                while (c.moveToNext()) {
                                    records.add(
                                        MusicoletSongRecord(
                                            path = if (iPath >= 0) c.getString(iPath) ?: "" else "",
                                            title = if (iTitle >= 0) c.getString(iTitle) ?: "" else "",
                                            artist = if (iArtist >= 0) c.getString(iArtist) ?: "" else "",
                                            album = if (iAlbum >= 0) c.getString(iAlbum) else null,
                                            durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                                            totalPlays = if (iPlay >= 0) c.getInt(iPlay) else 0,
                                            weeklyPlays = if (iPlayW >= 0) c.getInt(iPlayW) else 0,
                                            monthlyPlays = if (iPlayM >= 0) c.getInt(iPlayM) else 0,
                                            yearlyPlays = if (iPlayY >= 0) c.getInt(iPlayY) else 0,
                                            lastPlayedEpochMs = if (iLast >= 0) c.getLong(iLast) else 0L,
                                        )
                                    )
                                }
                            }
                            db.close()
                            tempFile.delete()
                        }.onFailure { Log.w(TAG, "Failed to parse DB_SONGS_LOG", it) }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.onFailure { Log.w(TAG, "Zip reading error", it) }

        // If not a zip, maybe it is a single .mpl or .m3u file
        if (playlists.isEmpty()) {
            val fileName = uri.lastPathSegment ?: "Imported Playlist"
            if (fileName.endsWith(".mpl", ignoreCase = true) || allBytes.size > 10 && (allBytes[0] == '{'.code.toByte() || allBytes[0] != '#'.code.toByte())) {
                runCatching {
                    playlists.add(parseMpl(allBytes, fileName.removeSuffix(".mpl")))
                }
            }
            if (playlists.isEmpty()) {
                val text = allBytes.toString(Charsets.UTF_8)
                if (text.contains("#EXTM3U") || text.lines().any { it.contains('/') }) {
                    runCatching {
                        playlists.add(parseM3u(text, fileName.removeSuffix(".m3u8").removeSuffix(".m3u")))
                    }
                }
            }
        }

        MusicoletBackupData(playlists, records)
    }

    /**
     * High-level import function: imports playlists into LocalPlaylistStore
     * and historical play counts into ListeningStats!
     */
    suspend fun importIntoBeatChord(context: Context, uri: Uri): MusicoletImportSummary = withContext(Dispatchers.IO) {
        val backupData = parseMusicoletBackup(context, uri)

        // 1. Save playlists to LocalPlaylistStore
        var playlistsCount = 0
        for (pl in backupData.playlists) {
            if (pl.songs.isNotEmpty()) {
                LocalPlaylistStore.savePlaylist(pl.title, pl.songs)
                playlistsCount++
            }
        }

        // 2. Inject song play history into ListeningStats
        var totalPlays = 0
        var songsWithHistory = 0
        if (backupData.songRecords.isNotEmpty()) {
            val validRecords = backupData.songRecords.filter { it.totalPlays > 0 || it.weeklyPlays > 0 || it.monthlyPlays > 0 || it.yearlyPlays > 0 }
            songsWithHistory = validRecords.size
            totalPlays = validRecords.sumOf { it.totalPlays }
            ListeningStats.importMusicoletRecords(validRecords)
        }

        MusicoletImportSummary(
            playlistsImported = playlistsCount,
            songsWithHistory = songsWithHistory,
            totalPlaysImported = totalPlays,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun parseDurationToMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toLongOrNull() ?: 0L
                (m * 60 + s) * 1000L
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toLongOrNull() ?: 0L
                (h * 3600 + m * 60 + s) * 1000L
            }
            else -> 0L
        }
    }
}
