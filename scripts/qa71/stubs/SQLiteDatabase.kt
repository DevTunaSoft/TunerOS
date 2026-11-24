package android.database.sqlite

import android.content.ContentValues
import android.database.Cursor

/**
 * Bounded SQL-subset evaluator for the QA-71 host gate. It interprets the selection and ORDER BY
 * text emitted by the real store, so a mutated WHERE/ORDER string changes behaviour instead of
 * matching a canned lookup. Only the subset the store emits is admitted; anything else fails closed.
 */
class SQLiteDatabase private constructor(val name: String) {
    data class StoredRow(val cells: LinkedHashMap<String, Any?>)

    val tables = LinkedHashMap<String, LinkedHashMap<String, StoredRow>>()
    val columns = LinkedHashMap<String, LinkedHashSet<String>>()
    val ddl = ArrayList<String>()
    var cursorsCreated = 0
        private set
    var cursorsClosed = 0
        private set
    var initialized = false

    companion object {
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5
        private val opened = LinkedHashMap<String, SQLiteDatabase>()
        fun open(name: String?): SQLiteDatabase {
            val key = name ?: ":memory:"
            return opened.getOrPut(key) { SQLiteDatabase(key) }
        }
        fun reset() { opened.clear() }
    }

    class CursorFactory

    fun execSQL(sql: String) {
        val statement = sql.trim()
        ddl += statement
        val upper = statement.uppercase()
        when {
            upper.startsWith("CREATE TABLE") -> {
                val table = statement.substringAfter("CREATE TABLE", "").trim().substringBefore('(').trim()
                val body = statement.substringAfter('(').substringBeforeLast(')')
                val set = LinkedHashSet<String>()
                for (part in body.split(',')) {
                    val col = part.trim().split(Regex("\\s+"), limit = 2).firstOrNull() ?: continue
                    if (col.isNotEmpty()) set += col.lowercase()
                }
                columns[table] = set
                tables.putIfAbsent(table, LinkedHashMap())
            }
            upper.startsWith("CREATE INDEX") -> Unit
            upper.startsWith("DROP TABLE") -> {
                val table = statement.substringAfter("DROP TABLE", "").trim().trimEnd(';')
                tables.remove(table); columns.remove(table)
            }
            upper.startsWith("ALTER TABLE") -> {
                val rest = statement.substringAfter("ALTER TABLE", "").trim()
                val table = rest.substringBefore(' ').trim()
                if (upper.contains("ADD COLUMN")) {
                    val col = rest.substringAfter("ADD COLUMN", "").trim().substringBefore(' ').lowercase()
                    columns[table]?.add(col)
                    tables[table]?.values?.forEach { it.cells.putIfAbsent(col, 0L) }
                } else if (upper.contains("RENAME TO")) {
                    val newName = rest.substringAfter("RENAME TO", "").trim()
                    tables[newName] = tables.remove(table) ?: LinkedHashMap()
                    columns[newName] = columns.remove(table) ?: LinkedHashSet()
                }
            }
            upper.startsWith("INSERT INTO") -> {
                val target = statement.substringAfter("INSERT INTO", "").trim().substringBefore('(').trim()
                val source = statement.substringAfter("FROM", "").trim().substringBefore(' ').trim()
                val dest = tables.getOrPut(target) { LinkedHashMap() }
                for ((id, row) in tables[source].orEmpty()) {
                    dest[id] = StoredRow(LinkedHashMap(row.cells))
                }
            }
            else -> throw IllegalArgumentException("unsupported DDL: ${statement.take(24)}")
        }
    }

    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int,
    ): Long {
        val rows = tables.getOrPut(table) { LinkedHashMap() }
        val map = values.asMap()
        val id = map["id"]?.toString() ?: throw IllegalArgumentException("missing id")
        if (rows.containsKey(id)) {
            if (conflictAlgorithm == CONFLICT_REPLACE) {
                val row = StoredRow(LinkedHashMap(map))
                rows[id] = row
                return id.toLongOrNull() ?: 1L
            }
            return -1L
        }
        val known = columns[table] ?: LinkedHashSet(map.keys.map { it.lowercase() })
        for (key in map.keys) known.add(key)
        columns[table] = known
        rows[id] = StoredRow(LinkedHashMap(map))
        return id.toLongOrNull() ?: 1L
    }

    fun update(table: String, values: ContentValues, where: String?, whereArgs: Array<String>?): Int {
        val rows = tables[table] ?: return 0
        var changed = 0
        for (row in rows.values) {
            if (where == null || matches(where, whereArgs?.toList() ?: emptyList(), row)) {
                for ((key, value) in values.asMap()) row.cells[key] = value
                changed++
            }
        }
        return changed
    }

    fun delete(table: String, where: String?, whereArgs: Array<String>?): Int {
        val rows = tables[table] ?: return 0
        val doomed = rows.values.filter { where == null || matches(where, whereArgs?.toList() ?: emptyList(), it) }
            .mapNotNull { it.cells["id"]?.toString() }
        doomed.forEach { rows.remove(it) }
        return doomed.size
    }

    fun query(
        table: String,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        limit: String?,
    ): Cursor {
        if (!groupBy.isNullOrEmpty() || !having.isNullOrEmpty()) {
            throw IllegalArgumentException("unsupported grouping")
        }
        val rows = tables[table] ?: LinkedHashMap()
        var values = rows.values.toList()
        if (!selection.isNullOrEmpty()) {
            values = values.filter { matches(selection, selectionArgs?.toList() ?: emptyList(), it) }
        }
        if (!orderBy.isNullOrEmpty()) {
            val upper = orderBy.uppercase()
            val desc = upper.contains(" DESC")
            val field = when {
                upper.contains("STARTED_EPOCH") -> "started_epoch"
                upper.contains("STOPPED_EPOCH") -> "stopped_epoch"
                upper.contains("BYTES_WRITTEN") -> "bytes_written"
                upper.contains(" ID") || upper == "ID" -> "id"
                else -> throw IllegalArgumentException("unsupported ORDER BY")
            }
            val coalesce = upper.contains("COALESCE")
            values = values.sortedWith { a, b ->
                val av = a.cells[field]; val bv = b.cells[field]
                val cmp = when {
                    av == null && bv == null -> 0
                    av == null -> if (coalesce) (0L).compareTo(asLong(bv)) else -1
                    bv == null -> if (coalesce) asLong(av).compareTo(0L) else 1
                    av is Number || bv is Number -> asLong(av).compareTo(asLong(bv))
                    else -> av.toString().compareTo(bv.toString())
                }
                val tied = if (cmp == 0) (a.cells["id"]?.toString().orEmpty())
                    .compareTo(b.cells["id"]?.toString().orEmpty()) else cmp
                // Direction applies to the value only; ties keep a stable id order.
                if (cmp == 0) tied else if (desc) -cmp else cmp
            }
        }
        if (!limit.isNullOrEmpty()) {
            val count = limit.toIntOrNull() ?: throw IllegalArgumentException("bad limit")
            values = values.take(count)
        }
        cursorsCreated++
        val projectionNames = projection.toList()
        val projected = values.map { row -> projection.associateWith { key -> row.cells[key] } }
        return object : Cursor() {
            override fun close() { super.close(); cursorsClosed++ }
        }.also { c ->
            c.names = projectionNames
            c.rows = projected
        }
    }

    private fun asLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private sealed class Part {
        data class Word(val text: String) : Part()
        data class Text(val text: String) : Part()
        data class Number(val value: Long) : Part()
        object Arg : Part()
        object Open : Part()
        object Close : Part()
        object Comma : Part()
        data class Operator(val text: String) : Part()
    }

    private fun tokenize(expression: String): List<Part> {
        val parts = ArrayList<Part>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c.isWhitespace() -> i++
                c == '?' -> { parts += Part.Arg; i++ }
                c == '(' -> { parts += Part.Open; i++ }
                c == ')' -> { parts += Part.Close; i++ }
                c == ',' -> { parts += Part.Comma; i++ }
                c == '\'' -> {
                    val end = expression.indexOf('\'', i + 1)
                    if (end < 0) throw IllegalArgumentException("unterminated literal")
                    parts += Part.Text(expression.substring(i + 1, end))
                    i = end + 1
                }
                c == '=' || c == '<' || c == '>' || c == '!' -> {
                    var op = c.toString()
                    if (i + 1 < expression.length && expression[i + 1] == '=') { op += "="; i++ }
                    parts += Part.Operator(op); i++
                }
                else -> {
                    var end = i
                    while (end < expression.length && !expression[end].isWhitespace() &&
                        expression[end] !in "(),?'=<>!"
                    ) end++
                    if (end == i) throw IllegalArgumentException("bad token")
                    val word = expression.substring(i, end)
                    val asNumber = word.toLongOrNull()
                    parts += if (asNumber != null) Part.Number(asNumber) else Part.Word(word.uppercase())
                    i = end
                }
            }
        }
        return parts
    }

    private class Reader(
        private val parts: List<Part>,
        private val args: List<String>,
        private val row: StoredRow,
    ) {
        var index = 0
            private set
        private var argIndex = 0

        fun peek(): Part? = parts.getOrNull(index)
        fun take(): Part = parts.getOrNull(index++) ?: throw IllegalArgumentException("short expression")
        fun expectWord(word: String): Boolean {
            if (peek() is Part.Word && (peek() as Part.Word).text == word) { index++; return true }
            return false
        }

        fun operand(part: Part): Any? = when (part) {
            is Part.Arg -> args.getOrElse(argIndex++) { "" }
            is Part.Text -> part.text
            is Part.Number -> part.value
            is Part.Word -> row.cells[part.text.lowercase()]
            else -> throw IllegalArgumentException("bad operand")
        }
    }

    /** Recursive descent over the emitted WHERE text: OR -> AND -> atom -> comparison. */
    private fun matches(expression: String, args: List<String>, row: StoredRow): Boolean {
        val reader = Reader(tokenize(expression), args, row)
        val result = parseOr(reader)
        if (reader.peek() != null) throw IllegalArgumentException("trailing expression text")
        return result
    }

    private fun parseOr(r: Reader): Boolean {
        var value = parseAnd(r)
        while (r.expectWord("OR")) {
            val right = parseAnd(r)
            value = value || right
        }
        return value
    }

    private fun parseAnd(r: Reader): Boolean {
        var value = parseAtom(r)
        while (r.expectWord("AND")) {
            val right = parseAtom(r)
            value = value && right
        }
        return value
    }

    private fun parseAtom(r: Reader): Boolean {
        if (r.peek() is Part.Open) {
            r.take()
            val value = parseOr(r)
            if (r.take() !is Part.Close) throw IllegalArgumentException("unclosed group")
            return value
        }
        val left = r.operand(r.take())
        if (r.expectWord("IS")) {
            val negate = r.expectWord("NOT")
            if (!r.expectWord("NULL")) throw IllegalArgumentException("bad IS")
            return if (negate) left != null else left == null
        }
        if (r.expectWord("NOT")) {
            if (!r.expectWord("IN")) throw IllegalArgumentException("bad NOT")
            return !inSet(r, left)
        }
        if (r.expectWord("IN")) return inSet(r, left)
        val op = r.take() as? Part.Operator ?: throw IllegalArgumentException("missing operator")
        val right = r.operand(r.take())
        return compare(left, right, op.text)
    }

    private fun inSet(r: Reader, left: Any?): Boolean {
        if (r.take() !is Part.Open) throw IllegalArgumentException("bad IN")
        var found = false
        while (r.peek() !is Part.Close) {
            val candidate = r.operand(r.take())
            if (compare(left, candidate, "=")) found = true
            if (r.peek() is Part.Comma) r.take() else break
        }
        if (r.take() !is Part.Close) throw IllegalArgumentException("unclosed IN")
        return found
    }

    private fun compare(left: Any?, right: Any?, op: String): Boolean {
        val cmp = when {
            left == null || right == null -> if (left == null && right == null) 0 else 1
            left is Number || right is Number -> {
                val lnum = asLong(left); val rnum = asLong(right)
                when {
                    lnum == rnum && left.toString() == right.toString() -> 0
                    lnum == rnum && left !is Number && right !is Number -> 0
                    else -> lnum.compareTo(rnum)
                }
            }
            else -> left.toString().compareTo(right.toString())
        }
        return when (op) {
            "=" -> cmp == 0
            "!=" -> cmp != 0
            "<" -> cmp < 0
            ">" -> cmp > 0
            "<=" -> cmp <= 0
            ">=" -> cmp >= 0
            else -> throw IllegalArgumentException("bad operator: $op")
        }
    }
}
