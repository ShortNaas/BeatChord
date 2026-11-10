package com.music.bitchord.download

import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD

import java.io.ByteArrayOutputStream

/**
 * Writes iTunes-style metadata atoms — title, artist, album, lyrics, cover —
 * into an already-downloaded M4A/MP4 file, in place.
 *
 * There is no public Android API for this: [android.media.MediaMuxer] can
 * copy tracks into a fresh MP4 but has no way to declare a title or embed
 * artwork, and every general-purpose Java tagging library drags in either
 * native code or `javax.imageio` (absent on Android, and the exact crash
 * several other music apps hit shipping the unmodified desktop jaudiotagger).
 * So this reads and rewrites the handful of boxes involved directly.
 *
 * The whole thing is a single insertion: a fresh `udta/meta/ilst` atom is
 * appended as the last child of `moov`. Growing `moov` shifts every byte
 * after it, which is only a problem because `stco`/`co64` (the sample tables
 * under `moov/trak/mdia/minf/stbl`) record *absolute* file offsets into
 * `mdat` — so every entry at or past the insertion point is bumped by the
 * inserted length. Nothing else in the file addresses itself by absolute
 * offset, so that one adjustment is sufficient regardless of whether `mdat`
 * sits before or after `moov`.
 *
 * That is right for a download, which lands on a file with no iTunes metadata
 * at all. [retag] runs on files that already have some — a track saved by
 * another tool, or this app's own earlier download — and appending a second
 * `udta` there would leave two sets of tags in one file, with the answer
 * depending on which one a given player reads. So [retag] rewrites the `udta`
 * the file already has instead, carrying across every `ilst` child it does not
 * set and — when no new cover is supplied — the existing `covr` with them. The
 * offset adjustment is the same, measured from the start of the replaced
 * `moov` rather than from its end.
 *
 * Any layout this doesn't recognise — no `moov`, a box that doesn't fit its
 * parent — falls through to returning the input unchanged rather than
 * guessing: a download that plays untagged is a smaller loss than one a
 * bad rewrite has corrupted.
 */
object Mp4Tagger {

    private data class BoxRef(
        val offset: Int,
        val headerLen: Int,
        val size: Int,
        /** The raw 32-bit size field, before size==0/1 are resolved — 0 means "to end of parent", which must be left alone rather than replaced with a real number. */
        val rawSize32: Long,
        val type: String,
    ) {
        val contentOffset get() = offset + headerLen
        val end get() = offset + size
    }

    /** Box types whose payload is itself a run of child boxes, on the path down to `stco`/`co64`. */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverIsPng: Boolean,
        /** The A2 form, kept beside [lyrics] rather than instead of it — see [freeformItem]. */
        wordLyrics: String? = null,
    ): ByteArray {
        val items = tagItems(title, artist, album, lyrics, cover, coverIsPng, wordLyrics)
        if (items.isEmpty()) return bytes

        val moov = runCatching {
            parseBoxes(bytes, 0, bytes.size).firstOrNull { it.type == "moov" }
        }.getOrNull() ?: return bytes

        return runCatching {
            insert(bytes, moov, udtaAtom(metaAtom(ilstAtom(items))))
        }.getOrDefault(bytes)
    }

    /**
     * The same tags, written into a file that already has some.
     *
     * Two differences from [tag], both of them about not destroying what is
     * already in the file. The `udta` the file carries is rewritten in place
     * rather than joined by a second one, so there is one answer to "what does
     * this track say". And anything in the old `ilst` this does not set is
     * carried into the new one — `trkn`, `disk`, `gnre`, another tool's atoms,
     * and above all `covr`, which is only replaced when a new [cover] is
     * supplied. Without that last part a tag edit would silently drop the
     * artwork of every M4A it touched.
     *
     * @param cover the new artwork, or null to keep whatever the file has.
     */
    fun retag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        /** The A2 form, kept beside [lyrics] rather than instead of it — see [freeformItem]. */
        wordLyrics: String? = null,
        cover: ByteArray? = null,
        coverIsPng: Boolean = false,
    ): ByteArray {
        val items = tagItems(title, artist, album, lyrics, cover, coverIsPng, wordLyrics)
        if (items.isEmpty()) return bytes

        val moov = runCatching {
            parseBoxes(bytes, 0, bytes.size).firstOrNull { it.type == "moov" }
        }.getOrNull() ?: return bytes

        return runCatching {
            replace(bytes, moov, items, hasCover = cover != null && cover.isNotEmpty())
        }.getOrDefault(bytes)
    }

    private fun tagItems(
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverIsPng: Boolean,
        wordLyrics: String?,
    ): List<ByteArray> {
        val items = mutableListOf<ByteArray>()
        // © is iTunes's own "copyright" prefix for the four text atoms
        // below — not a copyright mark here, just the byte their readers key on.
        if (title.isNotBlank()) items += textItem("©nam", title)
        if (artist.isNotBlank()) items += textItem("©ART", artist)
        if (!album.isNullOrBlank()) items += textItem("©alb", album)
        // `©lyr` is a UTF-8 text atom like the three above, with no length limit
        // and no objection to newlines, so LRC goes in as-is. There is a
        // separate `Sync Lyrics`/`sylt`-style representation in some tools;
        // nothing writes it, because `©lyr` holding LRC is what the players
        // that show synced lyrics for an M4A actually read.
        if (!lyrics.isNullOrBlank()) items += textItem("©lyr", lyrics)
        if (!wordLyrics.isNullOrBlank()) items += freeformItem(WORD_LYRICS_FIELD, wordLyrics)
        if (cover != null && cover.isNotEmpty()) items += coverItem(cover, coverIsPng)
        return items
    }

    private fun insert(bytes: ByteArray, moov: BoxRef, udta: ByteArray): ByteArray {
        val insertAt = moov.end
        val delta = udta.size

        val prefix = bytes.copyOf(insertAt)
        // rawSize32 == 0 means "this box runs to the end of its parent" — still
        // true after the insertion, since nothing follows moov but this new
        // atom, so the field is left as-is rather than given a concrete value.
        if (moov.rawSize32 != 0L) {
            if (moov.headerLen == 16) {
                writeU64(prefix, moov.offset + 8, moov.size.toLong() + delta)
            } else {
                writeU32(prefix, moov.offset, moov.size.toLong() + delta)
            }
        }

        val offsetBoxes = mutableListOf<BoxRef>()
        collectOffsetBoxes(bytes, moov, offsetBoxes)
        offsetBoxes.forEach { box ->
            when (box.type) {
                "stco" -> patchStco(prefix, box, insertAt.toLong(), delta.toLong())
                "co64" -> patchCo64(prefix, box, insertAt.toLong(), delta.toLong())
            }
        }

        val suffix = bytes.copyOfRange(insertAt, bytes.size)
        return prefix + udta + suffix
    }

    /**
     * The file with the metadata under `moov/udta` replaced by an `ilst` built
     * from [items] plus whatever the old one held and this does not set.
     *
     * The rewrite is bottom-up: a new `ilst`, a `meta` holding it in place of
     * the old one, a `udta` holding that, then a `moov` holding that. Each
     * container keeps its other children — `hdlr` and `keys` under `meta`, any
     * legacy atoms beside it under `udta`, every `trak` under `moov` — and its
     * own header width, so a box written with a 64-bit size stays one.
     */
    private fun replace(bytes: ByteArray, moov: BoxRef, items: List<ByteArray>, hasCover: Boolean): ByteArray {
        val moovKids = parseBoxes(bytes, moov.contentOffset, moov.end)
        val udta = moovKids.firstOrNull { it.type == "udta" }
        val meta = udta
            ?.let { parseBoxes(bytes, it.contentOffset, it.end).firstOrNull { kid -> kid.type == "meta" } }
        // `meta` is a FullBox, unlike `udta` and `ilst`: its first four bytes are
        // version/flags, and reading its children from the start of the payload
        // would take those for a box size. That mode of failure is silent — the
        // child list comes back empty and the rewrite then drops the artwork it
        // was supposed to carry.
        val metaKids = meta?.let { parseBoxes(bytes, it.contentOffset + 4, it.end) }.orEmpty()
        val ilst = metaKids.firstOrNull { it.type == "ilst" }

        val carried = ilst?.let { box ->
            parseBoxes(bytes, box.contentOffset, box.end).mapNotNull { item ->
                val raw = bytes.copyOfRange(item.offset, item.end)
                when {
                    // The file's own artwork is only replaced, never dropped.
                    item.type == "covr" -> raw.takeUnless { hasCover }
                    // The four text atoms this rewrite owns outright; a blank
                    // value means the field is being cleared, not kept.
                    item.type in TEXT_ITEMS -> null
                    // Only this app's own word-timings item; another tool's
                    // `----` payload is carried like anything else.
                    item.type == "----" && isWordLyrics(bytes, item) -> null
                    else -> raw
                }
            }
        }.orEmpty()

        val newIlst = ilstAtom(carried + items)
        val newMeta = if (meta != null) {
            container(
                bytes = bytes,
                box = meta,
                prefix = bytes.copyOfRange(meta.contentOffset, meta.contentOffset + 4),
                kids = metaKids,
                replaced = "ilst",
                replacement = newIlst,
            )
        } else {
            metaAtom(newIlst)
        }
        val newUdta = if (udta != null) {
            container(bytes, udta, EMPTY, parseBoxes(bytes, udta.contentOffset, udta.end), "meta", newMeta)
        } else {
            udtaAtom(newMeta)
        }
        val newMoov = container(bytes, moov, EMPTY, moovKids, "udta", newUdta)

        return splice(bytes, moov, newMoov)
    }

    /**
     * A container box's payload with its first [replaced] child swapped for
     * [replacement], or the child appended when it had none. [prefix] is the
     * FullBox version/flags of the container itself, empty for a plain one.
     */
    private fun container(
        bytes: ByteArray,
        box: BoxRef,
        prefix: ByteArray,
        kids: List<BoxRef>,
        replaced: String,
        replacement: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(prefix.size + replacement.size + box.size)
        out.write(prefix)
        var placed = false
        for (kid in kids) {
            if (!placed && kid.type == replaced) {
                out.write(replacement)
                placed = true
            } else {
                out.write(bytes, kid.offset, kid.size)
            }
        }
        if (!placed) out.write(replacement)
        return boxed(box.type, out.toByteArray(), box.headerLen)
    }

    /** A box of [type] holding [payload], keeping the header width of the box it came from. */
    private fun boxed(type: String, payload: ByteArray, headerLen: Int): ByteArray {
        val size = headerLen + payload.size
        val out = ByteArray(size)
        if (headerLen == 16) {
            writeU32(out, 0, 1L)
            writeU64(out, 8, size.toLong())
        } else {
            writeU32(out, 0, size.toLong())
        }
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, headerLen)
        return out
    }

    /**
     * [bytes] with the `moov` at [moov] replaced by [newMoov], every absolute
     * sample offset moved by however much the box grew or shrank.
     *
     * The threshold is the start of the box rather than its end, because this
     * replacement can move `moov`'s *own* content as well as shift what follows
     * it. An offset before the box — an `mdat` ahead of a non-faststart `moov` —
     * is left alone; one at or past it moves with the box.
     */
    private fun splice(bytes: ByteArray, moov: BoxRef, newMoov: ByteArray): ByteArray {
        val delta = (newMoov.size - moov.size).toLong()
        val patched = newMoov.copyOf()
        val newMoovBox = parseBoxes(patched, 0, patched.size).firstOrNull { it.type == "moov" } ?: return bytes
        val offsetBoxes = mutableListOf<BoxRef>()
        collectOffsetBoxes(patched, newMoovBox, offsetBoxes)
        offsetBoxes.forEach { box ->
            when (box.type) {
                "stco" -> patchStco(patched, box, moov.offset.toLong(), delta)
                "co64" -> patchCo64(patched, box, moov.offset.toLong(), delta)
            }
        }

        val out = ByteArray(bytes.size + delta.toInt())
        bytes.copyInto(out, 0, 0, moov.offset)
        patched.copyInto(out, moov.offset)
        bytes.copyInto(out, moov.offset + patched.size, moov.end, bytes.size)
        return out
    }

    /**
     * Whether [item] is this app's own word-timings `----` item.
     *
     * Matched on the `name` child rather than on `----` itself: that fourcc is
     * iTunes' escape hatch and any tool can put anything in one, so dropping
     * every `----` would throw away someone else's tags to rewrite our own.
     */
    private fun isWordLyrics(bytes: ByteArray, item: BoxRef): Boolean {
        val name = parseBoxes(bytes, item.contentOffset, item.end).firstOrNull { it.type == "name" } ?: return false
        val start = name.contentOffset + 4
        if (start > name.end) return false
        return String(bytes, start, name.end - start, Charsets.UTF_8) == WORD_LYRICS_FIELD
    }

    private fun collectOffsetBoxes(bytes: ByteArray, box: BoxRef, out: MutableList<BoxRef>) {
        if (box.type == "stco" || box.type == "co64") {
            out += box
            return
        }
        if (box.type in CONTAINERS) {
            parseBoxes(bytes, box.contentOffset, box.end).forEach { collectOffsetBoxes(bytes, it, out) }
        }
    }

    /** `stco`: FullBox header, an entry count, then that many 32-bit offsets. */
    private fun patchStco(bytes: ByteArray, box: BoxRef, threshold: Long, delta: Long) {
        val base = box.contentOffset + 4
        val count = readU32(bytes, base).toInt()
        var p = base + 4
        repeat(count) {
            val off = readU32(bytes, p)
            if (off >= threshold) writeU32(bytes, p, off + delta)
            p += 4
        }
    }

    /** `co64`: the same shape as [patchStco], with 64-bit offsets. */
    private fun patchCo64(bytes: ByteArray, box: BoxRef, threshold: Long, delta: Long) {
        val base = box.contentOffset + 4
        val count = readU32(bytes, base).toInt()
        var p = base + 4
        repeat(count) {
            val off = readU64(bytes, p)
            if (off >= threshold) writeU64(bytes, p, off + delta)
            p += 8
        }
    }

    private fun parseBoxes(bytes: ByteArray, start: Int, end: Int): List<BoxRef> {
        val out = mutableListOf<BoxRef>()
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readU32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            var size = size32
            if (size32 == 1L) {
                if (pos + 16 > end) break
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (size32 == 0L) {
                size = (end - pos).toLong()
            }
            if (size < headerLen || pos + size > end || size > Int.MAX_VALUE) break
            out += BoxRef(pos, headerLen, size.toInt(), size32, type)
            pos += size.toInt()
        }
        return out
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun writeU32(b: ByteArray, off: Int, value: Long) {
        b[off] = ((value shr 24) and 0xFF).toByte()
        b[off + 1] = ((value shr 16) and 0xFF).toByte()
        b[off + 2] = ((value shr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }

    private fun writeU64(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = ((value shr (8 * (7 - i))) and 0xFF).toByte()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, (8 + payload.size).toLong())
        // ISO-8859-1, not ASCII: the iTunes item names below carry the 0xA9
        // "copyright" byte, which plain ASCII can't encode and would replace
        // with '?' — corrupting the very atom type a player looks up by.
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    /** iTunes's `data` atom: version(0) + a type indicator packed into 3 flag bytes, then the value. */
    private fun dataAtom(typeIndicator: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(8 + payload.size)
        writeU32(body, 0, typeIndicator.toLong())
        payload.copyInto(body, 8)
        return box("data", body)
    }

    /** Type indicator 1 = UTF-8 text. */
    private fun textItem(fourCc: String, text: String): ByteArray =
        box(fourCc, dataAtom(1, text.toByteArray(Charsets.UTF_8)))

    /** Type indicator 13 = JPEG, 14 = PNG. */
    private fun coverItem(image: ByteArray, isPng: Boolean): ByteArray =
        box("covr", dataAtom(if (isPng) 14 else 13, image))

    /**
     * A `----` item: iTunes' escape hatch for a field it has no four-byte code
     * for, named by a `mean`/`name` pair in front of the value.
     *
     * This is where the word timings go, and the reason they go somewhere of
     * their own is the one [LrcWriter] documents: `©lyr` is read by every other
     * player, and a reader without the A2 extension shows `<00:01.00>` rather
     * than skipping it. A player that doesn't know this item ignores it whole,
     * so the standard field stays clean and the timings still survive in the
     * file — see [EmbeddedLyrics][com.music.bitchord.data.lyrics.EmbeddedLyrics],
     * which reads them back.
     */
    private fun freeformItem(name: String, text: String): ByteArray {
        // Both are FullBoxes: four bytes of version/flags, then the string.
        val mean = box("mean", ByteArray(4) + MEAN.toByteArray(Charsets.UTF_8))
        val label = box("name", ByteArray(4) + name.toByteArray(Charsets.UTF_8))
        return box("----", mean + label + dataAtom(1, text.toByteArray(Charsets.UTF_8)))
    }

    /** The reverse-DNS owner of [freeformItem], which is what keeps the name ours. */
    private const val MEAN = "com.music.bitchord"

    /**
     * The `ilst` children a retag replaces outright.
     *
     * `covr` is not among them: artwork is replaced when a new cover is supplied
     * and carried across when one is not, which is a different decision from the
     * four text atoms here — a blank title means "clear this field", never
     * "keep whatever was there".
     */
    private val TEXT_ITEMS = setOf("©nam", "©ART", "©alb", "©lyr")

    /** The empty FullBox prefix, for the containers that are not FullBoxes. */
    private val EMPTY = ByteArray(0)

    /** A minimal handler box declaring this `meta` as iTunes-style metadata. */
    private fun hdlrAtom(): ByteArray {
        // version/flags(4) + pre_defined(4) + handler_type(4) + reserved(12) + name(1, empty cstring)
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

    private fun metaAtom(ilst: ByteArray): ByteArray {
        val hdlr = hdlrAtom()
        val payload = ByteArray(4 + hdlr.size + ilst.size)
        hdlr.copyInto(payload, 4)
        ilst.copyInto(payload, 4 + hdlr.size)
        return box("meta", payload)
    }

    private fun udtaAtom(meta: ByteArray): ByteArray = box("udta", meta)
}
