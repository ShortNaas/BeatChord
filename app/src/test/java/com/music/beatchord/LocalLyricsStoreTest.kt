package com.music.beatchord

import com.music.beatchord.data.lyrics.LocalLyricsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The sidecar half of the lyrics cache. Written by [LocalLyricsStore.save] and
 * read back by [LocalLyricsStore.get], so the two must agree on the exact
 * timestamp shape — the payload below is what `save` emits, not what LRC's
 * spec allows.
 */
class LocalLyricsStoreTest {

    private fun dir() = createTempDirectory("lyrics").toFile().also(File::deleteOnExit)

    @Test
    fun `sidecar written by save parses back`() {
        val root = dir()
        val audio = File(root, "Song.flac").apply { writeText("") }
        File(root, "Song.lrc").writeText(
            """
            [ti:Song]
            [ar:Artist]
            [by:BeatChord]
            [00:01.50]first line
            [01:04.00]second line
            """.trimIndent(),
        )

        val cached = LocalLyricsStore.sidecarLyrics(audio.absolutePath)

        assertEquals(listOf("first line", "second line"), cached?.lines?.map { it.text })
        assertEquals(1_500L, cached?.lines?.first()?.timeMs)
        assertEquals(64_000L, cached?.lines?.last()?.timeMs)
    }

    @Test
    fun `no sidecar means no lyrics`() {
        val root = dir()
        val audio = File(root, "Song.flac").apply { writeText("") }

        assertNull(LocalLyricsStore.sidecarLyrics(audio.absolutePath))
    }

    @Test
    fun `missing path means no lyrics`() {
        assertNull(LocalLyricsStore.sidecarLyrics(null))
    }
}
