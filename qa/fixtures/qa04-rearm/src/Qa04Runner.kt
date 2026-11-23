package com.devtunasoft.tuneros

import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Deterministic PASS/FAIL lines for the QA-04 shell gate. */
class Qa04ReportListener : RunListener() {
    private val failed = HashSet<String>()

    override fun testFailure(failure: Failure) {
        failed += failure.description.methodName
        val reason = failure.exception?.message?.lineSequence()?.firstOrNull().orEmpty()
        println("FAIL\t${failure.description.methodName}\t${reason.take(160)}")
    }

    override fun testFinished(description: Description) {
        if (description.methodName !in failed) {
            println("PASS\t${description.methodName}\tproduction store and scheduler")
        }
    }
}

fun main() {
    val listener = Qa04ReportListener()
    val core = JUnitCore()
    core.addListener(listener)
    val result: Result = core.run(Qa04SchedulesTest::class.java)
    println("SUMMARY\truns=${result.runCount}\tfailures=${result.failureCount}\tignored=${result.ignoreCount}")
    if (!result.wasSuccessful()) System.exit(1)
}
