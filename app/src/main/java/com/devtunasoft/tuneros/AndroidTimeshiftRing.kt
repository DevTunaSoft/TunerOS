package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.TimeshiftRing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A [TimeshiftRing] in one private directory. Segment files are created and deleted only here. The playlist is replaced
 * by renaming a finished temporary file over it, so a player never reads half of a playlist.
 */
internal class AndroidTimeshiftRing(private val directory: File) : TimeshiftRing {
    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("timeshift directory cannot be created")
    }

    override fun create(fileName: String): OutputStream = FileOutputStream(fileOf(fileName))

    override fun delete(fileName: String) {
        val file = fileOf(fileName)
        // A file that survives deletion would hold disk the window no longer accounts for, so that is a failure.
        if (file.exists() && !file.delete()) throw IOException("timeshift segment could not be deleted")
    }

    override fun replacePlaylist(text: String) {
        val temporary = File(directory, TEMPORARY_PLAYLIST_NAME)
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(File(directory, PLAYLIST_NAME))) {
            throw IOException("timeshift playlist could not be replaced")
        }
    }

    private fun fileOf(fileName: String): File {
        // The core names only files it made. Anything that could leave the directory is refused.
        require(fileName.isNotEmpty() && '/' !in fileName && fileName != "." && fileName != "..") {
            "timeshift file name leaves the ring"
        }
        return File(directory, fileName)
    }

    companion object {
        const val PLAYLIST_NAME = "index.m3u8"
        private const val TEMPORARY_PLAYLIST_NAME = "index.m3u8.tmp"
    }
}
