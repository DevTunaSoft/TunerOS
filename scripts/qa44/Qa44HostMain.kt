package com.devtunasoft.tuneros.qa44

import java.io.File

/** Host entry point. Every diagnostic line is captured and scanned before the process exits. */
private class CollectingSink : Qa44Contract.Sink {
    val lines = ArrayList<String>()

    override fun line(value: String) {
        println(value)
        lines += value
    }
}

private fun clean(lines: List<String>, marker: String): Boolean {
    val http = "ht" + "tp"
    val user = "user" + "name"
    val pass = "pass" + "word"
    val cred = "cre" + "dential"
    val secret = "se" + "cret"
    val pin = "pi" + "n"
    val token = "to" + "ken"
    val payload = "rec" + "ording" + " payload"
    val terms = listOf(http, user, pass, cred, secret, pin, token, payload, "://", marker)
    return lines.none { line -> terms.any { term -> line.contains(term, ignoreCase = true) } }
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "verify"
    val fixture = File(args.getOrNull(1) ?: "")
    val source = args.getOrNull(2)?.let(::File)
    val mutant = args.getOrNull(3)
    val sink = CollectingSink()
    val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "qa44-runtime-" + ProcessHandle.current().pid() + "-" + (mutant ?: "green"),
    )
    if (scratch.exists() && !scratch.deleteRecursively()) {
        sink.line("BLOCKED\tscratch-clean")
        System.exit(2)
    }
    if (!scratch.mkdirs()) {
        sink.line("BLOCKED\tscratch-create")
        System.exit(2)
    }
    val verdict = try {
        when (mode) {
            "generate" -> Qa44Contract.generate(fixture, sink)
            "verify", "verify-mutant" -> Qa44Contract.runAll(fixture, scratch, sink, source, mutant)
            else -> {
                sink.line("BLOCKED\tmode")
                System.exit(2)
                return
            }
        }
    } finally {
        scratch.deleteRecursively()
    }
    sink.line(
        "VERDICT\t${if (verdict.ok) "PASS" else "FAIL"}\tchecks=${verdict.checks}\t" +
            "pass=${verdict.passed}\tfail=${verdict.failed}\tfirst=${verdict.first ?: "NONE"}",
    )
    val privacyOk = clean(sink.lines, Qa44Contract.derivedMarker())
    sink.line(
        if (privacyOk) "DIAG_SCAN\tPASS\tlines=${sink.lines.size}"
        else "DIAG_SCAN\tFAIL\tDIAG_LEAK",
    )
    if (!verdict.ok || !privacyOk) System.exit(1)
}
