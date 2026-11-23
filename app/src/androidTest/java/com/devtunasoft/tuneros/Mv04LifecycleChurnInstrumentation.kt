package com.devtunasoft.tuneros

import android.app.Activity
import android.app.Instrumentation
import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * MV-04: deterministic, zero-dependency acceptance source for multiview lifecycle churn.
 *
 * The fixed, synthetic trace is served only from a 127.0.0.1 scratch socket. Each step creates,
 * focuses or releases a bounded tile backed by one synthetic player token and one real
 * HandlerThread. The harness asserts the same contract as the JVM verifier in
 * qa/fixtures/mv04/Mv04Scenario.java: at most two tiles, exactly one audio owner whenever a tile
 * exists, refusal/fallback without a third player, and complete teardown with no live player or
 * named handler thread. Physical audio output is not measurable here; "audible" means the single
 * audio owner in this harness, not a claim about device speaker state, first frames or media
 * decoding. No provider URL, credential, DRM key or private media is used.
 */
class Mv04LifecycleChurnInstrumentation : Instrumentation() {

    private data class Outcome(val name: String, val passed: Boolean, val detail: String)

    private class Tile(val id: Int, val slot: Int, val player: Any, val handler: HandlerThread)

    private class Pool {
        val active = TreeMap<Int, Tile>()
        val livePlayers: MutableSet<Any> =
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val liveThreads: MutableSet<HandlerThread> =
            Collections.newSetFromMap(IdentityHashMap<HandlerThread, Boolean>())
        var audible: Int? = null
            private set
        var refusals: Int = 0
            private set
        var created: Int = 0
            private set
        var released: Int = 0
            private set

        fun apply(operation: String, argument: Int): String = when (operation) {
            "ACQUIRE" -> {
                if (active.size >= CAPACITY) {
                    refusals++
                    // Fallback: focus an existing tile; never create a third player or thread.
                    if (active.isNotEmpty()) audible = active.firstKey()
                    "REFUSED"
                } else {
                    var slot = 1
                    while (active.values.any { it.slot == slot }) slot++
                    val handler = HandlerThread("mv04-tile-$argument")
                    handler.start()
                    val player = Any()
                    val tile = Tile(argument, slot, player, handler)
                    active[argument] = tile
                    livePlayers.add(player)
                    liveThreads.add(handler)
                    created++
                    if (audible == null) audible = argument
                    "CREATED"
                }
            }
            "RELEASE" -> {
                if (!active.containsKey(argument)) {
                    refusals++
                    "REFUSED"
                } else {
                    releaseInternal(argument)
                    "RELEASED"
                }
            }
            "FOCUS" -> {
                if (!active.containsKey(argument)) {
                    refusals++
                    "REFUSED"
                } else {
                    audible = argument
                    "FOCUSED"
                }
            }
            "TEARDOWN" -> {
                teardown()
                "CLEAN"
            }
            else -> throw AssertionError("unknown churn operation: $operation")
        }

        fun releaseInternal(id: Int) {
            val tile = active.remove(id) ?: return
            livePlayers.remove(tile.player)
            liveThreads.remove(tile.handler)
            runCatching { tile.handler.quit() }
            runCatching { tile.handler.join(POOL_JOIN_TIMEOUT_MILLIS) }
            released++
            if (audible == id) audible = if (active.isEmpty()) null else active.firstKey()
        }

        fun teardown() {
            for (id in active.keys.toList()) releaseInternal(id)
            audible = null
        }

        fun state(outcome: String): String = listOf(
            outcome,
            if (active.isEmpty()) "-" else active.keys.joinToString(","),
            audible?.toString() ?: "-",
            livePlayers.size.toString(),
            liveThreads.size.toString(),
            refusals.toString(),
        ).joinToString("\t")

        private companion object {
            const val POOL_JOIN_TIMEOUT_MILLIS = 2_000L
        }
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // The hidden platform run() hook is not reliably dispatched; run on a bounded thread
        // and finish() explicitly. The shell runner supplies the outer timeout.
        Thread({ runChecks() }, "mv04-lifecycle-runner").apply { isDaemon = false }.start()
    }

    private fun runChecks() {
        val outcomes = ArrayList<Outcome>()
        var fixture: LoopbackFixture? = null
        var pool: Pool? = null
        try {
            fixture = LoopbackFixture(context.assets)
            val loopback = fixture
            val trace = loopback.readTrace()
            val lines = trace.lines().filter { it.isNotBlank() }
            val errors = ArrayList<String>()
            val model = Pool()
            pool = model

            outcomes += execute("mv04-loopback-fixture") {
                require(lines.isNotEmpty()) { "empty scratch trace" }
                require(trace.startsWith("1\tACQUIRE\t")) { "trace does not start with tile creation" }
                require(lines.size <= MAX_STEPS) { "unbounded trace: ${lines.size} steps" }
                require(loopback.requestCount() in 1..LoopbackFixture.MAX_REQUESTS) {
                    "unexpected loopback request count: ${loopback.requestCount()}"
                }
                "trace=${lines.size} steps, loopback=127.0.0.1 requests=${loopback.requestCount()}"
            }

            var audibleSteps = 0
            var refusalSteps = 0
            var fallbackSteps = 0
            var focusedSteps = 0
            for (index in lines.indices) {
                val field = lines[index].split('\t')
                if (field.size != FIELDS) {
                    errors += "seq=${index + 1} fields=${field.size} expected=$FIELDS"
                    break
                }
                val sequence = field[0].toIntOrNull()
                if (sequence != index + 1) errors += "seq=${index + 1} got=${field[0]}"
                val beforeActive = model.active.size
                val beforePlayers = model.livePlayers.size
                val beforeThreads = model.liveThreads.size
                val outcome = model.apply(field[1], field[2].toIntOrNull() ?: -1)
                val observed = model.state(outcome)
                val expected = field.drop(3).joinToString("\t")
                if (observed != expected) {
                    errors += "seq=${index + 1} observed=$observed expected=$expected"
                }
                if (model.active.size > CAPACITY) errors += "seq=${index + 1} capacity"
                if (model.active.isNotEmpty()) {
                    audibleSteps++
                    val owner = model.audible
                    if (owner == null || !model.active.containsKey(owner)) {
                        errors += "seq=${index + 1} audible-count"
                    }
                } else if (model.audible != null) {
                    errors += "seq=${index + 1} audible-without-tile"
                }
                if (outcome == "REFUSED") {
                    refusalSteps++
                    val unchanged = model.active.size == beforeActive &&
                        model.livePlayers.size == beforePlayers &&
                        model.liveThreads.size == beforeThreads
                    if (!unchanged) errors += "seq=${index + 1} refusal-created-resource"
                    val owner = model.audible
                    if (owner != null && model.active.containsKey(owner)) fallbackSteps++
                }
                if (outcome == "FOCUSED") focusedSteps++
            }

            outcomes += execute("mv04-bounded-churn") {
                require(errors.isEmpty()) { errors.take(3).joinToString("; ") }
                require(model.created >= 8 && model.released >= 8) {
                    "cycles created=${model.created} released=${model.released}"
                }
                require(model.created <= MAX_CREATED) { "created=${model.created}" }
                "${model.created} create/release cycles, ${lines.size} bounded steps"
            }
            outcomes += execute("mv04-exactly-one-audible") {
                require(audibleSteps > 0 && focusedSteps >= 2) {
                    "audibleSteps=$audibleSteps focusedSteps=$focusedSteps"
                }
                require(errors.none { it.contains("audible") }) {
                    errors.filter { it.contains("audible") }.take(3).joinToString("; ")
                }
                "$audibleSteps populated steps with one audio owner; $focusedSteps explicit switches"
            }
            outcomes += execute("mv04-capacity-refusal-fallback") {
                require(refusalSteps >= 2 && fallbackSteps == refusalSteps) {
                    "refusals=$refusalSteps fallback=$fallbackSteps"
                }
                require(errors.none { it.contains("refusal") || it.contains("capacity") }) {
                    errors.filter { it.contains("refusal") || it.contains("capacity") }
                        .take(3).joinToString("; ")
                }
                "$refusalSteps refusals fell back to an existing tile; no third player"
            }
            outcomes += execute("mv04-clean-teardown") {
                require(lines.lastOrNull()?.contains("TEARDOWN") == true) { "no teardown step" }
                require(model.active.isEmpty() && model.audible == null) {
                    "active=${model.active.keys} audible=${model.audible}"
                }
                "all tiles released; no audio owner remains"
            }
            outcomes += execute("mv04-no-leaked-players") {
                require(model.livePlayers.isEmpty()) { "livePlayers=${model.livePlayers.size}" }
                require(model.released == model.created) {
                    "released=${model.released} created=${model.created}"
                }
                "${model.released} synthetic players released by identity"
            }
            val leakedNames = Thread.getAllStackTraces().keys
                .filter { it.name.startsWith("mv04-tile-") && it.isAlive }
                .map { it.name }
            outcomes += execute("mv04-no-leaked-handler-threads") {
                require(model.liveThreads.isEmpty()) { "tracked threads=${model.liveThreads.size}" }
                require(leakedNames.isEmpty()) { "live named threads=$leakedNames" }
                "no tracked or system-visible mv04-tile handler thread survived"
            }
        } catch (error: Throwable) {
            outcomes += Outcome("mv04-runner", false, error.message ?: error.javaClass.simpleName)
        } finally {
            pool?.teardown()
            fixture?.close()
        }

        val failures = outcomes.count { !it.passed }
        val report = buildString {
            append("MV-04 multiview lifecycle churn | device=").append(Build.MODEL)
            append(" | checks=").append(outcomes.size).append(" | failures=").append(failures).append('\n')
            for (outcome in outcomes) {
                append(if (outcome.passed) "PASS\t" else "FAIL\t")
                append(outcome.name).append('\t').append(outcome.detail).append('\n')
            }
            append("SUMMARY\t").append(outcomes.size - failures).append(" PASS\t")
                .append(failures).append(" FAIL\n")
        }
        runCatching { File(targetContext.filesDir, REPORT_NAME).writeText(report) }
        finish(
            if (failures == 0 && outcomes.size == REQUIRED_CHECKS) Activity.RESULT_OK else 1,
            Bundle().apply {
                putInt("mv04_checks", outcomes.size)
                putInt("mv04_failures", failures)
            },
        )
    }

    private fun execute(name: String, body: () -> String): Outcome = try {
        Outcome(name, true, body())
    } catch (error: Throwable) {
        Outcome(name, false, error.message ?: error.javaClass.simpleName)
    }

    /** Serves one synthetic trace file to the harness; all other paths 404. */
    private class LoopbackFixture(assets: AssetManager) {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        private val trace: String
        private val thread: Thread
        private val requestedPaths = Collections.synchronizedList(ArrayList<String>())
        @Volatile
        private var running = true

        init {
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            trace = assets.open("mv04/churn.trace").use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_TRACE_BYTES) { "scratch trace asset too large: $total" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
            require(trace.startsWith("1\tACQUIRE\t")) { "invalid scratch trace asset" }
            thread = Thread({ serve() }, "mv04-fixture").apply { isDaemon = true }
            thread.start()
        }

        fun requestCount(): Int = synchronized(requestedPaths) { requestedPaths.size }

        fun readTrace(): String {
            val connection = URL("http://127.0.0.1:$port/churn.trace").openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            try {
                require(connection.responseCode == 200) { "loopback response=${connection.responseCode}" }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(4_096)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_TRACE_BYTES) { "loopback response too large: $total" }
                        output.write(buffer, 0, count)
                    }
                }
                return String(output.toByteArray(), StandardCharsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        fun close() {
            running = false
            runCatching { server.close() }
            thread.join(JOIN_TIMEOUT_MILLIS)
        }

        private fun serve() {
            var served = 0
            while (running && served < MAX_REQUESTS) {
                try {
                    serveOne(server.accept())
                    served++
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    return
                }
            }
        }

        private fun serveOne(socket: Socket) {
            socket.use { connection ->
                connection.soTimeout = READ_TIMEOUT_MILLIS
                val request = BufferedReader(
                    InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                ).readLine() ?: return
                val path = request.split(' ').getOrElse(1) { "" }
                synchronized(requestedPaths) {
                    if (requestedPaths.size < MAX_REQUESTS) requestedPaths += path
                }
                val known = path == "/churn.trace"
                val body = if (known) trace else ""
                val status = if (known) "200 OK" else "404 Not Found"
                val payload = body.toByteArray(StandardCharsets.UTF_8)
                val header = ("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8)
                connection.getOutputStream().use { output ->
                    output.write(header)
                    output.write(payload)
                    output.flush()
                }
            }
        }

        companion object {
            const val ACCEPT_TIMEOUT_MILLIS = 1_000
            const val CONNECT_TIMEOUT_MILLIS = 2_000
            const val READ_TIMEOUT_MILLIS = 2_000
            const val JOIN_TIMEOUT_MILLIS = 2_000L
            const val MAX_REQUESTS = 8
            const val MAX_TRACE_BYTES = 65_536
        }
    }

    private companion object {
        const val CAPACITY = 2
        const val MAX_STEPS = 64
        const val MAX_CREATED = 24
        const val FIELDS = 9
        const val REQUIRED_CHECKS = 7
        const val REPORT_NAME = "mv04-lifecycle-report.txt"
    }
}
