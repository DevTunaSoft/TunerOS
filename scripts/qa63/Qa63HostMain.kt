package com.devtunasoft.tuneros.qa63

/** Offline JVM entry point. The gate logic lives in the shared Android-free contract. */
object Qa63HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        kotlin.system.exitProcess(Qa63Contract.run(args))
    }
}
