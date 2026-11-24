package com.devtunasoft.tuneros.ntf06

import java.io.File

/**
 * Locates the app module from the Gradle test working directory and reads
 * production sources as plain text. These regressions intentionally do not
 * declare any synthetic android.* classes: sibling branches ship their own
 * stubs in the android package, and keep ntf06 collision-free by staying a
 * pure source-contract suite.
 */
internal object Ntf06Sources {
    fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val marker = File(dir, "app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt")
            if (marker.isFile) return dir
            dir = dir.parentFile ?: error("Cannot locate app module from ${System.getProperty("user.dir")}")
        }
    }

    fun scheduler(): String =
        File(repoRoot(), "app/src/main/java/com/devtunasoft/tuneros/RecordingScheduler.kt").readText()

    fun manifest(): String = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

    /** Whitespace-insensitive view so formatting changes do not create false failures. */
    fun compact(text: String): String = text.replace(Regex("\\s+"), " ")
}
