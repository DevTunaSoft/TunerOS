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
                throw IllegalArgumentException("$label line exceeds $maxLength characters")
            }
            line.append(c.toChar())
        }
    }
}
