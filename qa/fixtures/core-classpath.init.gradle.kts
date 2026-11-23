// Read-only Gradle init script for QA-02/PERF-01.
// Exposes the EXISTING :core runtime classpath to the standalone Java harness so it can
// compile against the real parser entry points. It registers one print-only task and
// changes no source set, dependency, build file or core class.

import java.io.File
import org.gradle.api.tasks.SourceSetContainer

gradle.rootProject {
    gradle.projectsEvaluated {
        val coreProject = project(":core")
        tasks.register("printQaClasspath") {
            group = "qa"
            description = "Writes the resolved :core runtime classpath for the large-EPG QA harness."
            doLast {
                val sourceSets = coreProject.extensions.getByType(SourceSetContainer::class.java)
                val classpath = sourceSets.getByName("main").runtimeClasspath.asPath
                val outputDir = layout.buildDirectory.get().asFile
                outputDir.mkdirs()
                File(outputDir, "qa-core-classpath.txt").writeText(classpath)
                val entries = classpath.split(File.pathSeparator).size
                println("QA core classpath entries: $entries")
            }
        }
    }
}
