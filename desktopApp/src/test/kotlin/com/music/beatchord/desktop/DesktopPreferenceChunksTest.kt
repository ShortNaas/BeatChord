package com.music.beatchord.desktop

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `java.util.prefs` throws when a value passes [Preferences.MAX_VALUE_LENGTH], on whichever thread
 * was saving — which for this app is the UI thread.
 *
 * The store here is a map rather than `Preferences.userRoot()`. Chunking is
 * what the class under test does, and the platform store is the one part it
 * cannot own: the user root is a single XML file behind a file lock, so two test
 * JVMs — or a home directory that cannot be written — fail this for reasons
 * that have nothing to do with what is being tested.
 */
class DesktopPreferenceChunksTest {

    /** A `Preferences` node whose values live in memory; see the class note. */
    private class MemoryPreferences(parent: MemoryPreferences?, name: String) :
        AbstractPreferences(parent, name) {

        private val values = mutableMapOf<String, String>()
        private val children = mutableMapOf<String, MemoryPreferences>()

        override fun putSpi(key: String, value: String) {
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() {
            values.clear()
            children.clear()
        }

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

        override fun childSpi(name: String): AbstractPreferences =
            children.getOrPut(name) { MemoryPreferences(this, name) }

        override fun syncSpi() = Unit

        override fun flushSpi() = Unit
    }

    /** A fresh store per test, rooted so [Preferences.removeNode] is legal. */
    private fun newNode(): Preferences = MemoryPreferences(null, "").node("chunks")

    @Test
    fun aValueLongerThanThePlatformLimitSurvivesARoundTrip() {
        val node = newNode()
        val huge = buildString {
            repeat(Preferences.MAX_VALUE_LENGTH * 3) { append('a' + (it % 26)) }
        }
        assertTrue(huge.length > Preferences.MAX_VALUE_LENGTH)

        // Writing this straight into a preference is what crashed the app.
        DesktopPreferenceChunks.write(node, "entries", huge)

        assertEquals(huge, DesktopPreferenceChunks.read(node, "entries"))
    }

    @Test
    fun aShorterValueLeavesNoTailOfTheLongerOneItReplaced() {
        val node = newNode()
        DesktopPreferenceChunks.write(node, "queue", "x".repeat(Preferences.MAX_VALUE_LENGTH * 2))

        DesktopPreferenceChunks.write(node, "queue", "short")

        assertEquals("short", DesktopPreferenceChunks.read(node, "queue"))
    }

    @Test
    fun aValueWrittenBeforeChunkingIsStillReadable() {
        val node = newNode()
        node.put("legacy", "written by an older build")

        assertEquals("written by an older build", DesktopPreferenceChunks.read(node, "legacy"))
    }

    @Test
    fun anUnwrittenKeyReadsAsNothing() {
        assertNull(DesktopPreferenceChunks.read(newNode(), "never-written"))
    }

    @Test
    fun anEmptyValueIsStoredRatherThanLost() {
        val node = newNode()
        DesktopPreferenceChunks.write(node, "empty", "")

        assertEquals("", DesktopPreferenceChunks.read(node, "empty"))
    }

    @Test
    fun `characters xml cannot carry never reach the store`() {
        val node = newNode()
        // 0x1f is the one that was actually observed.
        DesktopPreferenceChunks.write(node, "k", "before\u001Fafter\u0000end")

        val stored = DesktopPreferenceChunks.read(node, "k")
        assertEquals("beforeafterend", stored)
        assertTrue(stored!!.none { it < ' ' })

        // Tab, newline and carriage return are legal XML and survive.
        DesktopPreferenceChunks.write(node, "k2", "a\tb\nc\rd")
        assertEquals("a\tb\nc\rd", DesktopPreferenceChunks.read(node, "k2"))
    }
}
