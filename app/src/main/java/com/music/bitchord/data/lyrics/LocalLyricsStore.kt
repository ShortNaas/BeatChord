package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class CachedLyrics(
    val lines: List<LyricLine>,
    val source: LyricsSource?,
    val fetchedTimestampMs: Long,
)

object LocalLyricsStore {

    private const val DIRECTORY_NAME = "offline_lyrics"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun keyFor(videoId: String, title: String, artist: String, localUri: String?): String {
        val cleanTitle = title.lowercase(Locale.ROOT).trim().replace(Regex("[^a-z0-9]"), "_")
        val cleanArtist = artist.lowercase(Locale.ROOT).trim().replace(Regex("[^a-z0-9]"), "_")
        val cleanId = (localUri ?: videoId).takeLast(16).replace(Regex("[^a-z0-9]"), "_")
        return "${cleanArtist}__${cleanTitle}__$cleanId"
    }

    suspend fun get(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        localUri: String? = null,
        localPath: String? = null,
    ): CachedLyrics? = withContext(Dispatchers.IO) {
        val key = keyFor(videoId, title, artist, localUri)
        val file = File(getStorageDir(context), "$key.json")
        if (!file.exists()) return@withContext sidecarLyrics(localPath)

        runCatching {
            val json = JSONObject(file.readText())
            val timestamp = json.optLong("timestamp", 0L)
            val sourceName = json.optString("source", null)
            val source = sourceName?.let { runCatching { LyricsSource.valueOf(it) }.getOrNull() }
            val linesArray = json.getJSONArray("lines")
            val lines = mutableListOf<LyricLine>()
            for (i in 0 until linesArray.length()) {
                val obj = linesArray.getJSONObject(i)
                val timeMs = if (obj.has("timeMs")) obj.getLong("timeMs") else obj.optLong("startMs", 0L)
                val sungUntilMs = if (obj.has("sungUntilMs")) {
                    obj.optLong("sungUntilMs", -1L).takeIf { it >= 0 }
                } else {
                    obj.optLong("endMs", -1L).takeIf { it >= 0 }
                }
                lines.add(
                    LyricLine(
                        timeMs = timeMs,
                        text = obj.getString("text"),
                        sungUntilMs = sungUntilMs,
                    )
                )
            }
            if (lines.isNotEmpty()) {
                CachedLyrics(lines, source, timestamp)
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * The `<song>.lrc` written beside the audio file, read back when the JSON
     * cache is gone. Second half of the same bargain [save] makes: the pair is
     * stored twice so a cleared cache still never costs a network round trip.
     */
    internal fun sidecarLyrics(localPath: String?): CachedLyrics? {
        val audio = localPath?.let(::File) ?: return null
        val parent = audio.parentFile ?: return null
        val lrc = File(parent, "${audio.nameWithoutExtension}.lrc")
        if (!lrc.isFile) return null
        val lines = runCatching { ProviderLyrics.parse(lrc.readText()) }.getOrNull().orEmpty()
        return lines.takeIf { it.isNotEmpty() }?.let {
            CachedLyrics(it, null, lrc.lastModified())
        }
    }

    suspend fun save(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        localUri: String? = null,
        localPath: String? = null,
        lines: List<LyricLine>,
        source: LyricsSource? = null,
    ): Unit = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext
        val key = keyFor(videoId, title, artist, localUri)
        val timestamp = System.currentTimeMillis()

        runCatching {
            val json = JSONObject()
            json.put("timestamp", timestamp)
            json.put("source", source?.name)
            val linesArray = JSONArray()
            lines.forEach { line ->
                val obj = JSONObject()
                obj.put("text", line.text)
                obj.put("timeMs", line.timeMs)
                line.sungUntilMs?.let { obj.put("sungUntilMs", it) }
                linesArray.put(obj)
            }
            json.put("lines", linesArray)

            val file = File(getStorageDir(context), "$key.json")
            file.writeText(json.toString())
        }

        // Also save sidecar .lrc beside the audio file if local file is writable
        runCatching {
            val audioPath = localPath ?: localUri?.removePrefix("file://")
            if (!audioPath.isNullOrBlank() && audioPath.startsWith("/")) {
                val audioFile = File(audioPath)
                if (audioFile.exists() && audioFile.parentFile != null && audioFile.parentFile.canWrite()) {
                    val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                    val lrcContent = buildString {
                        appendLine("[ti:$title]")
                        appendLine("[ar:$artist]")
                        appendLine("[by:BeatChord]")
                        lines.forEach { line ->
                            val totalSec = line.timeMs / 1000
                            val min = totalSec / 60
                            val sec = totalSec % 60
                            val hundredths = (line.timeMs % 1000) / 10
                            appendLine(String.format(Locale.ROOT, "[%02d:%02d.%02d]%s", min, sec, hundredths, line.text))
                        }
                    }
                    lrcFile.writeText(lrcContent)
                }
            }
        }
    }
}
