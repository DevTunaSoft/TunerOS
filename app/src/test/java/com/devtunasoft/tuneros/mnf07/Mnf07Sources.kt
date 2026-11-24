package com.devtunasoft.tuneros.mnf07

import java.io.File

/**
 * Locates the app module from the Gradle test working directory and reads
 * production sources as plain text. These regressions are deliberately pure
 * source-contract checks: no synthetic android.* classes, no device state and
 * no media/credential fixtures, so they stay collision-free with sibling
 * branches that ship their own stubs.
 */
internal object Mnf07Sources {
    fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val marker = File(dir, "app/src/main/AndroidManifest.xml")
            if (marker.isFile) return dir
            dir = dir.parentFile ?: error("Cannot locate app module from ${System.getProperty("user.dir")}")
        }
    }

    fun manifest(): String = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

    fun service(): String =
        File(repoRoot(), "app/src/main/java/com/devtunasoft/tuneros/DvrRecordingService.kt").readText()

    fun buildGradle(): String = File(repoRoot(), "app/build.gradle.kts").readText()

    /** Receivers are nested inside their scheduler source, so scan declarations, not filenames. */
    fun hasClassDeclaration(name: String): Boolean {
        val root = File(repoRoot(), "app/src/main/java")
        val needle = "class $name "
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.any { it.readText().contains(needle) }
    }

    /** Whitespace-insensitive view so formatting changes do not create false failures. */
    fun compact(text: String): String = text.replace(Regex("\\s+"), " ")
}
