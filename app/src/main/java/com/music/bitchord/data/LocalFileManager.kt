package com.music.bitchord.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object LocalFileManager {

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

        // Update MediaStore content resolver
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

        // Rescan path
        song.localPath?.let { path ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            updated = true
        }

        updated
    }

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
