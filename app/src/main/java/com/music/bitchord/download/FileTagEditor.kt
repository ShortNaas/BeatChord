package com.music.bitchord.download

import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.toLrc

/**
 * The tag edit behind the "Edit music tags" dialog, for a file the user
 * already has on the device.
 *
 * Tagging a freshly written file is one job: it has no metadata, so a writer
 * rebuilds whatever it touches. This is the other situation. The file has been
 * on the device for years, its artwork and its genre and its track number are
 * the user's, and rewriting the metadata region has to leave everything it was
 * not asked to change exactly as it was. The taggers grew a `retag` entry point
 * for that ([FlacTagger.retag], [Mp4Tagger.retag]), and this object is what
 * picks between them and reads the file's own lyrics back out.
 *
 * **No network access.** Not an incidental detail: anything that fetched a
 * cover would make a local tag edit reach the internet for an image that is
 * already inside the file — and fail offline, which is when editing a tag is
 * most likely to be the thing someone is doing. The cover is therefore never
 * re-embedded. Passing
 * `cover = null` is what tells each tagger to carry the artwork it finds across
 * untouched, block for block, which is both cheaper and strictly better than
 * pulling one image out and writing it back as a new one.
 *
 * Deliberately narrow about which containers it rewrites. A `.webm` is written
 * by [WebmTagger] by appending a whole new `Tags`/`Attachments` pair after the
 * bytes already in the file, and Matroska has no cheap way to take the old ones
 * out again — removing an element from a `Segment` means moving every cluster
 * after it, and the `Cues`/`SeekHead` positions that point at them. Appending a
 * second `Tags` to a file that already has one leaves the answer to "what does
 * this track say" up to which element a player happens to read, so WebM edits
 * stay in MediaStore until that can be done properly.
 */
object FileTagEditor {

    /** The containers [retag] can rewrite in place, in their file extensions. */
    private val EDITABLE = setOf("flac", "m4a")

    /**
     * Whether [extension] names a container whose tags this can edit on disk.
     *
     * A file outside this set keeps the MediaStore row an edit gives it and
     * nothing in the file, which is what every format did before this existed.
     */
    fun handles(extension: String): Boolean = extension in EDITABLE

    /**
     * [bytes] with the tags replaced, or null when there is nothing to write.
     *
     * Everything the edit does not set is carried over from the file: its
     * artwork, its genre and track number, another tool's atoms, and its lyrics
     * in both forms — [lyricsIn] reads those back so that a title edit does not
     * silently cost a local track its lyrics.
     */
    fun retag(
        bytes: ByteArray,
        extension: String,
        title: String,
        artist: String,
        album: String?,
    ): ByteArray? {
        val lyrics = lyricsIn(bytes)
        val tagged = runCatching {
            when (extension) {
                "flac" -> FlacTagger.retag(bytes, title, artist, album, lyrics.first, lyrics.second)
                "m4a" -> Mp4Tagger.retag(bytes, title, artist, album, lyrics.first, lyrics.second)
                else -> null
            }
        }.getOrNull() ?: return null
        // Every tagger hands back the array it was given when there is nothing
        // safe to do with it, so a file with no safe rewrite is left alone.
        return tagged.takeIf { it !== bytes }
    }

    /**
     * The lyrics already in [bytes], as (portable, word-timed).
     *
     * The reader returns whichever field is there, preferring this app's own
     * A2 one — so whether the text that came back is word-synced is decided from
     * the text itself rather than from which field it came out of. When it is,
     * the plain form is derived through the same parser the player uses
     * ([toLrc]), because a word-timed line written into the portable field is
     * angle-bracket noise in every other player. When it is not, the text is
     * passed through byte for byte.
     */
    internal fun lyricsIn(bytes: ByteArray): Pair<String?, String?> {
        val raw = runCatching { EmbeddedLyrics.fromBytes(bytes) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null to null
        val lines = runCatching { LrcLib.parseLrc(raw) }.getOrDefault(emptyList())
        if (lines.none { it.isWordSynced || it.background?.isWordSynced == true }) return raw to null
        return lines.toLrc().ifBlank { raw } to raw
    }
}
