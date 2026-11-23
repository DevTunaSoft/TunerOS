package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.xml.sax.SAXException

/**
 * Adversarial boundary coverage for the byte-level XMLTV prolog gate. Neighbouring tests already cover the safe grammar,
 * canonical and foreign DOCTYPEs, internal subsets, seeded document fuzzing and SAX security; these cases cover only the
 * gaps: the exact byte cap, truncation, encoding declarations at the byte seam, attribute floods, bounded reads and
 * retained state, repeated cycles, and constant diagnostics.
 *
 * "fail closed" here means a refusal with SAXException and no accepted guide, not a particular message body beyond the
 * documented cap and DOCTYPE contracts. Every URL and token below is a fixed synthetic marker on the reserved .invalid
 * domain; no provider address or credential is present.
 */
class XmltvPrologAdversarialTest {
    private val canonical = """<!DOCTYPE tv SYSTEM "xmltv.dtd">"""
    private val root = "<tv>"
    private val guideBody = """<tv><channel id="c"><display-name>One</display-name></channel></tv>"""
    private val urlMarker = "http://prolog.invalid/guide?token=SYNTHTOKEN"
    private val secretMarker = "SYNTHTOKEN"

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun assertNoMarkers(error: Throwable) {
        val diagnostics = buildString {
            append(error.message.orEmpty())
            error.suppressed.forEach { append(it.message.orEmpty()) }
        }
        assertTrue(!diagnostics.contains(urlMarker) && !diagnostics.contains(secretMarker),
            "diagnostic echoed a synthetic marker")
    }

    private fun refuseProlog(input: ByteArray): SAXException {
        val error = assertFailsWith<SAXException> { GuideParser.parse(ByteArrayInputStream(input)) }
        assertNoMarkers(error)
        assertTrue(error.message?.isNotBlank() == true, "refusal had no diagnostic")
        return error
    }

    /** Counts every byte the prolog gate actually consumes, so an over-read cannot hide behind a correct verdict. */
    private class CountingInputStream(payload: ByteArray) : InputStream() {
        private val source = ByteArrayInputStream(payload)
        var bytesRead = 0
            private set

        override fun read(): Int {
            val byte = source.read()
            if (byte >= 0) bytesRead++
            return byte
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(target, offset, length)
            if (count > 0) bytesRead += count
            return count
        }

        override fun available(): Int = source.available()
        override fun markSupported(): Boolean = source.markSupported()
        override fun mark(readLimit: Int) = source.mark(readLimit)
        override fun reset() = source.reset()
    }

    @Test fun prologCapIsExactAndTheGateNeverReadsPastIt() {
        val exactProlog = "<!--" + "x".repeat(XmltvProlog.MAX_BYTES - 7) + "-->"
        assertEquals(XmltvProlog.MAX_BYTES, exactProlog.length)
        val accepted = CountingInputStream(bytes(exactProlog + root))
        XmltvProlog.verify(accepted)
        // Only the root's '<' and its first name byte may pass the cap; both fit the caller's +8 mark slack.
        assertEquals(XmltvProlog.MAX_BYTES + 2, accepted.bytesRead)

        val spaces = " ".repeat(XmltvProlog.MAX_BYTES + 8) + root
        val spaceError = assertFailsWith<SAXException> { XmltvProlog.verify(ByteArrayInputStream(bytes(spaces))) }
        assertTrue(spaceError.message?.contains("prolog exceeds") == true, "whitespace past the cap was accepted")

        val overCap = "<!--" + "x".repeat(XmltvProlog.MAX_BYTES - 6) + "-->"
        assertEquals(XmltvProlog.MAX_BYTES + 1, overCap.length)
        val rejected = CountingInputStream(bytes(overCap + root))
        val error = assertFailsWith<SAXException> { XmltvProlog.verify(rejected) }
        assertTrue(error.message?.contains("prolog exceeds") == true, "wrong refusal for an over-cap prolog")
        assertNoMarkers(error)
        assertEquals(XmltvProlog.MAX_BYTES, rejected.bytesRead)
    }

    @Test fun rootAttributeFloodIsNotScannedByThePrologGate() {
        val prefix = " ".repeat(XmltvProlog.MAX_BYTES - 2)
        val attributes = buildString { repeat(4_096) { append(" a").append(it).append("=\"v\"") } }
        val payload = prefix + "<tv" + attributes + ">"
        val counted = CountingInputStream(bytes(payload))
        XmltvProlog.verify(counted)
        assertEquals(XmltvProlog.MAX_BYTES, counted.bytesRead)
        val guide = GuideParser.parse(ByteArrayInputStream(bytes("<tv$attributes></tv>")))
        assertEquals(0, guide.programs.size)
    }

    @Test fun truncatedPrologsAreRefusedWithSAXExceptions() {
        val inputs = listOf(
            byteArrayOf(),
            byteArrayOf(0xEF.toByte()),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte()),
            byteArrayOf(0xFE.toByte()),
            byteArrayOf(0xFF.toByte()),
            bytes("<?xml"),
            bytes("""<?xml version="1.0""""),
            bytes("<?pi data"),
            bytes("<!--unterminated"),
            bytes("<!"),
            bytes("<!DOCTYPE"),
            bytes("""<!DOCTYPE tv SYSTEM "xmltv.dtd""""),
            bytes(canonical),
            bytes("<"),
        )
        inputs.forEach { refuseProlog(it) }
    }

    @Test fun encodingDeclarationsCannotBypassTheByteGate() {
        assertEquals(
            "One",
            GuideParser.parse(
                ByteArrayInputStream(bytes("""<?xml version="1.0" encoding="US-ASCII"?>""" + "\n" + canonical + guideBody)),
            ).channelNames["c"],
        )
        assertEquals(
            "One",
            GuideParser.parse(
                ByteArrayInputStream(bytes("\uFEFF" + """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + canonical + guideBody)),
            ).channelNames["c"],
        )
        refuseProlog(bytes("""<?xml version="1.0" encoding="UTF-16"?>""" + "\n" + canonical + guideBody))
        // A processing instruction containing declaration-like text is skipped here and never becomes a DTD.
        assertEquals(
            "One",
            GuideParser.parse(
                ByteArrayInputStream(bytes("<?pi encoding=\"UTF-16\"?>\n" + canonical + guideBody)),
            ).channelNames["c"],
        )
    }

    @Test fun hostileDeclarationTextNeverLeaksIntoDiagnostics() {
        val harmless = listOf("<!--$urlMarker-->", "<?pi $urlMarker?>")
        harmless.forEach { prefix ->
            XmltvProlog.verify(ByteArrayInputStream(bytes(prefix + canonical + root)))
            val guide = GuideParser.parse(ByteArrayInputStream(bytes(prefix + canonical + guideBody)))
            assertTrue(guide.warnings.none { it.contains(urlMarker) || it.contains(secretMarker) },
                "guide diagnostic echoed a synthetic marker")
        }
        val hostile = """<!DOCTYPE tv SYSTEM "$urlMarker" [<!ENTITY x "$secretMarker">]>"""
        val error = assertFailsWith<SAXException> {
            XmltvProlog.verify(ByteArrayInputStream(bytes(hostile + canonical + root)))
        }
        assertNoMarkers(error)
        refuseProlog(bytes(hostile + canonical + guideBody))
    }

    @Test fun absurdDoctypeAttributeCountsFailClosed() {
        val attrs = buildString { repeat(128) { append(" attribute").append(it).append("=\"").append(secretMarker).append("\"") } }
        val underCap = """<!DOCTYPE tv SYSTEM "xmltv.dtd"$attrs>"""
        assertTrue(underCap.length < XmltvProlog.MAX_BYTES)
        val canonicalError = assertFailsWith<SAXException> {
            XmltvProlog.verify(ByteArrayInputStream(bytes(underCap + root)))
        }
        assertTrue(canonicalError.message?.contains("canonical") == true, "wrong refusal for a doctype with attributes")
        assertNoMarkers(canonicalError)

        val flood = "<!DOCTYPE tv SYSTEM \"xmltv.dtd\" " + "a=\"v\" ".repeat(4_096) + ">"
        val capError = assertFailsWith<SAXException> {
            XmltvProlog.verify(ByteArrayInputStream(bytes(flood + root)))
        }
        assertTrue(capError.message?.contains("prolog exceeds") == true, "attribute flood was not bounded")
        assertNoMarkers(capError)
    }

    @Test fun repeatedCyclesKeepTheGateStatelessAndItsBuffersBounded() {
        val hostile = bytes("<!--$urlMarker--><!DOCTYPE tv SYSTEM \"$urlMarker\" [<!ENTITY x \"$secretMarker\">]>$canonical$root")
        val safe = bytes(canonical + guideBody)
        repeat(1_024) {
            assertFailsWith<SAXException> { XmltvProlog.verify(ByteArrayInputStream(hostile)) }
            XmltvProlog.verify(ByteArrayInputStream(safe))
        }
        repeat(32) {
            assertEquals("One", GuideParser.parse(ByteArrayInputStream(safe)).channelNames["c"])
            refuseProlog(hostile)
        }

        // The singleton owns no mutable state; each scan owns exactly three bounded fields and no collection.
        val singletonState = XmltvProlog::class.java.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        assertTrue(singletonState.isEmpty(), "prolog singleton gained mutable state")
        val readerState = Class.forName("com.devtunasoft.tuneros.core.XmltvProlog\$PrologReader").declaredFields
        assertEquals(3, readerState.size)
        assertTrue(readerState.none {
            it.type.isArray || Collection::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type)
        }, "prolog reader retains a collection")
    }
}
