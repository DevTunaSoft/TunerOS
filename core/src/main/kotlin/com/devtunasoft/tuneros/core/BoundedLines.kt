package com.devtunasoft.tuneros.core

import java.io.BufferedReader

internal object BoundedLines {
    fun read(reader: BufferedReader, maxLength: Int, label: String): String? {
        require(maxLength > 0)
        val line = StringBuilder(minOf(maxLength, 256))
        while (true) {
            val c = reader.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= maxLength) {
                // A CR here is the first byte of a CRLF terminator (or a final bare CR), not line content.
                if (c == '\r'.code) {
                    reader.mark(1)
                    val next = reader.read()
                    if (next == '\n'.code || next == -1) return line.toString()
                    reader.reset()
                }
                throw IllegalArgumentException("$label line exceeds $maxLength characters")
            }
            line.append(c.toChar())
        }
    }
}
