package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.xml.sax.SAXException

/**
 * Grammar test of the XMLTV prolog check. The safe prolog is an optional UTF-8 byte-order mark, then any mix of spaces,
 * tabs, line breaks, processing instructions and comments, at most one canonical DOCTYPE, and then the root element's
 * name. The regular expression below states that grammar, and the check must accept exactly the prologs it matches.
 * Every iteration is seeded, so a failure reproduces from the iteration number.
 */
class XmltvPrologGrammarTest {
    // A comment or processing instruction ends at its first terminator, so each token may not contain one inside it.
    private val safeToken =
        """(?:[ \t\r\n]|<\?(?:(?!\?>).)*\?>|<!--(?:(?!-->).)*-->|<!DOCTYPE tv SYSTEM "xmltv\.dtd"[ \t\r\n]*>)"""
    // The root name starts with an ASCII letter, underscore or colon, or with any non-ASCII character. XML allows
    // non-ASCII name starts (for example é), and the check takes every byte of 0x80 or more as one. The rest of the
    // name is left to the SAX parser, so this grammar stops at the first character of the name.
    private val safePrologThenRoot =
        Regex("^(?:$safeToken)*<(?:[A-Za-z_:]|[^\\x00-\\x7F])", RegexOption.DOT_MATCHES_ALL)

    private fun grammarAccepts(text: String): Boolean {
        val body = text.removePrefix("﻿")
        return safePrologThenRoot.containsMatchIn(body) && body.split("<!DOCTYPE").size - 1 <= 1
    }

    private fun checkAccepts(text: String): Boolean =
        try {
            XmltvProlog.verify(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            true
        } catch (refused: SAXException) {
            false
        }

    private val fragments = listOf(
        " ", "\n", "\t", "\r", "<?xml version=\"1.0\"?>", "<?pi data?>", "<!-- note -->", "<!-- a -- b -->",
        "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">", "<!DOCTYPE tv SYSTEM \"xmltv.dtd\" >", "<!DOCTYPE tv>",
        "<!DOCTYPE tv [<!ENTITY x \"y\">]>", "<!ENTITY x>", "text", "é", "<!x>", "<", "<<", "<?",
    )

    private val roots = listOf("<tv>", "<tv>", "<_tv>", "<:tv>", "<1tv>", "</tv>", "")

    @Test fun thePrologCheckAcceptsExactlyTheSafeGrammar() {
        val random = Random(20261122)
        var accepted = 0
        var refused = 0
        repeat(3_000) { iteration ->
            val text = buildString {
                if (random.nextInt(5) == 0) append('﻿')
                repeat(random.nextInt(0, 6)) { append(fragments[random.nextInt(fragments.size)]) }
                append(roots[random.nextInt(roots.size)])
            }
            val expected = grammarAccepts(text)
            assertEquals(expected, checkAccepts(text), "iteration $iteration for ${text.replace("\n", "\\n")}")
            if (expected) accepted++ else refused++
        }
        // Both outcomes must be common, or the grammar could match without the check ever deciding either way.
        assertTrue(accepted >= 100 && refused >= 100, "accepted $accepted, refused $refused")
    }
}
