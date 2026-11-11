package com.music.beatchord

import com.music.beatchord.data.lyrics.EmbeddedLyrics
import com.music.beatchord.download.FileTagEditor
import com.music.beatchord.download.FlacTagger
import com.music.beatchord.download.Mp4Tagger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Editing the tags of a file that already has some, which is a different job
 * from tagging a download and fails differently.
 *
 * The download path always ran on a file with no metadata, so its writers were
 * free to rebuild whatever they touched. "Edit music tags" runs on a file that
 * has been on the device for years, and the tags it was not asked about — the
 * embedded artwork above all — have to come out of the rewrite exactly as they
 * went in. A tagger that quietly dropped the cover would look fine in every
 * existing test here and destroy something the user cannot get back, which is
 * why these tests assert on the cover bytes themselves rather than on the
 * container being well-formed.
 *
 * Everything is built synthetically: a minimal FLAC block chain, and an M4A
 * with the `moov/udta/meta/ilst` tree a real file has. No real audio, no real
 * artwork, no network.
 */
class TagEditPreservationTest {

    // ---- FLAC ---------------------------------------------------------------

    private val flacMagic = "fLaC".toByteArray(Charsets.US_ASCII)

    private fun flacFile(blocks: List<Pair<Int, ByteArray>>, frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(flacMagic)
        blocks.forEachIndexed { index, (type, payload) ->
            out.write(type or if (index == blocks.lastIndex) 0x80 else 0)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(payload)
        }
        out.write(frames)
        return out.toByteArray()
    }

    /** The metadata chain of [bytes] as (type, payload) pairs, and where the frames begin. */
    private fun flacChain(bytes: ByteArray): Pair<List<Pair<Int, ByteArray>>, Int> {
        assertArrayEquals(flacMagic, bytes.copyOfRange(0, 4))
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 4
        while (true) {
            val flags = bytes[offset].toInt() and 0xFF
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            blocks += (flags and 0x7F) to bytes.copyOfRange(offset + 4, offset + 4 + length)
            offset += 4 + length
            if (flags and 0x80 != 0) return blocks to offset
        }
    }

    /** A `PICTURE` block payload, in the layout [FlacTagger] writes and a real file uses. */
    private fun picturePayload(mime: String, cover: ByteArray, pictureType: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        fun be(value: Int) {
            out.write((value ushr 24) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        be(pictureType)
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        be(mimeBytes.size)
        out.write(mimeBytes)
        be(0) // description
        be(0) // width
        be(0) // height
        be(0) // depth
        be(0) // colours
        be(cover.size)
        out.write(cover)
        return out.toByteArray()
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun vorbisComment(vendor: String, fields: List<String>): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        var out = le32(vendorBytes.size) + vendorBytes + le32(fields.size)
        for (field in fields) {
            val encoded = field.toByteArray(Charsets.UTF_8)
            out += le32(encoded.size) + encoded
        }
        return out
    }

    /** The `NAME=value` fields of a `VORBIS_COMMENT` payload, little-endian like the format. */
    private fun commentFields(comment: ByteArray): List<String> {
        var at = 0
        fun u32(): Int {
            val value = (comment[at].toInt() and 0xFF) or
                ((comment[at + 1].toInt() and 0xFF) shl 8) or
                ((comment[at + 2].toInt() and 0xFF) shl 16) or
                ((comment[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return value
        }
        val vendor = u32()
        at += vendor
        val count = u32()
        return buildList {
            repeat(count) {
                val length = u32()
                add(String(comment, at, length, Charsets.UTF_8))
                at += length
            }
        }
    }

    private fun ByteArray.indexOfBytes(needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

    /** A FLAC with two pictures — a front cover and a back one — and a genre of its own. */
    private fun flacWithArtwork(lyrics: String? = null): ByteArray {
        val fields = buildList {
            add("TITLE=Old Title")
            add("ARTIST=Old Artist")
            add("GENRE=Jazz")
            if (lyrics != null) add("LYRICS=$lyrics")
        }
        return flacFile(
            blocks = listOf(
                TYPE_STREAMINFO to ByteArray(34) { it.toByte() },
                TYPE_VORBIS_COMMENT to vorbisComment("Some Other Tool", fields),
                TYPE_PICTURE to picturePayload("image/jpeg", COVER),
                TYPE_PICTURE to picturePayload("image/png", BACK_COVER, pictureType = 4),
                TYPE_PADDING to ByteArray(64),
            ),
            frames = ByteArray(96) { (it + 1).toByte() },
        )
    }

    @Test
    fun `flac retag keeps every picture when no new cover is supplied`() {
        val original = flacWithArtwork()
        val frames = flacChain(original).let { (_, at) -> original.copyOfRange(at, original.size) }

        val tagged = FlacTagger.retag(original, "New Title", "New Artist", "New Album", lyrics = null)

        assertNotSame(original, tagged)
        val (blocks, framesAt) = flacChain(tagged)
        // Padding spent, both pictures carried through, the comment rewritten
        // and moved to the end.
        assertEquals(
            listOf(TYPE_STREAMINFO, TYPE_PICTURE, TYPE_PICTURE, TYPE_VORBIS_COMMENT),
            blocks.map { it.first },
        )
        assertArrayEquals(picturePayload("image/jpeg", COVER), blocks[1].second)
        assertArrayEquals(picturePayload("image/png", BACK_COVER, pictureType = 4), blocks[2].second)
        assertArrayEquals(frames, tagged.copyOfRange(framesAt, tagged.size))

        // The three fields the user set are the new ones; the genre they never
        // touched is still there.
        assertTrue(tagged.indexOfBytes("TITLE=New Title".utf8()) >= 0)
        assertTrue(tagged.indexOfBytes("ARTIST=New Artist".utf8()) >= 0)
        assertTrue(tagged.indexOfBytes("ALBUM=New Album".utf8()) >= 0)
        assertTrue(tagged.indexOfBytes("GENRE=Jazz".utf8()) >= 0)
        assertEquals(-1, tagged.indexOfBytes("Old Title".utf8()))
        assertEquals(-1, tagged.indexOfBytes("Old Artist".utf8()))
    }

    /**
     * The download path never supplies a cover, and it never sees a file with
     * one either — but the rewrite it shares with [FlacTagger.retag] is the same
     * one, and "no cover given" must never mean "spend the ones in the file".
     */
    @Test
    fun `flac tag with no cover does not spend the artwork already in the file`() {
        val original = flacWithArtwork()

        val tagged = FlacTagger.tag(original, "New Title", "New Artist", null, lyrics = null, cover = null, coverMime = "image/jpeg")

        assertNotSame(original, tagged)
        val (blocks, _) = flacChain(tagged)
        assertEquals(2L, blocks.count { it.first == TYPE_PICTURE }.toLong())
        assertArrayEquals(picturePayload("image/jpeg", COVER), blocks.first { it.first == TYPE_PICTURE }.second)
        assertTrue(tagged.indexOfBytes(COVER) >= 0)
        assertTrue(tagged.indexOfBytes(BACK_COVER) >= 0)
    }

    @Test
    fun `flac retag with a new cover replaces the old pictures`() {
        val original = flacWithArtwork()

        val tagged = FlacTagger.retag(
            original,
            "New Title",
            "New Artist",
            null,
            lyrics = null,
            cover = NEW_COVER,
            coverMime = "image/png",
        )

        val (blocks, _) = flacChain(tagged)
        assertEquals(1L, blocks.count { it.first == TYPE_PICTURE }.toLong())
        assertArrayEquals(picturePayload("image/png", NEW_COVER), blocks.first { it.first == TYPE_PICTURE }.second)
        assertEquals(-1, tagged.indexOfBytes(COVER))
        assertEquals(-1, tagged.indexOfBytes(BACK_COVER))
        assertTrue(tagged.indexOfBytes(NEW_COVER) >= 0)
    }

    /**
     * The lyrics are the other thing a tag edit must not quietly spend: they
     * were written at download time and are the reason a downloaded song shows
     * its words offline at all.
     */
    @Test
    fun `flac retag carries the lyrics it was given and keeps them readable`() {
        val original = flacWithArtwork(lyrics = LRC)

        val tagged = requireNotNull(
            FileTagEditor.retag(original, "flac", "New Title", "New Artist", null),
        )

        assertEquals(LRC, EmbeddedLyrics.fromBytes(tagged))
        assertTrue(tagged.indexOfBytes(COVER) >= 0)
        assertTrue(tagged.indexOfBytes(BACK_COVER) >= 0)
        assertTrue(tagged.indexOfBytes("TITLE=New Title".utf8()) >= 0)
        assertEquals(-1, tagged.indexOfBytes("Old Title".utf8()))
    }

    /**
     * Both lyric fields have to come back: the A2 one keeps the word timings the
     * player draws on, and the portable one has to stay something a player
     * without A2 shows as text rather than angle-bracket noise.
     */
    @Test
    fun `a word-timed lyric sheet stays word-timed without stamping the portable field`() {
        val enhanced = "[00:01.00]<00:01.00>two <00:01.40>words <00:02.00>"
        val original = flacFile(
            blocks = listOf(
                TYPE_STREAMINFO to ByteArray(34),
                TYPE_VORBIS_COMMENT to vorbisComment(
                    "Some Other Tool",
                    listOf("TITLE=Old Title", "BEATCHORD_LYRICS=$enhanced"),
                ),
                TYPE_PICTURE to picturePayload("image/jpeg", COVER),
            ),
            frames = ByteArray(32) { 7 },
        )

        val tagged = requireNotNull(FileTagEditor.retag(original, "flac", "New Title", "New Artist", null))

        val fields = flacChain(tagged).first
            .first { it.first == TYPE_VORBIS_COMMENT }
            .second
            .let(::commentFields)
        // The portable field is plain LRC; the timings live in this app's own
        // field beside it, whole.
        assertEquals("[00:01.00]two words", fields.first { it.startsWith("LYRICS=") }.removePrefix("LYRICS="))
        assertEquals(enhanced, fields.first { it.startsWith("BEATCHORD_LYRICS=") }.removePrefix("BEATCHORD_LYRICS="))
        assertTrue(tagged.indexOfBytes(COVER) >= 0)
    }

    // ---- MP4 / M4A ----------------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        val size = out.size
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    /**
     * An iTunes `data` atom: version/flags (the low three bytes are the type
     * indicator), a four-byte locale, then the value — the layout [Mp4Tagger]
     * writes and every reader of these atoms assumes.
     */
    private fun dataAtom(typeIndicator: Int, value: ByteArray): ByteArray =
        box("data", u32(typeIndicator) + ByteArray(4) + value)

    private fun textItem(fourCc: String, text: String): ByteArray =
        box(fourCc, dataAtom(1, text.utf8()))

    private fun coverItem(image: ByteArray, isPng: Boolean = false): ByteArray =
        box("covr", dataAtom(if (isPng) 14 else 13, image))

    /** A `trkn` item, standing in for the atoms this rewrite has no opinion about. */
    private fun trknItem(): ByteArray =
        box("trkn", dataAtom(0, byteArrayOf(0, 0, 0, 1, 0, 1, 0, 0)))

    /** The minimal `meta` handler [Mp4Tagger] itself writes. */
    private fun hdlrAtom(): ByteArray {
        val body = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(body, 8)
        return box("hdlr", body)
    }

    private fun ilstAtom(items: List<ByteArray>): ByteArray {
        val payload = ByteArray(items.sumOf { it.size })
        var p = 0
        items.forEach { it.copyInto(payload, p); p += it.size }
        return box("ilst", payload)
    }

    /**
     * `ftyp` + `moov(trak/…/stco, udta(meta(hdlr, ilst)))` + `mdat`, with the
     * `stco` entry pointing at `mdat`'s payload — the layout a tagged M4A has,
     * including the sample offset a rewrite has to keep pointing at the audio.
     */
    private fun buildTaggedMp4(ilst: ByteArray, mdatPayload: ByteArray): Triple<ByteArray, Int, Int> {
        fun moovWithStcoOffset(offset: Int): ByteArray {
            val stcoPayload = ByteArray(12)
            stcoPayload[7] = 1 // entry_count = 1
            u32(offset).copyInto(stcoPayload, 8)
            val stbl = box("stbl", box("stco", stcoPayload))
            val trak = box("trak", box("mdia", box("minf", stbl)))
            val meta = box("meta", ByteArray(4) + hdlrAtom() + ilst)
            return box("moov", trak + box("udta", meta))
        }

        val ftyp = box("ftyp", ByteArray(8))
        val placeholder = moovWithStcoOffset(0)
        val mdatPayloadOffset = ftyp.size + placeholder.size + 8
        val moov = moovWithStcoOffset(mdatPayloadOffset)
        check(moov.size == placeholder.size)
        return Triple(ftyp + moov + box("mdat", mdatPayload), mdatPayloadOffset, ftyp.size)
    }

    private fun taggedIlst(): ByteArray = ilstAtom(
        listOf(
            textItem("©nam", "Old Title"),
            textItem("©ART", "Old Artist"),
            coverItem(COVER),
            trknItem(),
        ),
    )

    @Test
    fun `m4a retag keeps the existing cover, the other atoms and the sample offset`() {
        val mdatPayload = ByteArray(32) { (it + 1).toByte() }
        val (original, mdatPayloadOffset, _) = buildTaggedMp4(taggedIlst(), mdatPayload)

        val tagged = Mp4Tagger.retag(original, "New Title", "New Artist", "New Album", lyrics = null)

        assertNotSame(original, tagged)
        val delta = tagged.size - original.size
        // The audio is still where the sample table now says it is.
        val newOffset = mdatPayloadOffset + delta
        assertArrayEquals(mdatPayload, tagged.copyOfRange(newOffset, newOffset + mdatPayload.size))
        val stcoTypePos = tagged.indexOfBytes("stco".toByteArray(Charsets.US_ASCII))
        assertTrue(stcoTypePos >= 0)
        assertEquals(newOffset.toLong(), readU32(tagged, stcoTypePos + 4 + 4 + 4))

        // One set of tags, not two: the file's own udta was replaced.
        assertEquals(1L, tagged.countOccurrences("udta".toByteArray(Charsets.ISO_8859_1)).toLong())
        assertTrue(tagged.indexOfBytes("New Title".utf8()) >= 0)
        assertEquals(-1, tagged.indexOfBytes("Old Title".utf8()))
        // The artwork and an atom this rewrite has no opinion about survived.
        assertTrue(tagged.indexOfBytes(COVER) >= 0)
        assertTrue(tagged.indexOfBytes(trknItem()) >= 0)
    }

    @Test
    fun `m4a retag with a new cover replaces it and keeps the other atoms`() {
        val (original, _, _) = buildTaggedMp4(taggedIlst(), ByteArray(16) { 7 })

        val tagged = Mp4Tagger.retag(
            original,
            "New Title",
            "New Artist",
            null,
            lyrics = null,
            cover = NEW_COVER,
            coverIsPng = true,
        )

        assertTrue(tagged.indexOfBytes(NEW_COVER) >= 0)
        assertEquals(-1, tagged.indexOfBytes(COVER))
        assertTrue(tagged.indexOfBytes(trknItem()) >= 0)
        assertEquals(1L, tagged.countOccurrences("udta".toByteArray(Charsets.ISO_8859_1)).toLong())
    }

    /** The reader has to agree with the writer about what is in the file afterwards. */
    @Test
    fun `m4a retag keeps the lyrics readable and does not append a second udta`() {
        val ilst = ilstAtom(listOf(textItem("©nam", "Old Title"), textItem("©lyr", LRC), coverItem(COVER)))
        val (original, _, _) = buildTaggedMp4(ilst, ByteArray(16) { 7 })

        val tagged = requireNotNull(FileTagEditor.retag(original, "m4a", "New Title", "New Artist", null))

        assertEquals(LRC, EmbeddedLyrics.fromBytes(tagged))
        assertTrue(tagged.indexOfBytes(COVER) >= 0)
        assertTrue(tagged.indexOfBytes("New Title".utf8()) >= 0)
        assertEquals(-1, tagged.indexOfBytes("Old Title".utf8()))
    }

    // ---- What is and is not editable ---------------------------------------

    @Test
    fun `only the containers with a safe rewrite are claimed`() {
        assertTrue(FileTagEditor.handles("flac"))
        assertTrue(FileTagEditor.handles("m4a"))
        // Appending a second Tags/Attachments pair is not a safe edit — see
        // FileTagEditor — so WebM stays a MediaStore-only edit.
        assertFalse(FileTagEditor.handles("webm"))
        assertFalse(FileTagEditor.handles("mp3"))
        assertFalse(FileTagEditor.handles("wav"))
        assertNull(FileTagEditor.retag(byteArrayOf(1, 2, 3), "webm", "T", "A", null))
    }

    @Test
    fun `an unrecognisable container comes back as it was`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertSame(bytes, FlacTagger.retag(bytes, "T", "A", null, lyrics = null))
        assertSame(bytes, Mp4Tagger.retag(bytes, "T", "A", null, lyrics = null))
        assertNull(FileTagEditor.retag(bytes, "flac", "T", "A", null))
        assertNull(FileTagEditor.retag(bytes, "m4a", "T", "A", null))
    }

    private fun ByteArray.countOccurrences(needle: ByteArray): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = indexOfBytes(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.size
        }
    }

    private companion object {
        const val TYPE_STREAMINFO = 0
        const val TYPE_PADDING = 1
        const val TYPE_VORBIS_COMMENT = 4
        const val TYPE_PICTURE = 6

        val COVER = ByteArray(48) { (it * 3 + 11).toByte() }
        val BACK_COVER = ByteArray(32) { (it * 7 + 5).toByte() }
        val NEW_COVER = ByteArray(40) { (it * 5 + 2).toByte() }

        const val LRC = "[00:01.20]first line here\n[00:04.50]second line here"
    }
}
