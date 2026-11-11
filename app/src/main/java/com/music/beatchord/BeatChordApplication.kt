package com.music.beatchord

import android.app.Application
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.music.beatchord.data.DebugLog
import com.music.beatchord.data.lyrics.LyricsTranslation
import com.music.beatchord.data.settings.AppSettings
import com.music.beatchord.data.settings.SearchHistory
import com.music.beatchord.data.stats.ArtistFacts
import com.music.beatchord.data.stats.ListeningStats
import com.music.beatchord.playback.LastPlayed
import com.music.beatchord.ui.player.AndroidPlayerHost
import com.music.beatchord.ui.player.PlayerPlatform

class BeatChordApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // The player is drawn by the shared UI module; this is what it reads
        // underneath — settings and outputs.
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.music.beatchord.ui.AppUi.install(com.music.beatchord.ui.AndroidAppUiHost)
        // The shared data layer (lyrics, artist facts) logs to logcat on debug
        // builds only, as the app's own DebugLog always has.
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        }
        // The per-app language picker. Full tag (zh-Hant / zh-Hans preserved)
        // so the shared UI resolves the language the listener actually chose.
        migrateScriptOnlyAppLocale()
        LyricsTranslation.cacheDir = cacheDir
        AppSettings.init(this)
        // Before anything plays a local file: an output that cannot be opened
        // losslessly is gated on this, and the gate reads "no" until it has
        // looked.
        com.music.beatchord.playback.audio.LosslessOutput.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        // The device's own listening record. Opened here rather than in
        // PlaybackService because the Replay page reads it from the UI side and
        // both live in this process — one owner, one directory.
        ListeningStats.init(this)
        // After AppSettings, whose switch decides whether half of it runs.
        ArtistFacts.init(this)
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in the image cache — survives it
        // untouched. Wipe it on the first launch of a higher versionCode so a
        // format or key change between builds can't serve stale or mismatched
        // bytes from a cache the new code didn't write.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .build()

    companion object {
        /**
         * One-time move from region-less script tags to regional ones.
         *
         * Early builds of the Simplified/Traditional picker stored `zh-Hant` /
         * `zh-Hans`. Android Views resolve those (script qualifiers), but the
         * shared Compose resources only match language + region — so Settings
         * read Traditional while the player read Simplified. Regional tags
         * match on both systems. Bare `zh` is left alone: it historically
         * meant Simplified.
         */
        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }
    }
}
