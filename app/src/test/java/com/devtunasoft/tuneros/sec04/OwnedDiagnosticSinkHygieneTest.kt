package com.devtunasoft.tuneros.sec04

import androidx.media3.common.PlaybackException
import com.devtunasoft.tuneros.GuideFocusChecks
import com.devtunasoft.tuneros.MainActivity
import com.devtunasoft.tuneros.OnDeviceGuideStress
import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.StreamEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field

/** JVM unit tests cannot run Android constructors, so allocate inert objects and set only the fields read here. */
private val unsafe: Any by lazy {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    field.isAccessible = true
    field.get(null)
}

private fun uAllocate(clazz: Class<*>): Any =
    unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)

private fun uOffset(field: Field): Long =
    unsafe.javaClass.getMethod("objectFieldOffset", Field::class.java).invoke(unsafe, field) as Long

private fun uPutInt(target: Any, field: Field, value: Int) {
    unsafe.javaClass.getMethod(
        "putInt", Object::class.java, java.lang.Long.TYPE, java.lang.Integer.TYPE,
    ).invoke(unsafe, target, uOffset(field), value)
}

private fun uPutObject(target: Any, field: Field, value: Any?) {
    unsafe.javaClass.getMethod(
        "putObject", Object::class.java, java.lang.Long.TYPE, Object::class.java,
    ).invoke(unsafe, target, uOffset(field), value)
}

private fun fakeError(code: Int, message: String, causeMessage: String): PlaybackException {
    val error = uAllocate(PlaybackException::class.java) as PlaybackException
    uPutInt(error, PlaybackException::class.java.getDeclaredField("errorCode"), code)
    uPutObject(error, Throwable::class.java.getDeclaredField("detailMessage"), message)
    val cause = uAllocate(IllegalStateException::class.java)
    uPutObject(cause, Throwable::class.java.getDeclaredField("detailMessage"), causeMessage)
    uPutObject(error, Throwable::class.java.getDeclaredField("cause"), cause)
    return error
}

/**
 * SEC-04 negative regressions for the remaining owned diagnostic sinks.
 *
 * Every marker below is synthetic: no real provider URL, credential, private path, DRM key, PIN or
 * recording payload is present. The tests cover the text MainActivity actually shares through the
 * Stream Doctor report, the failure message behind that report, and the bounded synthetic fixtures
 * used by the two guide diagnostics.
 */
class OwnedDiagnosticSinkHygieneTest {
    companion object {
        private const val FAKE_URL = "https://example.invalid/qa?token=SYNTHETIC_TOKEN"
        private const val FAKE_PATH = "/data/user/0/com.devtunasoft.tuneros/files/SYNTHETIC_PRIVATE"
        private const val FAKE_DRM = "SYNTHETIC_DRM_KEY_9f1c"
        private const val FAKE_PIN = "SYNTHETIC_PIN_7381"
        private const val FAKE_PAYLOAD = "SYNTHETIC_RECORDING_PAYLOAD"
        private val MARKERS = listOf(FAKE_URL, FAKE_PATH, FAKE_DRM, FAKE_PIN, FAKE_PAYLOAD)
        private const val SHARE_REPORT_LIMIT = 400
        private val URL_LITERAL = Regex("https?://[^\\s\"')]+")
    }

    private fun assertNoMarkers(text: String) {
        for (marker in MARKERS) {
            assertFalse("shareable diagnostic leaked $marker", text.contains(marker, ignoreCase = true))
        }
    }

    /** Where AGP places the module root for a JVM unit test; walk up so the test also works from the repo root. */
    private fun ownedSource(name: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val file = File(dir, "app/src/main/java/com/devtunasoft/tuneros/$name")
            if (file.isFile) return file.readText()
            dir = dir.parentFile ?: error("no parent above $dir")
        }
        error("owned source $name not found above ${System.getProperty("user.dir")}")
    }

    private fun assertOnlySyntheticLiterals(source: String) {
        URL_LITERAL.findAll(source).forEach { match ->
            assertTrue(
                "non-synthetic URL literal in an owned diagnostic: ${match.value}",
                match.value.startsWith("https://example.invalid/"),
            )
        }
        assertFalse("private file path literal", source.contains("/data/data/") || source.contains("/data/user/0/"))
        assertFalse("credential query literal", source.contains("token=") || source.contains("Bearer "))
        assertFalse("DRM key literal", source.contains("DRM_KEY"))
        assertFalse("synthetic leak marker left in production source", source.contains("SYNTHETIC_"))
    }

    private fun intField(clazz: Class<*>, name: String): Int =
        clazz.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    private fun longField(clazz: Class<*>, name: String): Long =
        clazz.getDeclaredField(name).apply { isAccessible = true }.getLong(null)

    @Test
    fun streamDoctorSharePayloadIsSanitizedBoundedAndDeterministic() {
        val hostile = StreamEvidence(
            httpStatus = 999,
            contentType = "$FAKE_URL $FAKE_PATH $FAKE_DRM $FAKE_PIN $FAKE_PAYLOAD",
            transport = null,
        )
        val text = StreamDoctor.report(2004, hostile)
        val lines = text.replace("\r\n", "\n").trimEnd('\n').lines()

        assertEquals("lines=$lines", 4, lines.size)
        assertEquals("TunerOS stream report", lines[0])
        assertEquals("playback error code: 2004", lines[1])
        assertEquals("issue: Stream server rejected playback", lines[2])
        assertEquals("next step: Check provider access and the stream address", lines[3])
        assertNoMarkers(text)
        assertTrue("share report exceeded $SHARE_REPORT_LIMIT chars: ${text.length}", text.length <= SHARE_REPORT_LIMIT)
        assertEquals("share report must be deterministic", text, StreamDoctor.report(2004, hostile))

        // A well-formed media type keeps only type/subtype; query parameters are dropped.
        val withParameter = StreamDoctor.report(
            2003,
            StreamEvidence(httpStatus = 200, contentType = "video/mp4; token=$FAKE_PIN"),
        )
        assertNoMarkers(withParameter)
        assertTrue(withParameter.contains("detail: HTTP 200; content type video/mp4"))
        assertEquals(withParameter, StreamDoctor.report(2003, StreamEvidence(200, "video/mp4; token=$FAKE_PIN")))

        // A hostile unbounded media type cannot grow the shareable report.
        val oversized = StreamDoctor.report(2003, StreamEvidence(200, FAKE_URL.repeat(500), null))
        assertNoMarkers(oversized)
        assertTrue("oversized evidence grew the report", oversized.length <= SHARE_REPORT_LIMIT)
        assertEquals(5, oversized.replace("\r\n", "\n").trimEnd('\n').lines().size)
        assertTrue(oversized.contains("detail: HTTP 200"))
    }

    @Test
    fun mainActivityFailureMessageIgnoresExceptionTextAndStaysBounded() {
        // No constructor call: this private formatter is instance-independent, and a JVM unit test
        // cannot build an Activity. Unsafe allocates the object without running Android stubs.
        val activity = uAllocate(MainActivity::class.java) as MainActivity
        val formatter = MainActivity::class.java.getDeclaredMethod(
            "playbackFailureMessage",
            PlaybackException::class.java,
            Class.forName("com.devtunasoft.tuneros.FirstResponseRecorder"),
        )
        formatter.isAccessible = true
        val error = fakeError(
            2004,
            "$FAKE_URL $FAKE_PIN",
            "$FAKE_PATH $FAKE_DRM $FAKE_PAYLOAD",
        )
        val message = formatter.invoke(activity, error, null) as String

        assertNoMarkers(message)
        assertFalse("exception message reached the user-visible diagnostic", message.contains("SYNTHETIC", ignoreCase = true))
        assertEquals(
            "Stream Doctor (2004): Stream server rejected playback. " +
                "Check provider access and the stream address",
            message,
        )
        assertTrue("failure message is unbounded", message.length <= SHARE_REPORT_LIMIT)
        assertEquals(message, formatter.invoke(activity, error, null))
    }

    @Test
    fun guideStressFixtureAndBudgetsStayBoundedAndSynthetic() {
        val stress = OnDeviceGuideStress::class.java
        val channels = intField(stress, "CHANNEL_COUNT")
        val programsPerChannel = intField(stress, "PROGRAMS_PER_CHANNEL")
        val elapsed = longField(stress, "MAX_ELAPSED_MS")
        val heap = longField(stress, "MAX_HEAP_GROWTH_BYTES")

        assertTrue("channel fixture grew: $channels", channels in 1..500)
        assertTrue("programme fixture grew: $programsPerChannel", programsPerChannel in 1..200)
        assertTrue("total fixture grew: ${channels * programsPerChannel}", channels * programsPerChannel <= 50_000)
        assertTrue("elapsed budget grew: $elapsed", elapsed in 1L..30_000L)
        assertTrue("heap budget grew: $heap", heap in 1L..(128L * 1024 * 1024))
        assertOnlySyntheticLiterals(ownedSource("OnDeviceGuideStress.kt"))
    }

    @Test
    fun guideFocusCheckLabelsAreFixedSyntheticMarkers() {
        val focus = GuideFocusChecks::class.java
        val companionClass = Class.forName("com.devtunasoft.tuneros.GuideFocusChecks\$Companion")
        val label = { name: String ->
            // Kotlin const vals live on the outer class; non-const companion values live on Companion.
            val field = runCatching { focus.getDeclaredField(name) }
                .getOrElse { companionClass.getDeclaredField(name) }
            field.isAccessible = true
            field.get(null) as String
        }
        assertEquals("Guide Focus Alpha", label("ALPHA"))
        assertEquals("Guide Focus Bravo", label("BRAVO"))
        assertEquals("First show", label("FIRST_TITLE"))
        assertEquals("Second show", label("SECOND_TITLE"))
        val checkIds = listOf("CELL_CHECK", "PROGRAMME_CHECK", "EDGE_CHECK", "ROWS_CHECK").map(label)
        assertEquals(
            listOf(
                "guide-focus-channel-cell-takes-focus",
                "guide-focus-right-left-moves-programme",
                "guide-focus-left-edge-shifts-window",
                "guide-focus-up-down-leaves-timeline",
            ),
            checkIds,
        )
        checkIds.forEach { assertNoMarkers(it) }
        assertTrue("unexpected guide check count", checkIds.size == 4)
        assertTrue("check API changed", focus.declaredMethods.any { it.name == "run" })
        assertOnlySyntheticLiterals(ownedSource("GuideFocusChecks.kt"))
    }

    @Test
    fun ownedSharePathSourcesContainNoPrivateLiterals() {
        assertOnlySyntheticLiterals(ownedSource("MainActivity.kt"))
        assertOnlySyntheticLiterals(ownedSource("OnDeviceGuideStress.kt"))
        assertOnlySyntheticLiterals(ownedSource("GuideFocusChecks.kt"))
    }
}
