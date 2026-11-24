package com.devtunasoft.tuneros.qa60

import java.io.File
import kotlin.system.exitProcess

/** QA-60 host entry: generate pinned scratch bytes or run a bounded verification mode. */
object Qa60HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val modes = setOf(
            "generate", "verify", "verify-manifest", "verify-pinned", "verify-semantic",
        )
        if (args.size != 2 || !modes.contains(args[0])) {
            println("usage: Qa60HostMain generate|verify|verify-manifest|verify-pinned|verify-semantic <dir>")
            exitProcess(2)
        }
        val dir = File(args[1])
        if (args[0] == "generate") {
            if (!dir.isDirectory && !dir.mkdirs()) {
                println("FIRST\tGENERATE_DIR")
                exitProcess(1)
            }
            val generated = Qa60Contract.generate()
            for (name in Qa60Contract.ALL_FILES) File(dir, name).writeBytes(generated.getValue(name))
            println("GENERATE\tPASS\tfiles=" + Qa60Contract.ALL_FILES.size)
            return
        }
        val files = LinkedHashMap<String, ByteArray>()
        for (name in Qa60Contract.ALL_FILES) {
            val file = File(dir, name)
            if (!file.isFile) {
                println("FIRST\tMISSING_FIXTURE")
                println("VERDICT\tFAIL")
                exitProcess(1)
            }
            files[name] = file.readBytes()
        }
        exitProcess(Qa60Contract.verifyModel(args[0], files) { println(it) })
    }
}
