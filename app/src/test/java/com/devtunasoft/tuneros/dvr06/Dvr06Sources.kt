package com.devtunasoft.tuneros.dvr06

import java.io.File

/**
 * Reads the owned production source as plain text for DVR-06 source contracts.
 * Declares no android.* classes: sibling suites ship their own synthetic framework
 * declarations and this suite must stay collision-free. Only synthetic source text
 * is used - no device, URL, credential, PIN, media or real recording path.
 */
internal object Dvr06Sources {
    fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val marker = File(dir, "app/src/main/java/com/devtunasoft/tuneros/DvrRecordingService.kt")
            if (marker.isFile) return dir
            dir = dir.parentFile ?: error("Cannot locate the app module from ${System.getProperty("user.dir")}")
        }
    }

    fun service(): String =
        File(repoRoot(), "app/src/main/java/com/devtunasoft/tuneros/DvrRecordingService.kt").readText()

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
