package com.devtunasoft.tuneros.qa55

import com.devtunasoft.tuneros.core.GuideParser
import java.io.ByteArrayInputStream
import java.io.File

/** QA-55 host entry point. Adds real production cross-checks to the shared contract. */
object Qa55HostMain {

    private fun readFixture(dir: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (name in Qa55Contract.DATA_FILES) out[name] = File(dir, name).readBytes()
        out["sha256.txt"] = File(dir, "sha256.txt").readBytes()
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val dir = File(args[1])
        val lines = ArrayList<String>()
        var passed = 0
        var rc = 0

        fun pass(id: String, detail: String) { lines.add("CHECK\t" + id + "\tPASS\t" + detail); passed++ }
        fun fail(id: String, detail: String): Int {
            lines.add("CHECK\t" + id + "\tFAIL\t" + detail)
            lines.add("FIRST\t" + id)
            return 1
        }

        try {
            if (mode == "generate") {
                if (!dir.exists()) dir.mkdirs()
                for ((name, bytes) in Qa55Contract.generate()) File(dir, name).writeBytes(bytes)
                for (name in Qa55Contract.DATA_FILES) {
                    lines.add("GENERATE\t" + name + "\tsha256=" + Qa55Contract.sha256(File(dir, name).readBytes()))
                }
                lines.add("VERDICT\tPASS")
            } else {
                val files = readFixture(dir)
                val modelPassed = Qa55Contract.verifyModel(mode, files) { lines.add(it) }
                if (modelPassed < 0) {
                    rc = 1
                } else {
                    passed += modelPassed

                    val guide = files.getValue("guide.xmltv")
                    val threadsBefore = Thread.activeCount()
                    var usedMax = 0L
                    var cycle = 0
                    while (cycle < 4) {
                        val snapshot = GuideParser.parse(ByteArrayInputStream(guide))
                        if (cycle == 0) {
                            val expected = Qa55Contract.PROGRAMME_COUNT - 1
                            if (snapshot.programs.size != expected) {
                                rc = fail("PROD_ADMISSION", "count=" + snapshot.programs.size +
                                    ",expected=" + expected)
                            } else {
                                var titlesOk = true
                                for ((index, program) in snapshot.programs.withIndex()) {
                                    val want = if (index == Qa55Contract.PROGRAMME_COUNT - 2) {
                                        "X".repeat(256)
                                    } else {
                                        "Title-" + (index + 1).toString().padStart(5, '0')
                                    }
                                    if (program.title != want) titlesOk = false
                                }
                                if (!titlesOk) rc = fail("PROD_ADMISSION", "title-mismatch")
                                else pass("PROD_ADMISSION", "programmes=" + expected)
                            }
                            if (rc == 0) {
                                var boundOk = true
                                for (program in snapshot.programs) {
                                    if (program.title.length > 256) boundOk = false
                                    for (token in Qa55Contract.REGISTRY) {
                                        if (program.title.contains(token) || program.description?.contains(token) == true)
                                            boundOk = false
                                    }
                                    if (program.description?.contains("UNLISTED") == true) boundOk = false
                                }
                                if (!boundOk) rc = fail("PROD_TEXT_BOUND", "token-leaked-into-metadata")
                                else pass("PROD_TEXT_BOUND", "title-cap=256,registry=" + Qa55Contract.REGISTRY.size)
                            }
                        }
                        cycle++
                        if (cycle < 4) {
                            System.gc()
                            Thread.sleep(10L)
                        }
                        val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                        if (used > usedMax) usedMax = used
                    }
                    val threadsAfter = Thread.activeCount()
                    if (rc == 0) {
                        if (threadsAfter != threadsBefore) rc = fail("THREAD_DRIFT",
                            "before=" + threadsBefore + ",after=" + threadsAfter)
                        else pass("THREAD_DRIFT", "before=" + threadsBefore + ",after=" + threadsAfter)
                    }
                    if (rc == 0) {
                        val heapMax = Runtime.getRuntime().maxMemory()
                        if (usedMax > 16L * 1024L * 1024L) rc = fail("HEAP_BOUND", "used=" + usedMax)
                        else pass("HEAP_BOUND", "usedMax=" + usedMax)
                        lines.add("MEM\theapMax=" + heapMax + "\tusedMax=" + usedMax + "\tcycles=4")
                        lines.add("THREADS\tbefore=" + threadsBefore + "\tafter=" + threadsAfter)
                    }

                    if (rc == 0) {
                        val parsed = Qa55Contract.parseFixture(files)
                        if (parsed == null) {
                            rc = fail("PROD_CORRUPT_REJECT", "fixture-parse-null")
                        } else {
                            var rejected = 0
                            var checked = 0
                            for ((id, value) in parsed.corrupt) {
                                if (id == "X05" || id == "X09") continue
                                checked++
                                var threw = false
                                try {
                                    GuideParser.parse(ByteArrayInputStream(value.second))
                                } catch (t: Throwable) {
                                    threw = true
                                }
                                if (threw) rejected++
                            }
                            if (rejected != checked) rc = fail("PROD_CORRUPT_REJECT",
                                "rejected=" + rejected + ",checked=" + checked)
                            else pass("PROD_CORRUPT_REJECT", "rejected=" + rejected)
                        }
                    }
                }
                lines.add("SUMMARY\tchecks=" + passed + "\tfail=" + if (rc == 0) 0 else 1)
                lines.add("VERDICT\t" + if (rc == 0) "PASS" else "FAIL")
            }
        } catch (t: Throwable) {
            lines.add("FIRST\tGATE_EXCEPTION")
            lines.add("EXCEPTION\t" + t.javaClass.simpleName)
            lines.add("SUMMARY\tchecks=" + passed + "\tfail=1")
            lines.add("VERDICT\tFAIL")
            rc = 1
        }

        for (line in lines) println(line)
        System.exit(rc)
    }
}
