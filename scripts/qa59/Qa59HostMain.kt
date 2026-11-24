package com.devtunasoft.tuneros.qa59

import java.io.File
import kotlin.system.exitProcess

/** QA-59 host entry: generate pinned scratch bytes or run a bounded verification mode. */
object Qa59HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val modes = setOf(
            "generate", "verify", "verify-manifest", "verify-pinned", "verify-semantic",
        )
        if (args.size != 2 || !modes.contains(args[0])) {
            println("usage: Qa59HostMain generate|verify|verify-manifest|verify-pinned|verify-semantic <dir>")
            exitProcess(2)
        }
        val dir = File(args[1])
        if (args[0] == "generate") {
            if (!dir.isDirectory && !dir.mkdirs()) {
                println("FIRST\tGENERATE_DIR")
                exitProcess(1)
            }
            val generated = Qa59Contract.generate()
            for (name in Qa59Contract.ALL_FILES) File(dir, name).writeBytes(generated.getValue(name))
            println("GENERATE\tPASS\tfiles=" + Qa59Contract.ALL_FILES.size)
            return
        }
        val files = LinkedHashMap<String, ByteArray>()
        for (name in Qa59Contract.ALL_FILES) {
            val file = File(dir, name)
            if (!file.isFile) {
                println("FIRST\tMISSING_FIXTURE")
                println("VERDICT\tFAIL")
                exitProcess(1)
            }
            files[name] = file.readBytes()
        }
        exitProcess(Qa59Contract.verifyModel(args[0], files) { println(it) })
    }
}
