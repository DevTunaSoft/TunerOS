package com.devtunasoft.tuneros.qa56

import java.io.File
import kotlin.system.exitProcess

/** QA-56 host entry: generates scratch bytes or verifies committed fixture bytes. */
object Qa56HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2 || (args[0] != "generate" && args[0] != "verify" &&
                !args[0].startsWith("verify-"))) {
            println("usage: Qa56HostMain generate|verify|verify-<slice>[:<focus>] <dir>")
            exitProcess(2)
        }
        val mode = args[0]
        val dir = File(args[1])
        if (mode == "generate") {
            if (!dir.isDirectory && !dir.mkdirs()) {
                println("FIRST\tGENERATE_DIR")
                exitProcess(1)
            }
            val generated = Qa56Contract.generate()
            for (name in Qa56Contract.ALL_FILES) File(dir, name).writeBytes(generated.getValue(name))
            println("GENERATE\tPASS\tfiles=" + Qa56Contract.ALL_FILES.size)
            return
        }
        val files = LinkedHashMap<String, ByteArray>()
        for (name in Qa56Contract.ALL_FILES) {
            val file = File(dir, name)
            if (!file.isFile) {
                println("FIRST\tMISSING_FIXTURE")
                println("VERDICT\tFAIL")
                exitProcess(1)
            }
            files[name] = file.readBytes()
        }
        val rc = Qa56Contract.verifyModel(mode, files) { println(it) }
        exitProcess(if (rc == 0) 0 else 1)
    }
}
