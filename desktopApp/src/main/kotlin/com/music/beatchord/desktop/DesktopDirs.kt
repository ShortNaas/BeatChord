package com.music.beatchord.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where this app keeps the little it writes outside its own preferences.
 *
 * The desktop has no `context.cacheDir`, so it follows the XDG base-directory convention the rest
 * of a Linux profile already uses, and macOS and Windows paths fall back to the same place under
 * the user's home.
 */
internal object DesktopDirs {

    /** Created on first use. */
    val cacheDir: Path by lazy {
        val state = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.cache"
        Path.of(state, "beatchord").also { runCatching { Files.createDirectories(it) } }
    }
}
