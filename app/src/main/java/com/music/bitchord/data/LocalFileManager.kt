package com.music.bitchord.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.FileTagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object LocalFileManager {

    private const val TAG = "BitChord"

    suspend fun deleteSong(context: Context, song: Song): Boolean = withContext(Dispatchers.IO) {
        var deleted = false

        // 1. Delete by file path if available
        song.localPath?.let { path ->
            runCatching {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    deleted = true
                }
            }
        }

        // 2. Delete by content Uri in MediaStore
        song.localUri?.let { uriStr ->
            if (uriStr.startsWith("content://")) {
                runCatching {
                    val uri = Uri.parse(uriStr)
                    val count = context.contentResolver.delete(uri, null, null)
                    if (count > 0) deleted = true
                }
            } else if (uriStr.startsWith("file://")) {
                runCatching {
                    val file = File(Uri.parse(uriStr).path ?: "")
                    if (file.exists() && file.delete()) deleted = true
                }
            }
        }

        // 3. Rescan if file path was known
        song.localPath?.let { path ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }

        deleted
    }

    suspend fun moveSong(context: Context, song: Song, targetDirectory: File): Boolean = withContext(Dispatchers.IO) {
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        val sourceFile = song.localPath?.let { File(it) }?.takeIf { it.exists() }
        val fileName = sourceFile?.name ?: "${song.title}.mp3"
        val destinationFile = File(targetDirectory, fileName)

        var moved = false
        if (sourceFile != null) {
            moved = sourceFile.renameTo(destinationFile)
            if (!moved) {
                // Fallback copy then delete
                runCatching {
                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destinationFile.exists() && destinationFile.length() > 0) {
                        sourceFile.delete()
                        moved = true
                    }
                }
            }
            if (moved) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(sourceFile.absolutePath, destinationFile.absolutePath),
                    null,
                    null,
                )
            }
        } else {
            val uriStr = song.localUri
            if (uriStr != null && uriStr.startsWith("content://")) {
                runCatching {
                    val srcUri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(srcUri)?.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (destinationFile.exists() && destinationFile.length() > 0) {
                    context.contentResolver.delete(srcUri, null, null)
                    moved = true
                }
            }
            if (moved) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destinationFile.absolutePath),
                    null,
                    null,
                )
            }
        }
    }

    moved
    }

    suspend fun editSongTags(
        context: Context,
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
    ): Boolean = withContext(Dispatchers.IO) {
        var updated = false

        // 1. The audio file's own tags first. A MediaStore row is a cache that
        //    any provider rebuild discards and no other player ever reads, so an
        //    edit that stops there is lost the next time the library is rescanned
        //    — and never visible outside this app. Best effort: a file this app
        //    cannot write, a container with no safe rewrite, or a device whose
        //    storage provider refuses the write all leave the row below as the
        //    only record, which is what this did before.
        if (runCatching { rewriteFileTags(context, song, newTitle, newArtist, newAlbum) }
                .onFailure { Log.w(TAG, "could not rewrite tags for ${song.localUri}: ${it.message}") }
                .getOrDefault(false)
        ) {
            updated = true
        }

        // 2. Update MediaStore content resolver
        song.localUri?.let { uriStr ->
            if (uriStr.startsWith("content://")) {
                runCatching {
                    val uri = Uri.parse(uriStr)
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, newTitle)
                        put(MediaStore.Audio.Media.ARTIST, newArtist)
                        put(MediaStore.Audio.Media.ALBUM, newAlbum)
                    }
                    val rows = context.contentResolver.update(uri, values, null, null)
                    if (rows > 0) updated = true
                }
            }
        }

        // 3. Rescan path
        song.localPath?.let { path ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            updated = true
        }

        updated
    }

    /**
     * Rewrites the tags inside the song's own audio file, reading and writing
     * through the uri the row came from.
     *
     * False means "nothing was written", for any reason at all: a remote or
     * missing uri, an extension the tag editor does not handle, a read or a
     * write the provider refused, or a container the tagger did not recognise.
     * The caller falls back to the MediaStore row in every one of those cases,
     * so nothing here throws and nothing here is fatal.
     *
     * The artwork is *not* pulled out and passed back in. The taggers keep the
     * cover blocks they find when no new one is supplied, which preserves every
     * picture in the file — including the ones a single extracted cover would
     * lose — and keeps this path free of any image decoding or fetching.
     */
    private fun rewriteFileTags(
        context: Context,
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
    ): Boolean {
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: song.localPath?.let { Uri.fromFile(File(it)) }
            ?: return false
        val extension = fileExtension(context, song, uri) ?: return false
        if (!FileTagEditor.handles(extension)) return false

        val bytes = readBytes(context, uri) ?: return false
        val tagged = FileTagEditor.retag(bytes, extension, newTitle, newArtist, newAlbum) ?: return false
        return writeBytes(context, uri, tagged)
    }

    /** The container extension of the file behind [uri], from whichever source still names it. */
    private fun fileExtension(context: Context, song: Song, uri: Uri): String? {
        song.localPath?.extensionOf()?.let { return it }
        uri.path?.extensionOf()?.let { return it }
        // A MediaStore uri carries an id rather than a file name; its
        // DISPLAY_NAME column is what still says what the file is called.
        displayName(context, uri)?.extensionOf()?.let { return it }
        return when (context.contentResolver.getType(uri)?.lowercase()?.substringBefore(';')?.trim()) {
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            else -> null
        }
    }

    private fun String.extensionOf(): String? =
        substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme != "content") {
            null
        } else {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }
    }.getOrNull()

    /**
     * The file [uri] names when it names one directly, rather than through a
     * storage provider. A bare path is in here too: rows restored from the
     * listening log carry one, and it is still the same audio file.
     */
    private fun Uri.directFile(): File? = when (scheme) {
        "file", null -> path?.takeIf { it.isNotBlank() }?.let(::File)
        else -> null
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        val file = uri.directFile()
        if (file != null) {
            file.takeIf { it.isFile }?.readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }.onFailure { Log.w(TAG, "could not read $uri for a tag edit: ${it.message}") }.getOrNull()

    private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean = runCatching {
        val file = uri.directFile()
        if (file != null) {
            file.outputStream().use { it.write(bytes) }
        } else {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("could not open $uri for writing")
        }
        true
    }.onFailure { Log.w(TAG, "could not write tags to $uri: ${it.message}") }.getOrDefault(false)

    fun getCommonMusicFolders(context: Context): List<File> {
        val list = mutableListOf<File>()
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (musicDir != null && musicDir.exists()) {
            list.add(musicDir)
            musicDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.take(6)?.let {
                list.addAll(it)
            }
        }
        val beatChordDir = File(musicDir, "BeatChord")
        if (!list.contains(beatChordDir)) {
            list.add(1.coerceAtMost(list.size), beatChordDir)
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null && downloadsDir.exists() && !list.contains(downloadsDir)) {
            list.add(downloadsDir)
        }
        return list.distinctBy { it.absolutePath }
    }
}
