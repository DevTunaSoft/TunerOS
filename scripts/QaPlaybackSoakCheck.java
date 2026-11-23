import com.devtunasoft.tuneros.core.Channel;
import com.devtunasoft.tuneros.core.PlaylistParser;
import com.devtunasoft.tuneros.core.PlayerReusePolicy;
import com.devtunasoft.tuneros.core.StreamDiagnosis;
import com.devtunasoft.tuneros.core.StreamDoctor;
import com.devtunasoft.tuneros.core.StreamEvidence;
import com.devtunasoft.tuneros.core.TransportFault;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QA-03 harness: deterministic, bounded playback-regression and channel-switching soak.
 *
 * It calls only EXISTING core entry points (PlaylistParser, PlayerReusePolicy,
 * StreamDoctor) and a short-lived loopback HTTP source on 127.0.0.1. There is no
 * provider network, no Android SDK, no decoder, no media file and no credential.
 * A "first frame" is the synthetic QA frame marker below: this is controller and
 * lifecycle evidence, not proof of a decoded video frame.
 */
public final class QaPlaybackSoakCheck {

    private static final int ITERATIONS = 128;
    private static final int FRAME_BYTES = 32;
    private static final int FRAME_COUNT = 16;
    private static final int MAX_BODY_BYTES = 4096;
    private static final int TIMEOUT_MS = 2_000;
    private static final byte[] MAGIC = {'Q', 'A', 'F', '1'};
    private static final long MAX_HEAP_GROWTH = 48L * 1024 * 1024;

    // Synthetic sentinels only. They are never printed; they prove the report and
    // the shell-side scan reject provider URLs, credentials, DRM keys and payloads.
    private static final String[] SENTINELS = {
        "SENTINEL-PROVIDER-URL-9f2c",
        "SENTINEL-CREDENTIAL-51c9",
        "SENTINEL-DRM-KEY-7a31",
        "SENTINEL-RECORDING-PAYLOAD-b442",
    };

    private static final StringBuilder EMITTED = new StringBuilder(4096);

    private static void emit(String line) {
        EMITTED.append(line).append('\n');
        System.out.println(line);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            emit("FAIL " + message);
            throw new AssertionError(message);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private static boolean containsSentinel(String text) {
        for (String sentinel : SENTINELS) {
            if (text.contains(sentinel)) return true;
        }
        return false;
    }

    /** Fixed deterministic frame stream; a frame is the smallest observable playback unit here. */
    private static byte[] framePayload() {
        byte[] payload = new byte[FRAME_BYTES * FRAME_COUNT];
        long mix = 20261123L;
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            int start = frame * FRAME_BYTES;
            System.arraycopy(MAGIC, 0, payload, start, MAGIC.length);
            payload[start + 4] = (byte) frame;
            payload[start + 5] = (byte) (frame >>> 8);
            for (int i = 6; i < FRAME_BYTES; i++) {
                mix = mix * 6364136223846793005L + 1442695040888963407L;
                payload[start + i] = (byte) (mix >>> 33);
            }
        }
        return payload;
    }

    /** Short-lived in-process loopback source. Two daemon handler threads, stopped and joined on close. */
    private static final class Loopback implements AutoCloseable {
        final HttpServer server;
        final ExecutorService pool;
        final AtomicInteger requests = new AtomicInteger();
        final byte[] payload = framePayload();

        Loopback() throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            pool = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "qa-playback-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(pool);
            server.createContext("/frame/", this::frame);
            server.createContext("/missing.mp4", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.createContext("/page.html", exchange -> {
                requests.incrementAndGet();
                byte[] body = "<!doctype html><title>not a stream</title>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.createContext("/truncated.bin", exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload, 0, 8); // deliberately short, then clean close
                }
            });
            server.start();
        }

        private void frame(HttpExchange exchange) throws java.io.IOException {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        URL url(String path) throws Exception {
            return new URL("http://127.0.0.1:" + port() + path);
        }

        @Override
        public void close() throws Exception {
            server.stop(0);
            pool.shutdownNow();
            check(pool.awaitTermination(2, TimeUnit.SECONDS), "loopback handler threads did not stop");
        }
    }

    /** One fake player. It uses a real bounded HTTP read against the loopback source. */
    private static final class FakePlayer {
        final int index;
        boolean released;
        boolean opened;
        boolean firstFrame;
        boolean truncated;
        int frames;
        int bytes;
        HttpURLConnection connection;

        FakePlayer(Loopback loopback, int index, boolean readImmediately) throws Exception {
            this.index = index;
            if (!readImmediately) return; // preempted before it ever opened a source
            connection = (HttpURLConnection) loopback.url("/frame/" + index + ".bin").openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            opened = true;
            int status = connection.getResponseCode();
            check(status == 200, "loopback frame source returned " + status);
            try (InputStream in = connection.getInputStream()) {
                byte[] block = new byte[64];
                int matched = 0;
                int count;
                while ((count = in.read(block)) != -1) {
                    bytes += count;
                    check(bytes <= MAX_BODY_BYTES, "loopback body exceeded the byte cap");
                    for (int i = 0; i < count; i++) {
                        if (block[i] == MAGIC[matched]) {
                            matched++;
                            if (matched == MAGIC.length) {
                                frames++;
                                matched = 0;
                            }
                        } else {
                            matched = block[i] == MAGIC[0] ? 1 : 0;
                        }
                    }
                }
                check(matched == 0, "partial frame marker at EOF");
            } finally {
                connection.disconnect();
            }
            truncated = bytes != loopback.payload.length;
            firstFrame = frames > 0 && !truncated;
        }

        void release() {
            check(!released, "player " + index + " was released twice");
            released = true;
            opened = false; // connection is closed by the bounded read / disconnect
        }
    }

    private static final class Trace {
        final String digest;
        final int created;
        final int released;
        final int reused;
        final int preempted;

        Trace(String digest, int created, int released, int reused, int preempted) {
            this.digest = digest;
            this.created = created;
            this.released = released;
            this.reused = reused;
            this.preempted = preempted;
        }
    }

    /** Deterministic switch driver. Every decision goes through the existing core policy. */
    private static final class Controller {
        final Loopback loopback;
        final List<Map<String, String>> headers = new ArrayList<>(ITERATIONS);
        final StringBuilder trace = new StringBuilder(ITERATIONS * 48);
        int created;
        int released;
        int reused;
        int preempted;

        Controller(Loopback loopback, Path fixture) throws Exception {
            this.loopback = loopback;
            List<Channel> fromFixture = new ArrayList<>();
            if (fixture != null && Files.exists(fixture)) {
                try (BufferedReader reader = Files.newBufferedReader(fixture, StandardCharsets.UTF_8)) {
                    fromFixture.addAll(PlaylistParser.INSTANCE.parse(reader, ITERATIONS));
                }
            }
            check(fromFixture.size() == ITERATIONS,
                "fixture playlist did not supply " + ITERATIONS + " channels (got " + fromFixture.size() + ")");
            for (int i = 0; i < ITERATIONS; i++) {
                // Only the index is used. Fixture stream URLs are never read into log output.
                Channel channel = fromFixture.get(i);
                check(channel != null && !channel.getId().isEmpty(), "fixture channel " + i + " has no id");
                Map<String, String> header = new LinkedHashMap<>();
                header.put("Referer", "https://qa-provider-" + ((i / 8) % 4) + ".invalid/");
                header.put("X-QA-Profile", ((i / 8) % 2 == 0) ? "A" : "B");
                headers.add(header);
            }
        }

        Trace run() throws Exception {
            FakePlayer current = null;
            Map<String, String> currentHeaders = null;
            for (int i = 0; i < ITERATIONS; i++) {
                Map<String, String> next = headers.get(i);
                boolean playingRecording = i % 17 == 0;
                boolean reuse = PlayerReusePolicy.INSTANCE.canReuse(currentHeaders, next, playingRecording);
                if (reuse) {
                    check(current != null && !current.released, "reuse decision without a live player");
                    reused++;
                    trace.append(i).append("|reuse|live=1\n");
                } else {
                    if (current != null) {
                        current.release();
                        released++;
                    }
                    current = new FakePlayer(loopback, i, true);
                    check(current.firstFrame, "loopback first frame not observed for switch " + i);
                    created++;
                    currentHeaders = next;
                    trace.append(i).append("|create|live=1\n");
                }
                check(created - released == 1, "switch " + i + " left an unexpected player count");
                if (i % 11 == 3) {
                    // Rapid switch away before the next frame: the half-open player must be released.
                    current.release();
                    released++;
                    current = null;
                    currentHeaders = null;
                    preempted++;
                    trace.append(i).append("|preempt|live=0\n");
                }
            }
            if (current != null) {
                current.release();
                released++;
            }
            check(created == released, "player creation/release mismatch");
            check(created >= ITERATIONS / 4, "soak created too few players to be meaningful");
            check(reused > 0, "soak never exercised a reuse decision");
            check(preempted > 0, "soak never exercised a pre-first-frame switch");
            return new Trace(sha256(trace.toString().getBytes(StandardCharsets.UTF_8)),
                created, released, reused, preempted);
        }
    }

    /** The fixed failure taxonomy, checked against the existing StreamDoctor decision table. */
    private static final Object[][] TAXONOMY = {
        {-102, "Provider sign-in expired"}, {-103, "Provider premium access needed"},
        {-104, "Provider stream limit"}, {-105, "Parental restriction"},
        {-106, "Region restriction"}, {-108, "Playback setup required"},
        {1002, "Live window moved"}, {1003, "Connection timed out"},
        {2000, "Media input/output error"}, {2001, "Network connection failed"},
        {2002, "Connection timed out"}, {2003, "Unexpected content type"},
        {2004, "Stream server rejected playback"}, {2005, "Media not found"},
        {2006, "Media access denied"}, {2007, "Insecure HTTP blocked"},
        {2008, "Stream read position unavailable"}, {3001, "Malformed media or guide"},
        {3003, "Unsupported media format"}, {4001, "Playback decoder problem"},
        {5001, "Device audio output failed"}, {6000, "Protected media issue"},
        {6099, "Playback failed"}, {Integer.MAX_VALUE, "Playback failed"},
    };

    private static void taxonomy() {
        String hostile = SENTINELS[0] + "?" + SENTINELS[1] + "=" + SENTINELS[2] + "&body=" + SENTINELS[3];
        StreamEvidence hostileEvidence = new StreamEvidence(999, hostile, TransportFault.TLS);
        for (Object[] row : TAXONOMY) {
            int code = (Integer) row[0];
            String expected = (String) row[1];
            StreamDiagnosis diagnosis = StreamDoctor.INSTANCE.diagnose(code);
            check(diagnosis != null && expected.equals(diagnosis.getIssue()),
                "taxonomy code " + code + " was not " + expected);
            check(diagnosis.getNextStep() != null && !diagnosis.getNextStep().isEmpty(),
                "taxonomy code " + code + " has no next step");
            String report = StreamDoctor.INSTANCE.report(code, hostileEvidence);
            check(report.length() <= 400, "taxonomy report for " + code + " is not bounded");
            check(report.contains("playback error code: " + code), "taxonomy report lost its code");
            check(report.contains(expected), "taxonomy report lost its issue");
            check(!containsSentinel(report), "taxonomy report leaked a sensitive sentinel");
            check(!report.toLowerCase(Locale.ROOT).contains("http://"), "taxonomy report leaked a URL scheme");
        }
        String detail = StreamDoctor.INSTANCE.describe(hostileEvidence);
        check(detail.equals("TLS handshake failed"), "hostile evidence was not reduced to a fixed transport label");
        check(detail.length() <= 64, "evidence detail is not bounded");
        emit("PASS playback-failure-taxonomy cases=" + TAXONOMY.length);
    }

    /** Exercises real loopback HTTP responses, mapped onto the existing taxonomy. */
    private static void loopbackFailures(Loopback loopback) throws Exception {
        int[][] cases = {{404, 2005}, {200, 2003}};
        for (int[] test : cases) {
            HttpURLConnection connection = (HttpURLConnection) loopback.url(
                test[0] == 404 ? "/missing.mp4" : "/page.html").openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int status = connection.getResponseCode();
            check(status == test[0], "loopback diagnostic returned the wrong status");
            String type = StreamDoctor.INSTANCE.safeContentType(connection.getHeaderField("Content-Type"));
            StreamEvidence evidence = new StreamEvidence(status, type, null);
            String report = StreamDoctor.INSTANCE.report(test[1], evidence);
            check(!containsSentinel(report), "loopback diagnostic report leaked a sentinel");
            check(report.contains("HTTP " + status), "loopback diagnostic dropped its status");
            if (test[0] == 200) check(report.contains("content type text/html"), "html type was not reported");
            connection.disconnect();
        }
        // A clean short body must be detected locally, never accepted as a complete first frame.
        HttpURLConnection shortBody = (HttpURLConnection) loopback.url("/truncated.bin").openConnection();
        shortBody.setConnectTimeout(TIMEOUT_MS);
        shortBody.setReadTimeout(TIMEOUT_MS);
        int bytes = 0;
        try (InputStream in = shortBody.getInputStream()) {
            byte[] block = new byte[16];
            int count;
            while ((count = in.read(block)) != -1) bytes += count;
        } finally {
            shortBody.disconnect();
        }
        check(bytes == 8, "truncated loopback body was not detected as short");
        String shortReport = StreamDoctor.INSTANCE.report(2001, new StreamEvidence(200, "application/octet-stream", null));
        check(!containsSentinel(shortReport), "truncated report leaked a sentinel");
        emit("PASS playback-loopback-failures cases=3");
    }

    private static Set<String> qaThreads() {
        Set<String> names = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("qa-playback")) names.add(thread.getName());
        }
        return names;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public static void main(String[] args) throws Exception {
        Path fixture = args.length > 0 ? Paths.get(args[0]) : null;
        long heapBefore = usedHeap();
        int threadCountBefore = Thread.activeCount();
        Loopback loopback = new Loopback();
        try {
            Controller first = new Controller(loopback, fixture);
            Trace traceA = first.run();
            Controller second = new Controller(loopback, fixture);
            Trace traceB = second.run();
            check(traceA.digest.equals(traceB.digest), "switch trace was not deterministic");
            check(traceA.created == traceB.created && traceA.reused == traceB.reused,
                "switch counters were not deterministic");
            emit("PASS playback-soak-determinism sha256=" + traceA.digest
                + " iterations=" + ITERATIONS);
            emit("PASS playback-soak-no-leaked-players created=" + traceA.created
                + " released=" + traceA.released + " active=0 reused=" + traceA.reused
                + " preempted=" + traceA.preempted);
            emit("PASS playback-loopback-first-frame frames=" + FRAME_COUNT
                + " bytes=" + (FRAME_BYTES * FRAME_COUNT) + " requests=" + loopback.requests.get());

            taxonomy();
            loopbackFailures(loopback);
        } finally {
            loopback.close();
        }
        Set<String> leftover = qaThreads();
        check(leftover.isEmpty(), "loopback handler threads survived close: " + leftover.size());
        int threadCountAfter = Thread.activeCount();
        check(threadCountAfter <= threadCountBefore + 4,
            "unexpected thread growth: " + threadCountBefore + " -> " + threadCountAfter);
        emit("PASS playback-soak-no-leaked-threads before=" + threadCountBefore
            + " after=" + threadCountAfter);
        long heapGrowth = Math.max(0L, usedHeap() - heapBefore);
        check(heapGrowth < MAX_HEAP_GROWTH, "heap growth exceeded the bound: " + heapGrowth);
        emit("PASS playback-soak-bounded-memory heapGrowthBytes=" + heapGrowth);
        check(!containsSentinel(EMITTED.toString()), "a sensitive sentinel reached the emitted report");
        emit("PASS playback-privacy-no-sensitive-tokens");
        emit("PLAYBACK SOAK CHECK PASS");
    }
}
