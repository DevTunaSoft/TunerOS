package com.devtunasoft.tuneros.qa61

import java.io.File

/** QA-61 host entry: generate pinned scratch bytes or run a bounded verification mode. */
object Qa61HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val modes = setOf(
            "generate", "verify", "verify-manifest", "verify-pinned", "verify-semantic",
        )
        if (args.size != 2 || !modes.contains(args[0])) {
            println("usage: Qa61HostMain generate|verify|verify-manifest|verify-pinned|verify-semantic <dir>")
            kotlin.system.exitProcess(2)
        }
        val dir = File(args[1])
        if (args[0] == "generate") {
            if (!dir.isDirectory && !dir.mkdirs()) {
                println("FIRST\tGENERATE_DIR")
                kotlin.system.exitProcess(1)
            }
            val generated = Qa61Contract.generate()
            for (name in Qa61Contract.ALL_FILES) File(dir, name).writeBytes(generated.getValue(name))
            println("GENERATE\tPASS\tfiles=" + Qa61Contract.ALL_FILES.size)
            return
        }
        val files = LinkedHashMap<String, ByteArray>()
        for (name in Qa61Contract.ALL_FILES) {
            val file = File(dir, name)
            if (!file.isFile) {
                println("FIRST\tMISSING_FIXTURE")
                println("VERDICT\tFAIL")
                kotlin.system.exitProcess(1)
            }
            files[name] = file.readBytes()
        }
        kotlin.system.exitProcess(Qa61Contract.verifyModel(args[0], files) { println(it) })
    }
}
