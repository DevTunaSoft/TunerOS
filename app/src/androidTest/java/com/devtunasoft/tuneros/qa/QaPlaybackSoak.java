package com.devtunasoft.tuneros.qa;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA-03 playback switching fixture. Pure Java and dependency-free on purpose: the
 * same source is javac-compiled for the bounded JVM check and compiled by AGP into
 * app/src/androidTest for the on-device instrumentation.
 *
 * This is a BOUNDED MODEL of switch/lifecycle/failure handling. It drives no
 * Media3 decoder and confirms no real video frame on its own. Its first-frame
 * confirmation is the diagnostic event emitted for a successful synthetic switch.
 * Real decoded-frame evidence stays UNVERIFIED.
 */
public final class QaPlaybackSoak {

    public static final String SCHEMA = "tunos-qa-playback-1";
    public static final long SEED = 20261124L;
    public static final int CHANNELS = 24;
    public static final int SWITCHES = 96;
    public static final int MAX_TOTAL_BYTES = 64 * 1024;
    public static final int MAX_EVENTS = 512;
    public static final int MAX_DIAGNOSTICS_BYTES = 32 * 1024;
    public static final int MAX_REPORT_BYTES = 16 * 1024;

    private static final String PLAYLIST = "playlist.m3u";
    private static final String PLAN = "plan.tsv";
    private static final String MANIFEST = "manifest.properties";

    // Synthetic canaries. No real provider URL, credential, key or media is used.
    static final String CANARY_URL = "https://qa.invalid/live/qa-canary.m3u8";
    static final String CANARY_TOKEN = "QA_FAKE_TOKEN_4D2B";
    static final String CANARY_DRM = "QA_FAKE_DRM_KEY_7C1F";
    static final String CANARY_RECORDING = "QA_FAKE_RECORDING_PAYLOAD_A5";

    private static final Pattern DIAG_LINE = Pattern.compile(
            "^[0-9]+\\t[0-9]+\\t[A-Z_]+\\t[0-9]+\\t[0-9]+$");

    private QaPlaybackSoak() {
    }

    /** Fixed 64-bit avalanche; stable across JVMs and architectures. */
    public static long mix(long value) {
        long x = value ^ SEED;
        x *= 6364136223846793005L;
        x += 1442695040888963407L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 31;
        x *= 0x94D049BB133111EBL;
        return x ^ (x >>> 33);
    }

    /** Provider header set for a fixture channel: two values plus a per-channel referer. */
    public static Map<String, String> headerFor(int channel) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", channel % 2 == 0 ? "QaFixture/1 even" : "QaFixture/1 odd");
        headers.put("Referer", "https://qa.invalid/ref/" + (channel % 3));
        return headers;
    }

    private static String headerKey(int channel) {
        Map<String, String> headers = headerFor(channel);
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            key.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return key.toString();
    }

    private static String outcomeFor(int index) {
        int bucket = (int) Math.floorMod(mix(index * 131L + 17L), 16L);
        if (bucket < 10) return "OK";
        if (bucket == 10) return "NO_RENDERER";
        if (bucket <= 12) return "SOURCE_ERROR";
        return "DECODER_LIMIT";
    }

    private static String lifecycleFor(int index) {
        if (index % 12 == 11) return "RELEASE_RECREATE";
        if (index % 7 == 3) return "PAUSE_RESUME";
        return "NONE";
    }

    private static int errorCodeFor(String outcome) {
        switch (outcome) {
            case "OK": return 0;
            case "NO_RENDERER": return 4001;
            case "SOURCE_ERROR": return 2001;
            case "DECODER_LIMIT": return 4003;
            default: throw new IllegalArgumentException("outcome");
        }
    }

    private static boolean reuseFor(int index, int channel) {
        if (index == 0) return false;
        if ("RELEASE_RECREATE".equals(lifecycleFor(index))) return false;
        int previousChannel = channelFor(index - 1);
        return headerKey(previousChannel).equals(headerKey(channel));
    }

    private static int channelFor(int index) {
        return (int) Math.floorMod(mix(index * 7L + 3L), CHANNELS);
    }

    /** One planned switch, as read from plan.tsv. */
    private static final class Switch {
        final int index;
        final int channel;
        final String outcome;
        final boolean reuse;
        final int errorCode;
        final String lifecycle;
        final int metric;

        Switch(String[] fields) {
            index = Integer.parseInt(fields[0]);
            channel = Integer.parseInt(fields[1]);
            outcome = fields[2];
            reuse = Boolean.parseBoolean(fields[3]);
            errorCode = Integer.parseInt(fields[4]);
            lifecycle = fields[5];
            metric = Integer.parseInt(fields[6]);
        }
    }

    /** Bounded result returned to both the JVM runner and the Android instrumentation. */
    public static final class Result {
        public final boolean ok;
        public final List<String> lines;
        public final String text;

        Result(boolean ok, List<String> lines) {
            this.ok = ok;
            this.lines = lines;
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append(line).append('\n');
                if (out.length() > MAX_REPORT_BYTES) throw new AssertionError("report bound exceeded");
            }
            this.text = out.toString();
        }
    }

    public static final class CheckFailure extends RuntimeException {
        public CheckFailure(String message) {
            super(message);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckFailure(message);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] block = new byte[1 << 12];
            int count;
            while ((count = in.read(block)) != -1) digest.update(block, 0, count);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static void line(BufferedWriter writer, String text, int[] budget) throws IOException {
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        budget[0] += bytes.length;
        if (budget[0] > MAX_TOTAL_BYTES) throw new CheckFailure("fixture byte ceiling exceeded");
        writer.write(text);
        writer.write('\n');
    }

    /** Regenerates the whole bounded fixture from SEED; only known filenames are touched. */
    public static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String name : new String[] {PLAYLIST, PLAN, MANIFEST}) {
            Files.deleteIfExists(dir.resolve(name));
        }
        int[] budget = new int[1];
        int[] counts = counts();

        try (BufferedWriter out = Files.newBufferedWriter(dir.resolve(PLAYLIST), StandardCharsets.UTF_8)) {
            line(out, "#EXTM3U", budget);
            line(out, "#QA-FIXTURE schema=" + SCHEMA + " seed=" + SEED, budget);
            line(out, "#QA-CANARY url=" + CANARY_URL + " token=" + CANARY_TOKEN
                    + " drm=" + CANARY_DRM + " recording=" + CANARY_RECORDING, budget);
            for (int channel = 0; channel < CHANNELS; channel++) {
                line(out, String.format("#EXTINF:-1 tvg-id=\"qa.ch.%02d\" group-title=\"QA-Fixture\",Fixture Channel %02d",
                        channel, channel), budget);
                line(out, String.format("https://qa.invalid/live/channel-%02d.m3u8", channel), budget);
                line(out, "#QA-HEADER User-Agent=" + headerFor(channel).get("User-Agent")
                        + " Referer=" + headerFor(channel).get("Referer"), budget);
            }
        }

        try (BufferedWriter out = Files.newBufferedWriter(dir.resolve(PLAN), StandardCharsets.UTF_8)) {
            line(out, "switch\tchannel\toutcome\treuse\terror_code\tlifecycle\tmetric", budget);
            for (int index = 0; index < SWITCHES; index++) {
                int channel = channelFor(index);
                String outcome = outcomeFor(index);
                line(out, index + "\t" + channel + "\t" + outcome + "\t" + reuseFor(index, channel)
                        + "\t" + errorCodeFor(outcome) + "\t" + lifecycleFor(index) + "\t"
                        + Math.floorMod(mix(index * 19L + 11L), 1000), budget);
            }
        }

        StringBuilder manifest = new StringBuilder();
        String[] keys = {
                "schema", "seed", "channels", "switches", "playlist_bytes", "plan_bytes",
                "playlist_sha256", "plan_sha256", "total_bytes", "max_total_bytes",
                "expected_ok", "expected_no_renderer", "expected_source_error",
                "expected_decoder_limit", "expected_reuse_true", "expected_first_frames",
                "expected_pause", "expected_resume", "expected_release",
                "expected_diagnostic_lines", "canary_url", "canary_token", "canary_drm",
                "canary_recording"
        };
        long playlistBytes = Files.size(dir.resolve(PLAYLIST));
        long planBytes = Files.size(dir.resolve(PLAN));
        String[] values = {
                SCHEMA, Long.toString(SEED), Integer.toString(CHANNELS), Integer.toString(SWITCHES),
                Long.toString(playlistBytes), Long.toString(planBytes),
                sha256(dir.resolve(PLAYLIST)), sha256(dir.resolve(PLAN)),
                Long.toString(playlistBytes + planBytes), Integer.toString(MAX_TOTAL_BYTES),
                Integer.toString(counts[0]), Integer.toString(counts[1]), Integer.toString(counts[2]),
                Integer.toString(counts[3]), Integer.toString(counts[4]), Integer.toString(counts[5]),
                Integer.toString(counts[6]), Integer.toString(counts[7]), Integer.toString(counts[8]),
                Integer.toString(counts[9]), CANARY_URL, CANARY_TOKEN, CANARY_DRM, CANARY_RECORDING
        };
        for (int i = 0; i < keys.length; i++) manifest.append(keys[i]).append('=').append(values[i]).append('\n');
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (playlistBytes + planBytes + manifestBytes.length > MAX_TOTAL_BYTES) {
            throw new CheckFailure("fixture byte ceiling exceeded");
        }
        Files.write(dir.resolve(MANIFEST), manifestBytes);
    }

    /** Expected deterministic counts, computed independently of the soak run. */
    private static int[] counts() {
        int ok = 0, noRenderer = 0, sourceError = 0, decoderLimit = 0;
        int reused = 0, pauses = 0, releases = 0, diagLines = 1; // final release
        for (int index = 0; index < SWITCHES; index++) {
            int channel = channelFor(index);
            String outcome = outcomeFor(index);
            String lifecycle = lifecycleFor(index);
            boolean reuse = reuseFor(index, channel);
            if ("OK".equals(outcome)) ok++;
            else if ("NO_RENDERER".equals(outcome)) noRenderer++;
            else if ("SOURCE_ERROR".equals(outcome)) sourceError++;
            else decoderLimit++;
            if (reuse) reused++;
            if ("PAUSE_RESUME".equals(lifecycle)) pauses++;
            if ("RELEASE_RECREATE".equals(lifecycle)) {
                releases++;
                diagLines++;
            }
            if (!reuse && index > 0) {
                releases++;
                diagLines++;
            }
            diagLines += 1; // PLAYBACK_REQUESTED
            diagLines += "OK".equals(outcome) ? 2 : 1; // READY + FIRST_FRAME, or ERROR
            if ("PAUSE_RESUME".equals(lifecycle)) diagLines += 2;
        }
        releases++; // final release
        return new int[] {ok, noRenderer, sourceError, decoderLimit, reused, ok, pauses, pauses,
                releases, diagLines};
    }

    private static Map<String, String> readManifest(Path dir) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(dir.resolve(MANIFEST), StandardCharsets.UTF_8)) {
            String text;
            while ((text = reader.readLine()) != null) {
                int split = text.indexOf('=');
                require(split > 0, "malformed manifest line");
                values.put(text.substring(0, split), text.substring(split + 1));
            }
        }
        return values;
    }

    private static int expected(Map<String, String> manifest, String key) {
        String value = manifest.get(key);
        require(value != null, "missing manifest key " + key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException bad) {
            throw new CheckFailure("non-numeric manifest key " + key);
        }
    }

    private static List<Switch> readPlan(Path dir) throws IOException {
        List<Switch> plan = new ArrayList<>(SWITCHES);
        try (BufferedReader reader = Files.newBufferedReader(dir.resolve(PLAN), StandardCharsets.UTF_8)) {
            String text = reader.readLine();
            require("switch\tchannel\toutcome\treuse\terror_code\tlifecycle\tmetric".equals(text),
                    "plan header changed");
            while ((text = reader.readLine()) != null) {
                String[] fields = text.split("\t", -1);
                require(fields.length == 7, "plan row field count changed");
                plan.add(new Switch(fields));
            }
        }
        return plan;
    }

    /** Bounded numeric-only diagnostic sink mirroring the app event-line shape. */
    private static final class Diagnostics {
        final List<String> lines = new ArrayList<>(MAX_EVENTS);
        int bytes;
        int dropped;
        long wall = 1_700_000_000_000L;

        void emit(String event, int metric) {
            String line = wall + "\t" + (wall - 1_700_000_000_000L) + "\t" + event + "\t"
                    + metric + "\t" + (50_000 + (int) (wall % 7));
            wall++;
            bytes += line.length() + 1;
            require(bytes <= MAX_DIAGNOSTICS_BYTES, "diagnostic byte cap exceeded");
            if (lines.size() == MAX_EVENTS) {
                dropped++;
                lines.remove(0);
            }
            lines.add(line);
        }
    }

    /** Runs the switch/lifecycle/taxonomy model over a plan and checks every invariant. */
    public static Result check(Path dir) throws IOException {
        List<String> pass = new ArrayList<>(16);
        Map<String, String> manifest = readManifest(dir);
        require(SCHEMA.equals(manifest.get("schema")), "schema mismatch");
        require(SEED == Long.parseLong(manifest.get("seed")), "seed mismatch");
        require(CHANNELS == expected(manifest, "channels"), "channel cap mismatch");
        require(SWITCHES == expected(manifest, "switches"), "switch cap mismatch");
        require(MAX_TOTAL_BYTES == expected(manifest, "max_total_bytes"), "byte cap mismatch");

        Path playlist = dir.resolve(PLAYLIST);
        Path planPath = dir.resolve(PLAN);
        require(Files.size(playlist) == Long.parseLong(manifest.get("playlist_bytes")), "playlist size changed");
        require(Files.size(planPath) == Long.parseLong(manifest.get("plan_bytes")), "plan size changed");
        require(sha256(playlist).equals(manifest.get("playlist_sha256")), "playlist sha256 changed");
        require(sha256(planPath).equals(manifest.get("plan_sha256")), "plan sha256 changed");
        require(Files.size(playlist) + Files.size(planPath) <= MAX_TOTAL_BYTES, "fixture byte cap exceeded");
        pass.add("PASS qa-playback-fixture-integrity seed=" + SEED + " channels=" + CHANNELS
                + " switches=" + SWITCHES + " bytes=" + (Files.size(playlist) + Files.size(planPath)));

        StringBuilder playlistText = new StringBuilder();
        int channelsSeen = 0;
        try (BufferedReader reader = Files.newBufferedReader(playlist, StandardCharsets.UTF_8)) {
            String text;
            while ((text = reader.readLine()) != null) {
                playlistText.append(text).append('\n');
                if (text.startsWith("#EXTINF:")) channelsSeen++;
                if (text.startsWith("https://")) {
                    java.net.URI uri = java.net.URI.create(text);
                    require("https".equals(uri.getScheme()), "non-https fixture URL");
                    require(uri.getHost() != null && uri.getHost().endsWith(".invalid"),
                            "fixture URL is not on the reserved .invalid TLD");
                }
            }
        }
        require(channelsSeen == CHANNELS, "playlist channel count changed");
        String canaryText = playlistText.toString();
        for (String canary : new String[] {CANARY_URL, CANARY_TOKEN, CANARY_DRM, CANARY_RECORDING}) {
            require(canaryText.contains(canary), "synthetic canary missing from the fixture");
        }
        pass.add("PASS qa-playback-fixture-synthetic channels=" + channelsSeen
                + " non_invalid_urls=0 canaries=4");

        List<Switch> plan = readPlan(dir);
        require(plan.size() == SWITCHES, "plan row count changed");
        int[] expected = counts();
        int ok = 0, noRenderer = 0, sourceError = 0, decoderLimit = 0;
        for (int i = 0; i < plan.size(); i++) {
            Switch s = plan.get(i);
            require(s.index == i, "plan switch index out of order");
            require(s.channel >= 0 && s.channel < CHANNELS, "plan channel out of range");
            require(s.errorCode == errorCodeFor(s.outcome), "plan error code inconsistent");
            require(s.metric >= 0 && s.metric < 1000, "plan metric out of range");
            require("NONE".equals(s.lifecycle) || "PAUSE_RESUME".equals(s.lifecycle)
                    || "RELEASE_RECREATE".equals(s.lifecycle), "unknown lifecycle token");
            require(s.channel == channelFor(i), "plan channel changed");
            require(s.outcome.equals(outcomeFor(i)), "plan outcome changed");
            require(s.reuse == reuseFor(i, s.channel), "plan reuse decision changed");
            if ("OK".equals(s.outcome)) ok++;
            else if ("NO_RENDERER".equals(s.outcome)) noRenderer++;
            else if ("SOURCE_ERROR".equals(s.outcome)) sourceError++;
            else decoderLimit++;
        }
        int[] declared = {
                expected(manifest, "expected_ok"), expected(manifest, "expected_no_renderer"),
                expected(manifest, "expected_source_error"), expected(manifest, "expected_decoder_limit"),
                expected(manifest, "expected_reuse_true"), expected(manifest, "expected_first_frames"),
                expected(manifest, "expected_pause"), expected(manifest, "expected_resume"),
                expected(manifest, "expected_release"), expected(manifest, "expected_diagnostic_lines")
        };
        require(Arrays.equals(expected, declared), "manifest expected counts disagree with the plan");
        require(ok == declared[0] && noRenderer == declared[1] && sourceError == declared[2]
                && decoderLimit == declared[3], "plan taxonomy counts changed");

        Diagnostics diag = new Diagnostics();
        int active = 0;
        String currentHeaders = null;
        int reused = 0, firstFrames = 0, pauses = 0, resumes = 0, releases = 0;
        int errorEvents = 0;
        for (int i = 0; i < plan.size(); i++) {
            Switch s = plan.get(i);
            if (i == 0) {
                require(active == 0, "no player should exist before the first switch");
            } else {
                require(active == 1, "no active player before switch " + i);
            }
            if ("RELEASE_RECREATE".equals(s.lifecycle)) {
                active = 0;
                currentHeaders = null;
                releases++;
                diag.emit("PLAYBACK_RELEASE", 0);
            }
            if (active == 0) {
                active = 1; // a fresh player is created only after an explicit release
            }
            boolean allowed = currentHeaders != null
                    && currentHeaders.equals(headerKey(s.channel)); // playingRecording is always false here
            require(allowed == s.reuse, "reuse decision mismatch at switch " + i);
            if (allowed) {
                reused++;
            } else if (i > 0) {
                active = 0;
                currentHeaders = null;
                releases++;
                diag.emit("PLAYBACK_RELEASE", 0);
                active = 1;
            }
            currentHeaders = headerKey(s.channel);
            diag.emit("PLAYBACK_REQUESTED", s.metric);
            if ("OK".equals(s.outcome)) {
                diag.emit("PLAYBACK_READY", 0);
                diag.emit("PLAYBACK_FIRST_FRAME", s.metric);
                firstFrames++;
            } else {
                diag.emit("PLAYBACK_ERROR", s.errorCode);
                errorEvents++;
            }
            if ("PAUSE_RESUME".equals(s.lifecycle)) {
                diag.emit("PLAYBACK_PAUSE", 0);
                diag.emit("PLAYBACK_RESUME", 0);
                pauses++;
                resumes++;
            }
            require(active <= 1, "more than one active player at switch " + i);
        }
        require(active == 1, "no active player before final release");
        active = 0;
        releases++;
        diag.emit("PLAYBACK_RELEASE", 0);
        require(active == 0, "player still active after release");

        require(reused == declared[4], "reuse count changed");
        require(firstFrames == declared[5], "first-frame count changed");
        require(pauses == declared[6] && resumes == declared[7], "pause/resume counts changed");
        require(releases == declared[8], "release count changed: actual=" + releases + " declared=" + declared[8]);
        require(diag.lines.size() == declared[9], "diagnostic line count changed: " + diag.lines.size());
        require(diag.dropped == 0, "diagnostic ring dropped events");
        require(errorEvents == noRenderer + sourceError + decoderLimit, "error event count changed");
        long firstFrameLines = diag.lines.stream().filter(l -> l.contains("\tPLAYBACK_FIRST_FRAME\t")).count();
        require(firstFrameLines == firstFrames, "first-frame confirmation is missing or duplicated");
        pass.add("PASS qa-playback-switch-soak switches=" + plan.size() + " reused=" + reused
                + " first_frames=" + firstFrames + " releases=" + releases);
        pass.add("PASS qa-playback-failure-taxonomy no_renderer=" + noRenderer
                + " source_error=" + sourceError + " decoder_limit=" + decoderLimit
                + " error_events=" + errorEvents);
        pass.add("PASS qa-playback-lifecycle pause=" + pauses + " resume=" + resumes
                + " release=" + releases + " active_after_release=" + active);

        StringBuilder diagnostics = new StringBuilder();
        for (String entry : diag.lines) {
            require(DIAG_LINE.matcher(entry).matches(), "diagnostic line is not fixed-format numeric");
            diagnostics.append(entry).append('\n');
        }
        require(diagnostics.length() <= MAX_DIAGNOSTICS_BYTES, "diagnostic byte cap exceeded");
        String all = diagnostics.toString();
        for (String secret : new String[] {CANARY_URL, CANARY_TOKEN, CANARY_DRM, CANARY_RECORDING,
                "://", "token=", "Bearer", "DRM", "RECORDING_PAYLOAD"}) {
            require(!all.contains(secret), "diagnostics leaked a sensitive marker");
        }
        pass.add("PASS qa-playback-bounded-diagnostics lines=" + diag.lines.size()
                + " bytes=" + diagnostics.length() + " cap=" + MAX_DIAGNOSTICS_BYTES
                + " dropped=" + diag.dropped);
        pass.add("PASS qa-playback-redaction urls=0 credentials=0 drm_keys=0 recording_payloads=0");
        return new Result(true, pass);
    }

    public static Result generateAndCheck(Path dir) throws IOException {
        generate(dir);
        return check(dir);
    }

    /** Scans an existing bounded diagnostics file without modifying it. */
    public static Result scanDiagnostics(Path file, String[] canaries) throws IOException {
        List<String> pass = new ArrayList<>(2);
        require(Files.exists(file), "diagnostic file missing");
        require(Files.size(file) <= MAX_DIAGNOSTICS_BYTES, "diagnostic file exceeds its cap");
        StringBuilder text = new StringBuilder();
        int lines = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                require(DIAG_LINE.matcher(line).matches(), "device diagnostic line is not fixed-format numeric");
                text.append(line).append('\n');
            }
        }
        for (String secret : canaries) {
            require(secret != null && !secret.isEmpty(), "empty canary");
            require(!text.toString().contains(secret), "device diagnostics leaked a canary");
        }
        pass.add("PASS qa-playback-device-diagnostics lines=" + lines + " bytes=" + text.length()
                + " leaks=0");
        return new Result(true, pass);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("--generate".equals(args[0]) || "--check".equals(args[0]))) {
            System.out.println("usage: QaPlaybackSoak (--generate|--check) <fixture-dir>");
            System.exit(2);
        }
        Path dir = Paths.get(args[1]);
        if ("--generate".equals(args[0])) {
            generate(dir);
            System.out.println("PASS qa-playback-generate schema=" + SCHEMA + " seed=" + SEED
                    + " channels=" + CHANNELS + " switches=" + SWITCHES);
            return;
        }
        try {
            Result result = check(dir);
            for (String line : result.lines) System.out.println(line);
            System.out.println("PLAYBACK REGRESSION FIXTURE CHECK PASS");
        } catch (CheckFailure failure) {
            System.out.println("FAIL " + failure.getMessage());
            System.exit(1);
        }
    }
}
