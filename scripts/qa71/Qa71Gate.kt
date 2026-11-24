package com.devtunasoft.tuneros.qa71

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.devtunasoft.tuneros.RecordingCatalog
import java.io.File
import kotlin.system.exitProcess

/**
 * QA-71 zero-dependency host gate. Compiled with the real RecordingCatalog source plus minimal
 * Android stubs; every verdict comes from the compiled store, never from a second implementation.
 */
private class Report {
    var pass = 0
        private set
    var fail = 0
        private set
    var first: String = "NONE"
        private set

    fun check(code: String, ok: Boolean) {
        if (ok) {
            pass++
            println("CHECK\t$code\tPASS")
        } else {
            fail++
            if (first == "NONE") first = code
            println("CHECK\t$code\tFAIL")
        }
    }

    fun summary() {
        println("FIRST\t$first")
        println("SUMMARY\tchecks=${pass + fail}\tpass=$pass\tfail=$fail\tfirst=$first")
    }
}

private fun read(dir: File, name: String): ByteArray = File(dir, name).readBytes()

private fun text(bytes: ByteArray): String = String(bytes, 0, bytes.size, Charsets.UTF_8)

private fun mainImpl(args: Array<String>): Int {
    val mode = args.getOrElse(0) { "verify" }
    val threadsBefore = Thread.activeCount()
    val report = Report()

    if (mode == "generate") {
        val out = File(args.getOrElse(1) { throw IllegalArgumentException("generate needs a directory") })
        out.mkdirs()
        for ((name, content) in Qa71Contract.generate()) {
            File(out, name).writeBytes(content.toByteArray(Charsets.UTF_8))
        }
        println("GENERATE\tfiles=${Qa71Contract.FILES.size + 2}\tdir=native")
        println("THREADS\t$threadsBefore\t${Thread.activeCount()}")
        return 0
    }

    if (mode == "mirror") {
        val (body, ok) = Qa71Contract.run()
        print(body)
        report.check("MIRROR_CONTRACT", ok)
        report.summary()
        return if (report.fail == 0) 0 else 1
    }

    val validModes = setOf("verify", "verify-manifest", "verify-pinned", "verify-semantic")
    if (mode !in validModes) {
        println("FIRST\tBAD_MODE")
        println("SUMMARY\tchecks=0\tpass=0\tfail=1\tfirst=BAD_MODE")
        return 1
    }
    val dir = File(args.getOrElse(1) { throw IllegalArgumentException("verify needs a fixture directory") })
    val skipByte = mode != "verify"
    val skipManifest = mode == "verify-pinned" || mode == "verify-semantic"
    val skipPinned = mode == "verify-semantic"

    val committed = LinkedHashMap<String, ByteArray>()
    for (name in Qa71Contract.FILES + listOf("sha256.txt", ".gitattributes")) {
        val file = File(dir, name)
        if (!file.isFile) {
            println("FIRST\tHARNESS")
            println("SUMMARY\tchecks=0\tpass=0\tfail=1\tfirst=HARNESS")
            return 1
        }
        committed[name] = read(dir, name)
    }
    val generated = Qa71Contract.generate()

    if (!skipByte) {
        val names = (Qa71Contract.FILES + listOf("sha256.txt", ".gitattributes")).sorted()
        val digest = Qa71Contract.sha256(
            names.joinToString("") { it + "\u0000" + Qa71Contract.sha256(committed.getValue(it)) }.toByteArray(Charsets.UTF_8),
        )
        val rebuilt = Qa71Contract.sha256(
            names.joinToString("") { it + "\u0000" + Qa71Contract.sha256(generated.getValue(it).toByteArray(Charsets.UTF_8)) }
                .toByteArray(Charsets.UTF_8),
        )
        report.check("BYTE_IDENTITY", digest == rebuilt)
    }

    val manifest = Qa71Contract.parseManifest(text(committed.getValue("sha256.txt")))
    if (!skipManifest) {
        report.check(
            "MANIFEST_CONSISTENT",
            manifest != null && manifest.size == Qa71Contract.FILES.size &&
                Qa71Contract.FILES.all { name ->
                    manifest[name] == Qa71Contract.sha256(committed.getValue(name))
                },
        )
    }

    if (!skipPinned) {
        report.check(
            "PINNED_DIGEST",
            Qa71Contract.sha256(committed.getValue("cases.tsv")) == Qa71Contract.PINNED_CASES_SHA,
        )
    }

    val dimensions = Qa71Contract.parseDimensions(text(committed.getValue("dimensions.txt")))
    val cases = Qa71Contract.parseCases(text(committed.getValue("cases.tsv")))
    val expected = Qa71Contract.parseExpected(text(committed.getValue("expected.txt")))
    val corrupt = Qa71Contract.parseCorrupt(text(committed.getValue("corrupt.tsv")))

    report.check(
        "FIXTURE_ORACLE",
        cases != null && expected != null && cases.size == Qa71Contract.CASES &&
            expected.size == Qa71Contract.CASES &&
            cases.all { row -> expected[row[0]] == row[7] },
    )
    report.check(
        "DIMENSIONS",
        dimensions != null &&
            dimensions["schema"] == Qa71Contract.SCHEMA &&
            dimensions["seed"] == Qa71Contract.SEED.toString() &&
            dimensions["row_count"] == Qa71Contract.ROWS.toString() &&
            dimensions["case_count"] == Qa71Contract.CASES.toString() &&
            dimensions["small_limit"] == Qa71Contract.SMALL_LIMIT.toString() &&
            dimensions["max_limit"] == Qa71Contract.MAX_LIMIT.toString(),
    )
    report.check(
        "CORRUPT_REJECT",
        corrupt != null && corrupt.size == 6 && corrupt.all { Qa71Contract.parseCaseRow(it) == null },
    )

    if (cases == null || expected == null || manifest == null) {
        report.check("HARNESS_PARSE", false)
        report.summary()
        return 1
    }

    SQLiteDatabase.reset()
    val catalog = RecordingCatalog(Context(), "qa71_catalog.db")
    val db = catalog.writableDatabase
    var inserts = 0
    var insertsOk = true
    var protectUpdates = 0
    var finishUpdates = 0
    var interrupts = 0
    var interruptsOk = true
    var recentChecks = 0
    var recentOk = true
    var limitsRejected = 0

    for (row in cases) {
        val op = row[1]
        val value = expected.getValue(row[0])
        when (op) {
            "insert" -> {
                val start = if (row[5] == "-") 0L else row[5].toLong()
                val admitted = catalog.tryInsertStart(row[2], row[3], row[4], start)
                inserts++
                if ((if (admitted) "ADMITTED" else "IGNORED") != value) insertsOk = false
            }
            "protect" -> protectUpdates = catalog.setProtected(row[2], true)
            "finish" -> {
                catalog.finish(row[2], row[3], row[4].toLong(), row[5].toLong(), null, null)
                val state = db.tables["recordings"]?.get(row[2])?.cells?.get("state")
                if (state == row[3]) finishUpdates++
            }
            "interrupt" -> {
                val updated = catalog.markInterruptedRecordings(row[2].toLong())
                interrupts++
                if (updated.toString() != value) interruptsOk = false
            }
            "recent" -> {
                val limit = row[2].toInt()
                if (limit !in 1..Qa71Contract.MAX_LIMIT) {
                    val rejected = try {
                        catalog.recent(limit)
                        false
                    } catch (error: IllegalArgumentException) {
                        true
                    }
                    if (rejected && value == "REJECT") limitsRejected++
                } else {
                    val ids = catalog.recent(limit).joinToString(",") { it.id }
                    recentChecks++
                    if (ids != value) recentOk = false
                }
            }
        }
    }

    report.check("INSERT_ADMISSION", inserts == 13 && insertsOk)
    val protectedCell = db.tables["recordings"]?.get("qa71r003")?.cells?.get("protected")
    report.check("PROTECT_PRESERVED", protectedCell == 1 && protectUpdates == 1)
    report.check("FINISH_SCOPE", finishUpdates == 2)
    report.check("INTERRUPT_SCOPE", interrupts == 2 && interruptsOk)
    report.check("RECENT_ORDER", recentChecks == 4 && recentOk)
    report.check("INVALID_LIMIT_REJECT", limitsRejected == 2)
    report.check("CURSOR_CLOSED", db.cursorsCreated == 4 && db.cursorsCreated == db.cursorsClosed)
    report.check(
        "SCHEMA_BOUNDS",
        db.ddl.any { it.startsWith("CREATE TABLE recordings") && it.contains("protected INTEGER") } &&
            db.ddl.count { it.contains("CREATE INDEX recordings_") } == 2,
    )
    val threadsAfter = Thread.activeCount()
    println("THREADS\t$threadsBefore\t$threadsAfter")
    report.check("THREADS", threadsBefore == threadsAfter)

    report.summary()
    return if (report.fail == 0) 0 else 1
}

fun main(args: Array<String>) {
    exitProcess(mainImpl(args))
}
