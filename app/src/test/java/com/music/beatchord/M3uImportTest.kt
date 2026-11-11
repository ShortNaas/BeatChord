package com.music.beatchord

import com.music.beatchord.data.model.Song
import com.music.beatchord.data.playlists.M3uImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M3U reader behind the Playlists tab's Import button. Matching is the part
 * worth pinning: a playlist written on another machine carries absolute paths
 * that mean nothing here, so the fallbacks are load-bearing.
 */
class M3uImportTest {

    private fun song(videoId: String, title: String, path: String? = null) = Song(
        videoId = videoId,
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
        localPath = path,
    )

    private val library = listOf(
        song("a", "First", "/storage/emulated/0/Music/First.flac"),
        song("b", "Second", "/storage/emulated/0/Music/Second.mp3"),
        song("c", "Third", "/storage/emulated/0/Music/Third.ogg"),
    )

    @Test
    fun `parses extended m3u with titles and bare paths`() {
        val raw = """
            #EXTM3U
            #PLAYLIST:My list
            #EXTINF:213,First song
            /music/first.flac
            second.mp3
            # a comment
            #EXTINF:-1,Third song
            ../Third.ogg
        """.trimIndent()

        val entries = M3uImport.parse(raw)

        assertEquals(listOf("First song", null, "Third song"), entries.map { it.title })
        assertEquals(listOf("/music/first.flac", "second.mp3", "../Third.ogg"), entries.map { it.path })
    }

    @Test
    fun `matches by full path, then file name, then extinf title`() {
        val entries = listOf(
            M3uImport.Entry(null, "/storage/emulated/0/Music/First.flac"),
            M3uImport.Entry(null, "D:\\Other\\Computer\\SECOND.MP3"),
            M3uImport.Entry("Third", "/somewhere/else/track9.ogg"),
        )

        val matched = M3uImport.match(entries, library)

        assertEquals(listOf("a", "b", "c"), matched.map { it.videoId })
    }

    @Test
    fun `drops entries the device does not have, and repeats`() {
        val entries = listOf(
            M3uImport.Entry(null, "/storage/emulated/0/Music/First.flac"),
            M3uImport.Entry(null, "/nowhere/Missing.flac"),
            M3uImport.Entry(null, "First.flac"),
        )

        val matched = M3uImport.match(entries, library)

        assertEquals(listOf("a"), matched.map { it.videoId })
    }

    @Test
    fun `empty inputs match nothing`() {
        assertTrue(M3uImport.match(emptyList(), library).isEmpty())
        assertTrue(M3uImport.match(listOf(M3uImport.Entry(null, "x")), emptyList()).isEmpty())
    }

    @Test
    fun `title drops the extension`() {
        assertEquals("Road Trip", M3uImport.titleOf("Road Trip.m3u8"))
        assertEquals("Imported playlist", M3uImport.titleOf(null))
    }
}
