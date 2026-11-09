package com.music.bitchord.data.playlists

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song

/**
 * A `.m3u` / `.m3u8` playlist, matched against the songs already on the device.
 *
 * M3U is the one playlist format every other player writes, so it is the only
 * portable way to bring a playlist in without a remote service. Entries are
 * matched to local files — no network lookup, and an entry that is not in the
 * library is simply left out.
 */
object M3uImport {

    /** One `#EXTINF`-stamped line, or a bare path where there was no stamp. */
    data class Entry(val title: String?, val path: String)

    private val EXTINF = Regex("""^#EXTINF:[^,]*,?(.*)$""")

    /** [raw] as written, in file order. Header and unknown `#` directives are skipped. */
    fun parse(raw: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var pendingTitle: String? = null
        raw.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("#EXTINF:") ->
                    pendingTitle = EXTINF.find(trimmed)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                trimmed.startsWith("#") -> Unit
                else -> {
                    entries += Entry(pendingTitle, trimmed)
                    pendingTitle = null
                }
            }
        }
        return entries
    }

    /**
     * The entries that name a song this device actually has, in playlist order.
     *
     * Tried strongest first: the whole path, then the file's own name, then the
     * `#EXTINF` title. A M3U written on another machine carries paths that mean
     * nothing here, which is why the name and title fallbacks exist at all.
     */
    fun match(entries: List<Entry>, songs: List<Song>): List<Song> {
        if (entries.isEmpty() || songs.isEmpty()) return emptyList()

        val byPath = HashMap<String, Song>(songs.size)
        val byFileName = HashMap<String, Song>(songs.size)
        val byTitle = HashMap<String, Song>(songs.size)
        songs.forEach { song ->
            song.localPath?.let { path ->
                byPath[normalize(path)] = song
                byPath.putIfAbsent(normalize(fileName(path)), song)
                byFileName.putIfAbsent(normalize(fileName(path)), song)
            }
            byTitle.putIfAbsent(normalize(song.title), song)
        }

        val seen = HashSet<Long>(entries.size)
        return entries.mapNotNull { entry ->
            val key = normalize(entry.path)
            val song = byPath[key]
                ?: byFileName[normalize(fileName(entry.path))]
                ?: entry.title?.let { byTitle[normalize(it)] }
            song?.takeIf { seen.add(it.localId()) }
        }
    }

    /** The playlist's own name, minus the extension and minus any directory. */
    fun titleOf(displayName: String?): String =
        displayName?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Imported playlist"

    /** [uri] decoded to text. Throws if the file cannot be read. */
    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().decodeToString() }
            ?: error("Could not open the playlist file")

    // Windows paths and Unix ones both land here, so the separator is settled
    // before anything is cut off the end.
    // ponytail: percent-escapes are left as written; a playlist naming
    // "My%20Song.flac" falls through to the title match. Decode when it bites.
    private fun fileName(pathOrUri: String): String =
        pathOrUri.replace('\\', '/').substringBefore('?').substringAfterLast('/')

    private fun normalize(value: String): String =
        value.trim().replace('\\', '/').lowercase()

    /** Identity is the local file, which is the only thing a playlist row can name here. */
    private fun Song.localId(): Long = (localPath ?: localUri ?: videoId).hashCode().toLong()
}
