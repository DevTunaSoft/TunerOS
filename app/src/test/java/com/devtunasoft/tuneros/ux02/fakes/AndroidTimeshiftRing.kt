package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.TimeshiftRing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Test-only stand-in for AndroidTimeshiftRing. It implements the same contract with a direct
 * file write: java.io.File.renameTo cannot replace an existing file on Windows, while Android's
 * rename can. This keeps the JVM test about TimeshiftRecorder's cancel/cleanup logic, not the
 * host filesystem's rename semantics. Never compiled into the app.
 */
internal class AndroidTimeshiftRing(private val directory: File) : TimeshiftRing {
    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("timeshift directory cannot be created")
    }

    override fun create(fileName: String): OutputStream = FileOutputStream(fileOf(fileName))

    override fun delete(fileName: String) {
        val file = fileOf(fileName)
        if (file.exists() && !file.delete()) throw IOException("timeshift segment could not be deleted")
    }

    override fun replacePlaylist(text: String) {
        fileOf(PLAYLIST_NAME).writeText(text, Charsets.UTF_8)
    }

    private fun fileOf(fileName: String): File {
        require(fileName.isNotEmpty() && '/' !in fileName && fileName != "." && fileName != "..") {
            "timeshift file name leaves the ring"
        }
        return File(directory, fileName)
    }

    companion object {
        const val PLAYLIST_NAME = "index.m3u8"
    }
}
