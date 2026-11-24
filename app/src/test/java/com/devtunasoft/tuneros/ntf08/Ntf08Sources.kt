package com.devtunasoft.tuneros.ntf08

import java.io.File

/**
 * Reads the production source as plain text for the NTF-08 source contracts.
 * Deliberately declares no android.* classes: sibling test suites ship their
 * own synthetic framework declarations and this suite must stay collision-free.
 * No device state, dialog, URL, credential, media or permission grant is used.
 */
internal object Ntf08Sources {
    fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ". ").absoluteFile
        while (true) {
            val marker = File(dir, "app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt")
            if (marker.isFile) return dir
            dir = dir.parentFile ?: error("Cannot locate app module from ${System.getProperty("user.dir")}")
        }
    }

    fun scheduler(): String =
        File(repoRoot(), "app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt").readText()

    /** Whitespace-insensitive view so formatting changes do not create false failures. */
    fun compact(text: String): String = text.replace(Regex("\\s+"), " ")

    /** Brace-balanced body of the first function whose declaration starts with [signature]. */
    fun member(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing production member: $signature" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "No function body for: $signature" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        error("Unbalanced function body for: $signature")
    }
}
