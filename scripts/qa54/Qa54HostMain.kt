package com.devtunasoft.tuneros.qa54

import java.io.File
import kotlin.system.exitProcess

/** Host entry point. The declared object name keeps the JVM class name stable for the runner. */
object Qa54HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            println("usage: Qa54HostMain <generate|verify|verify-byte|verify-manifest|verify-pinned|verify-mutant|verify-device> <fixture-dir>")
            exitProcess(2)
        }
        val mode = args[0]
        if (mode == "generate") {
            Qa54Contract.generate(File(args[1]))
            exitProcess(0)
        }
        val rc = try {
            Qa54Contract.run(mode, File(args[1]))
        } catch (t: Throwable) {
            println("FIRST\tGATE_EXCEPTION")
            println("EXCEPTION\t" + t.javaClass.simpleName)
            println("VERDICT\tFAIL")
            1
        }
        exitProcess(rc)
    }
}
