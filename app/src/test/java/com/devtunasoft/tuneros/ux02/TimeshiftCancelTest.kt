package com.devtunasoft.tuneros.ux02

import com.devtunasoft.tuneros.TimeshiftRecorder
import com.devtunasoft.tuneros.core.HlsCaptureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * UX-02: cancelling a timeshift must end it as CANCELLED and remove every ring file.
 * The HLS origin is a loopback fixture with synthetic playlist and segment bytes.
 */
class TimeshiftCancelTest {

    @Test
    fun cancellingAfterSegmentsRemovesTheWholeRing() {
        val polls = AtomicInteger(1)
        val stopped = AtomicBoolean(false)
        val segment = ByteArray(64) { (it % 7).toByte() }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 500
        val serverThread = thread(name = "ux02-hls-fixture", isDaemon = true) {
            while (!stopped.get()) {
                try {
                    val socket = server.accept()
                    socket.use { client ->
                        client.soTimeout = 2_000
                        val request = BufferedReader(InputStreamReader(client.getInputStream())).readLine() ?: return@use
                        val path = request.split(' ').getOrNull(1) ?: return@use
                        val body = if (path.startsWith("/seg-")) {
                            segment
                        } else {
                            val latest = polls.getAndIncrement() + 1
                            val lines = ArrayList<String>()
                            lines += "#EXTM3U"
                            lines += "#EXT-X-TARGETDURATION:2"
                            lines += "#EXT-X-MEDIA-SEQUENCE:0"
                            for (sequence in 0..latest) {
                                lines += "#EXTINF:2.0,"
                                lines += "seg-$sequence.ts"
                            }
                            lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
                        }
                        val contentType = if (path.startsWith("/seg-")) "video/mp2t" else "application/vnd.apple.mpegurl"
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $contentType\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        client.getOutputStream().write(headers + body)
                        client.getOutputStream().flush()
                    }
                } catch (expected: Exception) {
                    // A timeout is the loop's exit check; a closed fixture is fine during teardown.
                }
            }
        }
        val ring = File(
            "C:/Users/X1/AppData/Local/hermes/cache/scratch/ux02-timeshift-ring",
            "ring-" + System.nanoTime(),
        )
        try {
            val recorder = TimeshiftRecorder(ring, maxSegments = 8, maxBytes = 64L * 1024L)
            recorder.start("http://127.0.0.1:${server.localPort}/live.m3u8")
            val deadline = System.currentTimeMillis() + 20_000L
            while (recorder.completedSegments < 3 &&
                recorder.state !is TimeshiftRecorder.State.Stopped &&
                recorder.state !is TimeshiftRecorder.State.Failed &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20L)
            }
            assertTrue(
                "The fixture must deliver at least three segments, got ${recorder.completedSegments} (${recorder.state})" +
                    (if (recorder.state is TimeshiftRecorder.State.Failed) " error=" +
                        (recorder.state as TimeshiftRecorder.State.Failed).error else ""),
                recorder.completedSegments >= 3,
            )

            recorder.stop()
            val stopDeadline = System.currentTimeMillis() + 20_000L
            while (recorder.state is TimeshiftRecorder.State.Idle ||
                recorder.state is TimeshiftRecorder.State.Recording
            ) {
                if (System.currentTimeMillis() > stopDeadline) break
                Thread.sleep(20L)
            }
            val state = recorder.state
            assertTrue("Cancel must report CANCELLED, got $state", state is TimeshiftRecorder.State.Stopped)
            assertEquals(HlsCaptureEngine.StopReason.CANCELLED, (state as TimeshiftRecorder.State.Stopped).reason)
            assertFalse("The ring directory must be gone", ring.exists())
            assertEquals(0, ring.listFiles().orEmpty().count { it.name.startsWith("seg-") })
        } finally {
            stopped.set(true)
            server.close()
            serverThread.join(2_000L)
            ring.deleteRecursively()
        }
    }
}
