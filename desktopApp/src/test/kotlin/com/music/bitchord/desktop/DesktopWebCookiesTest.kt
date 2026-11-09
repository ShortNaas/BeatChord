package com.music.bitchord.desktop

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWebCookiesTest {
    private val youtube = URI("https://music.youtube.com/")

    private fun cookieHeader() = DesktopWebCookies.get(youtube, emptyMap())["Cookie"].orEmpty().joinToString("; ")

    @Test
    fun `starting over leaves no cookie behind`() {
        DesktopWebCookies.put(youtube, mapOf("Set-Cookie" to listOf("SID=secret; Domain=.youtube.com; Path=/; Secure; HttpOnly")))
        assertEquals("SID=secret", cookieHeader())

        DesktopWebCookies.reset()

        assertTrue(cookieHeader().isEmpty())
    }
}
