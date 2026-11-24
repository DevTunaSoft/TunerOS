package com.devtunasoft.tuneros.qa48

/** Android-free mirror of the QA-48 fixture contract; also runs on the JVM. */
object Qa48Mirror {
    const val SCHEMA = "qa48-capture-storage-1"
    const val SEED = "qa48-seed-20261124"
    const val CASES = 15
    const val CORRUPT = 8
    const val BUFFER = 4096
    const val MIN_FREE = 512L
    const val DIGEST = "591084c3b6e675c94e32811c4b15faa09311879930c84a727e7e6447a572b481"

    @JvmStatic
    fun main(args: Array<String>) {
        runChecks().forEach { println(it) }
    }

    @JvmStatic
    fun runChecks(): List<String> {
        val out = ArrayList<String>()
        fun check(id: String, ok: Boolean) {
            out.add((if (ok) "PASS" else "FAIL") + "\t" + id)
        }
        check("MIRROR_SCHEMA", SCHEMA.startsWith("qa48-"))
        check("MIRROR_SEED", SEED.length > 8)
        check("MIRROR_CASE_COUNT", CASES == 15)
        check("MIRROR_CORRUPT_COUNT", CORRUPT == 8)
        check("MIRROR_BUFFER_BOUND", BUFFER in 4096..(1024 * 1024))
        check("MIRROR_MIN_FREE", MIN_FREE > 0L)
        check("MIRROR_DIGEST_SHAPE", DIGEST.length == 64 && DIGEST.all { it in '0'..'9' || it in 'a'..'f' })
        // Bounded capacity model mirrored from RecordingPolicy.remainingFreeBytes.
        check("MIRROR_CAPACITY_NULL", remaining(null, 0L) == 0L)
        check("MIRROR_CAPACITY_ZERO", remaining(0L, 0L) == 0L)
        check("MIRROR_CAPACITY_PARTIAL", remaining(1000L, 300L) == 700L)
        check("MIRROR_CAPACITY_OVERDRAWN", remaining(1000L, 5000L) == 0L)
        return out
    }

    private fun remaining(reported: Long?, written: Long): Long {
        require(written >= 0)
        if (reported == null || reported <= 0L) return 0L
        return if (written >= reported) 0L else reported - written
    }
}
