package android.database.sqlite

import android.content.ContentValues
import android.database.Cursor

/**
 * Minimal scratch SQLite stand-in. It evaluates the small SQL subset the production store emits:
 * parameter placeholders, = <> < > <= >=, AND/OR, IN (...), IS NULL, ORDER BY and LIMIT.
 * No real database, file, socket or provider is touched.
 */
class SQLiteDatabase {
    interface CursorFactory

    companion object {
        const val CONFLICT_IGNORE: Int = 4
    }

    private val rows = LinkedHashMap<Long, MutableMap<String, Any?>>()
    private var nextRow = 1L

    fun execSQL(@Suppress("UNUSED_PARAMETER") sql: String) = Unit

    fun insertWithOnConflict(
        @Suppress("UNUSED_PARAMETER") table: String,
        @Suppress("UNUSED_PARAMETER") nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int,
    ): Long {
        val copy = LinkedHashMap(values.map)
        if (conflictAlgorithm == CONFLICT_IGNORE) {
            val id = copy["id"]?.toString()
            if (id != null && rows.values.any { it["id"]?.toString() == id }) return -1L
        }
        val key = nextRow++
        rows[key] = copy
        return key
    }

    fun update(
        @Suppress("UNUSED_PARAMETER") table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
    ): Int {
        val predicate = predicateOf(whereClause, whereArgs)
        var changed = 0
        for (row in rows.values) {
            if (predicate(row)) {
                row.putAll(values.map)
                changed++
            }
        }
        return changed
    }

    fun delete(
        @Suppress("UNUSED_PARAMETER") table: String,
        whereClause: String?,
        whereArgs: Array<String>?,
    ): Int {
        val predicate = predicateOf(whereClause, whereArgs)
        val doomed = rows.filter { predicate(it.value) }.map { it.key }
        for (key in doomed) rows.remove(key)
        return doomed.size
    }

    fun query(
        @Suppress("UNUSED_PARAMETER") table: String?,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        @Suppress("UNUSED_PARAMETER") groupBy: String?,
        @Suppress("UNUSED_PARAMETER") having: String?,
        orderBy: String?,
        limit: String?,
    ): Cursor {
        val predicate = predicateOf(selection, selectionArgs)
        var matched = rows.values.filter(predicate)
        if (!orderBy.isNullOrEmpty()) matched = matched.sortedWith(orderComparator(orderBy))
        val cap = limit?.toIntOrNull()
        if (cap != null && matched.size > cap) matched = matched.take(cap)
        val projection = columns ?: (rows.values.firstOrNull()?.keys?.toTypedArray() ?: emptyArray())
        val rendered = matched.map { row -> projection.associateWith { name -> row[name] } }
        return ListCursor(projection, rendered)
    }

    private fun predicateOf(selection: String?, args: Array<String>?): (Map<String, Any?>) -> Boolean =
        if (selection.isNullOrEmpty()) {
            { true }
        } else {
            ConditionParser(tokenize(selection), args).parse()
        }

    private enum class Kind { IDENT, NUM, STR, PARAM, OP, LP, RP, COMMA, AND, OR, IN, IS, NOT, NULL, END }

    private data class Token(val kind: Kind, val text: String)

    private fun badSql(): Nothing = throw IllegalArgumentException("scratch SQL is outside the QA-69 subset")

    private fun tokenize(sql: String): List<Token> {
        val out = ArrayList<Token>()
        var index = 0
        while (index < sql.length) {
            val ch = sql[index]
            when {
                ch.isWhitespace() -> index++
                ch == '?' -> {
                    out += Token(Kind.PARAM, "?")
                    index++
                }
                ch == '(' -> {
                    out += Token(Kind.LP, "(")
                    index++
                }
                ch == ')' -> {
                    out += Token(Kind.RP, ")")
                    index++
                }
                ch == ',' -> {
                    out += Token(Kind.COMMA, ",")
                    index++
                }
                ch == '\'' -> {
                    val start = ++index
                    while (index < sql.length && sql[index] != '\'') index++
                    if (index >= sql.length) badSql()
                    out += Token(Kind.STR, sql.substring(start, index))
                    index++
                }
                ch == '=' || ch == '<' || ch == '>' || ch == '!' -> {
                    val pair = if (index + 1 < sql.length) sql.substring(index, index + 2) else ""
                    if (pair in setOf("<>", "!=", "<=", ">=")) {
                        out += Token(Kind.OP, pair)
                        index += 2
                    } else {
                        out += Token(Kind.OP, ch.toString())
                        index++
                    }
                }
                ch.isDigit() -> {
                    val start = index
                    while (index < sql.length && sql[index].isDigit()) index++
                    out += Token(Kind.NUM, sql.substring(start, index))
                }
                ch.isLetter() || ch == '_' -> {
                    val start = index
                    while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) index++
                    val word = sql.substring(start, index)
                    out += when (word.uppercase()) {
                        "AND" -> Token(Kind.AND, word)
                        "OR" -> Token(Kind.OR, word)
                        "IN" -> Token(Kind.IN, word)
                        "IS" -> Token(Kind.IS, word)
                        "NOT" -> Token(Kind.NOT, word)
                        "NULL" -> Token(Kind.NULL, word)
                        else -> Token(Kind.IDENT, word)
                    }
                }
                else -> badSql()
            }
        }
        out += Token(Kind.END, "")
        return out
    }

    private sealed class Operand

    private data class Column(val name: String) : Operand()

    private data class Constant(val value: Any?) : Operand()

    private data class Parameter(val value: String) : Operand()

    private fun compare(left: Any?, right: Any?): Int? {
        if (left == null || right == null) return null
        if (left is Number && right is Number) {
            return java.lang.Long.compare(left.toLong(), right.toLong())
        }
        if (left is Number && right is String) {
            val numeric = right.toLongOrNull()
            if (numeric != null) return java.lang.Long.compare(left.toLong(), numeric)
        }
        if (left is String && right is Number) {
            val numeric = left.toLongOrNull()
            if (numeric != null) return java.lang.Long.compare(numeric, right.toLong())
        }
        return left.toString().compareTo(right.toString())
    }

    private inner class ConditionParser(private val tokens: List<Token>, args: Array<String>?) {
        private var cursor = 0
        private var argument = 0
        private val arguments = args ?: emptyArray()

        private fun peek(): Token = tokens[cursor]

        private fun take(): Token {
            val token = tokens[cursor]
            cursor++
            return token
        }

        private fun at(kind: Kind): Boolean = peek().kind == kind

        private fun expect(kind: Kind): Token {
            val token = take()
            if (token.kind != kind) badSql()
            return token
        }

        private fun nextArgument(): String {
            if (argument >= arguments.size) badSql()
            return arguments[argument++]
        }

        fun parse(): (Map<String, Any?>) -> Boolean {
            val predicate = parseOr()
            if (!at(Kind.END)) badSql()
            return predicate
        }

        private fun parseOr(): (Map<String, Any?>) -> Boolean {
            var left = parseAnd()
            while (at(Kind.OR)) {
                take()
                val right = parseAnd()
                val first = left
                left = { row -> first(row) || right(row) }
            }
            return left
        }

        private fun parseAnd(): (Map<String, Any?>) -> Boolean {
            var left = parseAtom()
            while (at(Kind.AND)) {
                take()
                val right = parseAtom()
                val first = left
                left = { row -> first(row) && right(row) }
            }
            return left
        }

        private fun operand(): Operand = when (peek().kind) {
            Kind.IDENT -> Column(take().text)
            Kind.NUM -> Constant(take().text.toLongOrNull() ?: badSql())
            Kind.STR -> Constant(take().text)
            Kind.PARAM -> {
                take()
                Parameter(nextArgument())
            }
            else -> badSql()
        }

        private fun value(operand: Operand, row: Map<String, Any?>): Any? = when (operand) {
            is Column -> row[operand.name]
            is Constant -> operand.value
            is Parameter -> operand.value
        }

        private fun parseAtom(): (Map<String, Any?>) -> Boolean {
            if (at(Kind.LP)) {
                take()
                val inner = parseOr()
                expect(Kind.RP)
                return inner
            }
            val left = operand()
            if (at(Kind.IN)) {
                take()
                expect(Kind.LP)
                val accepted = ArrayList<Any?>()
                while (!at(Kind.RP)) {
                    accepted += value(operand(), emptyMap())
                    if (at(Kind.COMMA)) take() else if (!at(Kind.RP)) badSql()
                }
                expect(Kind.RP)
                return { row -> accepted.any { candidate -> compare(value(left, row), candidate) == 0 } }
            }
            if (at(Kind.IS)) {
                take()
                val negated = if (at(Kind.NOT)) {
                    take()
                    true
                } else {
                    false
                }
                expect(Kind.NULL)
                return { row ->
                    val isNull = value(left, row) == null
                    if (negated) !isNull else isNull
                }
            }
            val operator = expect(Kind.OP).text
            val right = operand()
            return { row ->
                val relation = compare(value(left, row), value(right, row))
                relation != null && when (operator) {
                    "=" -> relation == 0
                    "<>" -> relation != 0
                    "!=" -> relation != 0
                    "<" -> relation < 0
                    ">" -> relation > 0
                    "<=" -> relation <= 0
                    ">=" -> relation >= 0
                    else -> badSql()
                }
            }
        }
    }

    private fun orderComparator(specification: String): Comparator<Map<String, Any?>> {
        val terms = specification.split(',').map { it.trim().split(Regex("\\s+")) }
        return Comparator { left, right ->
            for (term in terms) {
                val column = term[0]
                val descending = term.getOrNull(1)?.equals("DESC", ignoreCase = true) == true
                val relation = compare(left[column], right[column]) ?: 0
                if (relation != 0) return@Comparator if (descending) -relation else relation
            }
            0
        }
    }

    private class ListCursor(
        private val projection: Array<String>,
        private val data: List<Map<String, Any?>>,
    ) : Cursor {
        private var position = -1

        override fun moveToNext(): Boolean {
            position++
            return position < data.size
        }

        override fun moveToFirst(): Boolean {
            position = 0
            return data.isNotEmpty()
        }

        override fun close() = Unit

        private fun raw(index: Int): Any? = data[position][projection[index]]

        override fun getString(index: Int): String = raw(index)?.toString() ?: throw NullPointerException("null cell")

        override fun getLong(index: Int): Long = raw(index).let { value ->
            when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
        }

        override fun getInt(index: Int): Int = raw(index).let { value ->
            when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: 0
                else -> 0
            }
        }

        override fun isNull(index: Int): Boolean = raw(index) == null

        override fun getColumnIndexOrThrow(columnName: String): Int {
            val found = projection.indexOf(columnName)
            if (found < 0) throw IllegalArgumentException("unknown column")
            return found
        }
    }
}
