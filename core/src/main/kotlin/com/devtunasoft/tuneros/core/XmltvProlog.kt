package com.devtunasoft.tuneros.core

import java.io.InputStream
import org.xml.sax.SAXException

/**
 * Checks the XML prolog of an XMLTV document before the SAX parser reads a byte of it.
 *
 * Real XMLTV files commonly begin with `<!DOCTYPE tv SYSTEM "xmltv.dtd">`. That one reference is
 * accepted. [GuideParser] answers the external DTD with an empty document, so nothing is fetched
 * and nothing is declared. Everything else is refused here: an internal subset (entity definitions
 * could then expand without limit), a public identifier, any other system identifier, another root
 * name, a second DOCTYPE, and UTF-16 input, whose prolog this byte-level check cannot read.
 *
 * The scan stops at the root element start tag, so nothing after the root is inspected here. The
 * prolog is limited to [MAX_BYTES], which the caller must allow for in its mark limit.
 */
internal object XmltvProlog {
    /** Largest prolog (XML declaration, comments and DOCTYPE) that will be read. */
    const val MAX_BYTES = 8 * 1024

    private val canonicalDoctype = Regex("""<!DOCTYPE\s+tv\s+SYSTEM\s+"xmltv\.dtd"\s*>""")
    private const val UNSUPPORTED_ENCODING = "XMLTV must be UTF-8 or ASCII-compatible"
    private const val UNSUPPORTED_DECLARATION = "Unsupported declaration before the XMLTV root element"
    private const val NO_BYTE = -2

    private const val SPACE = 0x20
    private const val TAB = 0x09
    private const val LF = 0x0A
    private const val CR = 0x0D
    private const val BANG = 0x21
    private const val DASH = 0x2D
    private const val QUESTION = 0x3F
    private const val COLON = 0x3A
    private const val UNDERSCORE = 0x5F
    private const val LT = 0x3C
    private const val GT = 0x3E

    /**
     * Reads the prolog of [input] and throws [SAXException] for anything unsafe or unsupported.
     * [input] must support mark and reset. The caller resets it, so the parser starts at byte zero.
     */
    fun verify(input: InputStream) {
        val reader = PrologReader(input)
        when (val first = reader.read()) {
            0xEF -> if (reader.read() != 0xBB || reader.read() != 0xBF) throw SAXException(UNSUPPORTED_ENCODING)
            0xFE, 0xFF -> throw SAXException(UNSUPPORTED_ENCODING)
            else -> reader.unread(first)
        }
        var sawDoctype = false
        while (true) {
            val byte = reader.read()
            when {
                byte == -1 -> throw SAXException("XMLTV document has no root element")
                byte == SPACE || byte == TAB || byte == LF || byte == CR -> Unit
                byte != LT -> throw SAXException("Unexpected content before the XMLTV root element")
                else -> {
                    val next = reader.read()
                    when {
                        next == QUESTION -> reader.skipPast(QUESTION, GT)
                        next == BANG -> { sawDoctype = readDeclaration(reader, sawDoctype) }
                        isNameStart(next) -> return
                        else -> throw SAXException("Malformed XMLTV root element")
                    }
                }
            }
        }
    }

    /** Handles `<!--` (skipped) and the one canonical `<!DOCTYPE`. Returns whether a DOCTYPE was seen. */
    private fun readDeclaration(reader: PrologReader, sawDoctype: Boolean): Boolean {
        val kind = reader.read()
        if (kind == DASH) {
            if (reader.read() != DASH) throw SAXException(UNSUPPORTED_DECLARATION)
            skipComment(reader)
            return sawDoctype
        }
        if (kind != 'D'.code) throw SAXException(UNSUPPORTED_DECLARATION)
        for (expected in "OCTYPE") {
            if (reader.read() != expected.code) throw SAXException(UNSUPPORTED_DECLARATION)
        }
        if (sawDoctype) throw SAXException("XMLTV allows one DOCTYPE declaration")
        val body = reader.readUntil(GT)
        if (body.indexOf('[') >= 0) throw SAXException("XMLTV internal DTD subsets are not allowed")
        if (!canonicalDoctype.matches("<!DOCTYPE$body>")) {
            throw SAXException("XMLTV DOCTYPE must be the canonical xmltv.dtd reference")
        }
        return true
    }

    /** Skips the rest of a comment whose `<!--` has been read. Comment text is never interpreted. */
    private fun skipComment(reader: PrologReader) {
        var dashes = 0
        while (true) {
            val byte = reader.read()
            if (byte == -1) throw SAXException("Unterminated XMLTV comment")
            when {
                byte == GT && dashes >= 2 -> return
                byte == DASH -> dashes++
                else -> dashes = 0
            }
        }
    }

    private fun isNameStart(byte: Int): Boolean =
        byte == COLON || byte == UNDERSCORE ||
            (byte >= 'A'.code && byte <= 'Z'.code) ||
            (byte >= 'a'.code && byte <= 'z'.code) ||
            byte >= 0x80

    /** Counts every byte it reads, so a hostile prolog stops at [MAX_BYTES]. */
    private class PrologReader(private val input: InputStream) {
        private var consumed = 0
        private var pushedBack = NO_BYTE

        fun read(): Int {
            if (pushedBack != NO_BYTE) {
                val byte = pushedBack
                pushedBack = NO_BYTE
                return byte
            }
            if (consumed >= MAX_BYTES) throw SAXException("XMLTV prolog exceeds $MAX_BYTES bytes")
            val byte = input.read()
            if (byte >= 0) consumed++
            return byte
        }

        fun unread(byte: Int) {
            pushedBack = byte
        }

        /** Returns the bytes before [terminator] as characters, consuming the terminator. */
        fun readUntil(terminator: Int): String {
            val out = StringBuilder()
            while (true) {
                val byte = read()
                if (byte == -1) throw SAXException("Unterminated XMLTV DOCTYPE")
                if (byte == terminator) return out.toString()
                out.append(byte.toChar())
            }
        }

        /** Skips through the first occurrence of [first] immediately followed by [second]. */
        fun skipPast(first: Int, second: Int) {
            var previous = NO_BYTE
            while (true) {
                val byte = read()
                if (byte == -1) throw SAXException("Unterminated XMLTV processing instruction")
                if (previous == first && byte == second) return
                previous = byte
            }
        }
    }
}
