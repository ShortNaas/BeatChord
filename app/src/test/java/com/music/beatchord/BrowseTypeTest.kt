package com.music.beatchord

import com.music.beatchord.data.model.BrowseType
import com.music.beatchord.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which kind of page a browse id names.
 *
 * The app's pages are the device's own now, so this is about the `local:` ids
 * the library builds — a folder, an artist, an album, a playlist — and about
 * leaving an id it does not recognise to whatever the caller already thought.
 */
class BrowseTypeTest {

    @Test
    fun `local artist, album and playlist ids resolve to their own kinds`() {
        assertEquals(BrowseType.ARTIST, MainViewModel.browseTypeOf("local:artist:Radiohead"))
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("local:album:In Rainbows"))
        assertEquals(BrowseType.PLAYLIST, MainViewModel.browseTypeOf("local:playlist:favourites"))
    }

    @Test
    fun `a local folder is not one of the collection pages`() {
        assertEquals(BrowseType.OTHER, MainViewModel.browseTypeOf("local:folder:/storage/music"))
        assertEquals(BrowseType.OTHER, MainViewModel.browseTypeOf("local:format:flac"))
    }

    @Test
    fun `preserves fallback when browse ID prefix is unknown`() {
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("unknown_id", fallback = BrowseType.ALBUM))
        assertEquals(BrowseType.ARTIST, MainViewModel.browseTypeOf("custom_source", fallback = BrowseType.ARTIST))
        assertEquals(BrowseType.OTHER, MainViewModel.browseTypeOf("unrecognized_prefix"))
    }
}
