package com.devtunasoft.tuneros.upgraderearm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * REL-01 / DVR-04 deterministic JVM regression coverage for upgrade-time rearm.
 *
 * Every fixture is synthetic: these tests only parse the module manifest and
 * read the receiver source. No device database, provider URL, credential,
 * recording or media file is opened.
 */
class UpgradePackageReplacedRearmTest {
    private data class Receiver(val name: String, val actions: Set<String>, val exported: Boolean)

    @Test
    fun packageReplacementRegistersADvrRearmReceiver() {
        val upgrade = manifestReceivers().firstOrNull { MY_PACKAGE_REPLACED in it.actions }
        assertNotNull(
            "Upgrade defect: no receiver is registered for $MY_PACKAGE_REPLACED; " +
                "pending DVR schedules are rearmed only if the user relaunches the app",
            upgrade,
        )
        assertFalse("upgrade receiver must not be exported", upgrade!!.exported)
        assertFalse(
            "boot and upgrade rearm must stay separate so boot does not rearm twice",
            BOOT_COMPLETED in upgrade.actions,
        )
        assertTrue(
            "the upgrade receiver must delegate to the idempotent scheduler rearm",
            sourceFor(upgrade!!.name).readText().contains("RecordingScheduler.rearmPending"),
        )
    }

    @Test
    fun upgradeReceiverChecksTheActionBeforeRearming() {
        val upgrade = manifestReceivers().firstOrNull { MY_PACKAGE_REPLACED in it.actions }
            ?: throw AssertionError("No receiver registered for $MY_PACKAGE_REPLACED")
        val source = sourceFor(upgrade.name).readText()
        val guard = source.indexOf("ACTION_MY_PACKAGE_REPLACED")
        val rearm = source.indexOf("RecordingScheduler.rearmPending(")
        assertTrue("receiver must name the package-replaced action", guard >= 0)
        assertTrue("receiver must rearm only after the action check", rearm > guard)
    }

    @Test
    fun upgradeReceiverDispatchesExactlyOneRearmPerBroadcast() {
        val upgrade = manifestReceivers().firstOrNull { MY_PACKAGE_REPLACED in it.actions }
            ?: throw AssertionError("No receiver registered for $MY_PACKAGE_REPLACED")
        val source = sourceFor(upgrade.name).readText()
        val dispatches = Regex("RecordingScheduler\\.rearmPending\\(").findAll(source).count()
        assertEquals("one rearm dispatch per package-replaced broadcast", 1, dispatches)
        assertTrue("rearm must use the application context", source.contains("context.applicationContext"))
        assertTrue("the async broadcast must always be finished", source.contains("pending.finish()"))
    }

    @Test
    fun scheduleUpgradeNeverDeletesDurableRows() {
        // Audit gap 2: at schema version 1 onUpgrade is correctly a no-op, and
        // C2's DVR-03 suite covers row survival across a version change. This
        // guard prevents any future upgrade path in this store from dropping
        // the durable schedule file.
        val store = resolveFile("src/main/java/com/devtunasoft/tuneros/RecordingScheduleStore.kt").readText()
        assertFalse("upgrade must not drop the schedules table", store.contains("DROP TABLE", true))
        assertFalse("upgrade must not delete stored rows", store.contains("DELETE FROM", true))
    }

    private fun manifestReceivers(): List<Receiver> {
        val manifest = resolveFile("src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("receiver")
        return (0 until nodes.length).map { index ->
            val node = nodes.item(index)
            val element = node as org.w3c.dom.Element
            val filters = element.getElementsByTagName("intent-filter")
            val actions = LinkedHashSet<String>()
            for (i in 0 until filters.length) {
                val actionNodes = filters.item(i).childNodes
                for (j in 0 until actionNodes.length) {
                    val child = actionNodes.item(j)
                    if (child.nodeName == "action" && child is org.w3c.dom.Element) {
                        actions += child.getAttributeNS(ANDROID_NS, "name") ?: ""
                    }
                }
            }
            Receiver(
                name = element.getAttributeNS(ANDROID_NS, "name"),
                actions = actions,
                exported = element.getAttributeNS(ANDROID_NS, "exported") == "true",
            )
        }
    }

    private fun sourceFor(receiverName: String): File {
        val srcDir = File(resolveFile("src/main/AndroidManifest.xml").parentFile, "java")
        val className = receiverName.removePrefix(".").substringAfterLast(".")
        return srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("class $className") }
            .firstOrNull()
            ?: error("Cannot find source for receiver $receiverName under $srcDir")
    }

    private fun resolveFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            if (dir == null) return@repeat
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val moduleCandidate = File(dir, "app/$relative")
            if (moduleCandidate.isFile) return moduleCandidate
            dir = dir.parentFile
        }
        error("Cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
        const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    }
}
