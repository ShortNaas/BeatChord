package com.music.beatchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the stats line reads off [DesktopAudioFormat]; every label it draws has to mean what the
 * decoder actually measured, and nothing is inferred from a file's name.
 */
class DesktopAudioFormatTest {

    @Test
    fun `ffmpeg codec names are recognised as lossless`() {
        assertTrue(DesktopAudioFormat(codec = "flac").isLossless)
        assertTrue(DesktopAudioFormat(codec = "alac").isLossless)
        assertTrue(DesktopAudioFormat(codec = "wavpack").isLossless)
        // FFmpeg names every uncompressed flavour pcm_<layout>.
        assertTrue(DesktopAudioFormat(codec = "pcm_s16le").isLossless)
        assertFalse(DesktopAudioFormat(codec = "aac").isLossless)
        assertFalse(DesktopAudioFormat(codec = "opus").isLossless)
    }

    @Test
    fun `hi-res is past CD, not merely lossless`() {
        val cd = DesktopAudioFormat("flac", sampleRateHz = 44_100, bitDepth = 16, channels = 2)
        val hiRes = DesktopAudioFormat("flac", sampleRateHz = 96_000, bitDepth = 24, channels = 2)
        assertTrue(cd.isLossless)
        assertFalse(cd.isHiRes)
        assertTrue(hiRes.isHiRes)
    }

    @Test
    fun `dolby atmos is distinct from lossless`() {
        val atmos = DesktopAudioFormat(codec = "eac3", kbps = 768)
        assertTrue(atmos.isDolbyAtmos)
        assertFalse(atmos.isLossless)
    }

    @Test
    fun `a lossless file states what its own header carries`() {
        assertEquals(
            "FLAC · 16-bit · 44.1 kHz · 900 kbps · Stereo",
            DesktopAudioFormat("flac", kbps = 900, sampleRateHz = 44_100, bitDepth = 16, channels = 2).summary,
        )
    }

    @Test
    fun `a lossy file states its own rate under its usual codec name`() {
        assertEquals(
            "AAC · 44.1 kHz · 302 kbps · Stereo",
            DesktopAudioFormat("aac", kbps = 302, sampleRateHz = 44_100, channels = 2).summary,
        )
        // FFmpeg names a codec bare; a container's MIME type reaches the same label.
        assertEquals("AAC · 320 kbps", DesktopAudioFormat("audio/mp4; codecs=\"mp4a.40.2\"", kbps = 320).summary)
    }

    @Test
    fun `nothing known is stated rather than guessed at`() {
        assertEquals("Unknown format", DesktopAudioFormat().summary)
        assertEquals("Opus · 160 kbps", DesktopAudioFormat("opus", kbps = 160).summary)
    }

    @Test
    fun `the stats sheet is handed a MIME type, not a bare codec`() {
        assertEquals("audio/flac", DesktopAudioFormat("flac").mimeType)
        assertEquals("audio/aac", DesktopAudioFormat("mp4a").mimeType)
        assertEquals("audio/raw", DesktopAudioFormat("pcm_s16le").mimeType)
        assertEquals(null, DesktopAudioFormat().mimeType)
    }
}
