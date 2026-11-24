package com.devtunasoft.tuneros.perm07

import java.io.File

/**
 * Synthetic source-contract helper: reads committed Kotlin sources as plain
 * text. No android.* stubs, device state, media, URLs or credentials, so these
 * regressions stay collision-free with sibling test source sets.
 */
internal object Perm07Sources {
    fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val marker = File(dir, "app/src/main/AndroidManifest.xml")
            if (marker.isFile) return dir
            dir = dir.parentFile ?: error("Cannot locate app module from ${System.getProperty("user.dir")}")
        }
    }

    fun mainActivity(): String =
        File(repoRoot(), "app/src/main/java/com/devtunasoft/tuneros/MainActivity.kt").readText()

    /** Whitespace-insensitive view so formatting changes do not create false failures. */
    fun compact(text: String): String = text.replace(Regex("\\s+"), " ")

    /**
     * One member function's text, from its declaration to the next member
     * declaration. Production members are indented four spaces.
     */
    fun member(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing source member: $signature" }
        val tail = source.substring(start + signature.length)
        val next = Regex("\\r?\\n {4}(?:private|override|internal|public)\\s+(?:fun|val|var|object|class)\\b")
            .find(tail)?.range?.first ?: tail.length
        return compact(source.substring(start, start + signature.length + next))
    }
}
