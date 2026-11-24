package com.devtunasoft.tuneros.qa66

/** JVM-only driver for the androidTest mirror; compiled by mirror_check.sh, not by AGP. */
fun main() {
    val lines = Qa66Assertions.runChecks()
    var fail = 0
    for (line in lines) {
        println(line)
        if (line.startsWith("FAIL")) fail++
    }
    println("MIRROR\tchecks=${lines.size}\tfail=$fail")
    if (fail != 0) throw IllegalStateException("mirror checks failed")
}
