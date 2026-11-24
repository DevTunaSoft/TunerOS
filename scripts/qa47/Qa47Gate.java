import com.devtunasoft.tuneros.core.RecordingWorkerLifecycle;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** QA-47: deterministic host gate for bounded recording-worker terminal shutdown. */
public final class Qa47Gate {
    static final String SCHEMA = "qa47-worker-shutdown-1";
    static final int MAX_TASKS = 12;
    static final int WORK_TASKS = 6;
    static final int JOURNAL_CAP = 16;
    static final int CYCLES = 3;
    static final int PEEK_WAIT_MS = 150;
    static final int TERMINATE_WAIT_MS = 3000;
    static final String SEED = "qa47-seed-20261124";

    // Pinned after the first deterministic generation; any fixture byte drift is RED.
    static final String PINNED_FIXTURE_DIGEST = "c26eba5a5c9b4734f219bfa6dbe7ff2a3973f816baffe66c80ce9e619a2ec056";
    static final String PINNED_PRODUCTION_SHA = "535eed12718ef76e0a1f603ac58fc1bab456708fd899b6b9b25121e0c7d53b8c";

    // Mutation targets: each line is replaced by exactly one runner probe.
    static final boolean STRICT_CORRUPT_ENABLED = true;
    static final boolean PRIVACY_ENABLED = true;
    static final int THREAD_SLACK = 2;

    static final String[] FILE_ORDER = {"corrupt.tsv", "dimensions.txt", "expected.txt", "sha256.txt", "tasks.tsv"};
    static final Pattern URL = Pattern.compile("(?i)(?:https?|ftp)://");
    static final Pattern CREDENTIAL = Pattern.compile("(?i)\\b(?:password|passwd|secret|api[-_]?key|credential|pin)\\s*[:=]\\s*[A-Za-z0-9+/=_-]{6,}");
    static final Pattern PAYLOAD = Pattern.compile("(?i)recording[-_]?payload");

    private final List<String> report = new ArrayList<String>();
    private String first = null;
    private int passed = 0;
    private int failed = 0;

    public static void main(String[] args) {
        int rc = 1;
        try {
            rc = new Qa47Gate().run(args);
        } catch (Throwable t) {
            System.out.println("FAIL GATE_EXCEPTION " + t.getClass().getSimpleName());
            System.out.println("FIRST GATE_EXCEPTION");
        }
        System.exit(rc);
    }

    private int run(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        Path dir = Paths.get(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
        if ("generate".equals(mode)) {
            Map<String, byte[]> files = buildFixture();
            Files.createDirectories(dir);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Files.write(dir.resolve(e.getKey()), e.getValue());
            }
            byte[] agg = aggregate(files);
            System.out.println("DIGEST " + hex(agg));
            for (String name : FILE_ORDER) {
                System.out.println("FILE " + name + " " + hex(sha256(files.get(name))));
            }
            System.out.println("SECRET_HEX_LENGTH " + secretHex().length());
            return 0;
        }
        if (!"verify".equals(mode)) {
            System.out.println("FAIL UNKNOWN_MODE");
            return 2;
        }
        boolean semantic = false;
        String production = "core/src/main/kotlin/com/devtunasoft/tuneros/core/RecordingWorkerLifecycle.kt";
        String scratch = dir.toString();
        for (int i = 2; i < args.length; i++) {
            if ("--semantic".equals(args[i])) semantic = true;
            else if ("--production".equals(args[i]) && i + 1 < args.length) production = args[++i];
            else if ("--scratch".equals(args[i]) && i + 1 < args.length) scratch = args[++i];
        }
        Path scratchDir = Paths.get(scratch).toAbsolutePath().normalize();
        Files.createDirectories(scratchDir);
        Files.write(scratchDir.resolve("qa47-secret.txt"), secretHex().getBytes(StandardCharsets.US_ASCII));

        Map<String, byte[]> expected = buildFixture();
        Map<String, byte[]> actual = new LinkedHashMap<String, byte[]>();
        for (String name : FILE_ORDER) {
            Path p = dir.resolve(name);
            if (!Files.isRegularFile(p)) {
                fail("FIXTURE_BYTE_IDENTITY", "missing-file");
                return finish(scratchDir);
            }
            actual.put(name, Files.readAllBytes(p));
        }

        if (!semantic) {
            boolean identical = true;
            for (String name : FILE_ORDER) {
                if (!Arrays.equals(expected.get(name), actual.get(name))) identical = false;
            }
            check("FIXTURE_BYTE_IDENTITY", identical, "files=" + FILE_ORDER.length);
            check("FIXTURE_PINNED_DIGEST", hex(aggregate(actual)).equals(PINNED_FIXTURE_DIGEST), "algorithm=sha256");
        }
        check("MANIFEST_CONSISTENT", manifestConsistent(actual), "lines=" + FILE_ORDER.length);

        Map<String, String> dims = new HashMap<String, String>();
        try {
            dims = dimensions(actual.get("dimensions.txt"));
            check("DIMENSIONS_PARSE", true, "keys=" + dims.size());
        } catch (Reject r) {
            check("DIMENSIONS_PARSE", false, r.code);
            return finish(scratchDir);
        }
        check("DIMENSIONS_BOUND", dims.get("schema").equals(SCHEMA)
                && num(dims, "max_tasks") == MAX_TASKS
                && num(dims, "work_tasks") == WORK_TASKS
                && num(dims, "journal_cap") == JOURNAL_CAP
                && num(dims, "cycles") == CYCLES
                && num(dims, "peek_wait_ms") == PEEK_WAIT_MS
                && num(dims, "terminate_wait_ms") == TERMINATE_WAIT_MS,
                "cap=" + MAX_TASKS + " work=" + WORK_TASKS + " cycles=" + CYCLES);

        List<String[]> rows = new ArrayList<String[]>();
        try {
            rows = tasks(actual.get("tasks.tsv"));
            check("HEADER_STRICT", true, "header=id-op-marker");
        } catch (Reject r) {
            check("HEADER_STRICT", false, r.code);
            return finish(scratchDir);
        }
        check("ROW_FIELDS_STRICT", allFields(rows), "rows=" + rows.size());
        check("OP_ALLOWED", allOps(rows), "ops=work,cleanup");
        check("ID_UNIQUE", uniqueIds(rows), "rows=" + rows.size());
        check("ORDER_STRICT", cleanupLast(rows), "work=" + WORK_TASKS);

        List<String[]> corrupt = new ArrayList<String[]>();
        try {
            corrupt = corruptRows(actual.get("corrupt.tsv"));
            check("CORRUPT_HEADER_STRICT", true, "header=name-rule-value");
        } catch (Reject r) {
            check("CORRUPT_HEADER_STRICT", false, r.code);
            return finish(scratchDir);
        }
        check("CORRUPT_FAIL_CLOSED", strictRejectsAll(corrupt, rows), "variants=" + corrupt.size());
        check("LAX_CONTROL_ACCEPTS", laxAcceptsAll(corrupt), "variants=" + corrupt.size());

        String stage = "SHUTDOWN_IMMEDIATE";
        try {
            List<String> signatures = new ArrayList<String>();
            int baseline = (int) liveThreads();
            int peak = baseline;
            int after = baseline;
            long[] heap = new long[CYCLES];
            boolean peekClean = true;
            boolean immediate = true;
            boolean rejected = true;
            boolean order = true;
            boolean once = true;
            boolean bounded = true;
            boolean terminated = true;
            for (int cycle = 0; cycle < CYCLES; cycle++) {
                ExecutorService worker = Executors.newSingleThreadExecutor();
                BlockingQueue<String> journal = new LinkedBlockingQueue<String>();
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                AtomicInteger cleanups = new AtomicInteger(0);
                try {
                    for (int i = 0; i < rows.size(); i++) {
                        final String[] row = rows.get(i);
                        if ("work".equals(row[1]) && i == 0) {
                            worker.execute(new Runnable() {
                                public void run() {
                                    entered.countDown();
                                    try {
                                        release.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                    }
                                    journal.add("WORK " + row[0]);
                                }
                            });
                        } else if ("work".equals(row[1])) {
                            worker.execute(new Runnable() {
                                public void run() { journal.add("WORK " + row[0]); }
                            });
                        } else {
                            final Function0<Unit> cleanup = new Function0<Unit>() {
                                public Unit invoke() {
                                    cleanups.incrementAndGet();
                                    journal.add("CLEANUP");
                                    return Unit.INSTANCE;
                                }
                            };
                            stage = "SUBMISSION_REJECTED";
                            RecordingWorkerLifecycle.INSTANCE.shutdownAfterPendingWork(worker, cleanup);
                        }
                    }
                    if (!worker.isShutdown()) immediate = false;
                    stage = "SUBMISSION_REJECTED";
                    try {
                        worker.execute(new Runnable() { public void run() { } });
                        rejected = false;
                    } catch (RejectedExecutionException expectedRejection) {
                        // expected
                    }
                    stage = "CLEANUP_AFTER_WORK";
                    if (!entered.await(5, TimeUnit.SECONDS)) order = false;
                    if (cycle == 0) {
                        Thread.sleep(PEEK_WAIT_MS);
                        if (journal.contains("CLEANUP")) peekClean = false;
                    }
                    peak = Math.max(peak, liveThreads());
                    release.countDown();
                    if (!worker.awaitTermination(TERMINATE_WAIT_MS, TimeUnit.MILLISECONDS)) terminated = false;
                    after = Math.max(after, liveThreads());
                    List<String> snapshot = new ArrayList<String>(journal);
                    if (snapshot.size() != WORK_TASKS + 1) bounded = false;
                    if (!snapshot.get(snapshot.size() - 1).equals("CLEANUP")) order = false;
                    for (int i = 0; i < WORK_TASKS; i++) {
                        if (!snapshot.get(i).equals("WORK " + rows.get(i)[0])) order = false;
                    }
                    if (cleanups.get() != 1) once = false;
                    signatures.add(join(snapshot, "|"));
                    System.gc();
                    Thread.sleep(30);
                    Runtime rt = Runtime.getRuntime();
                    heap[cycle] = rt.totalMemory() - rt.freeMemory();
                } finally {
                    release.countDown();
                    worker.shutdownNow();
                }
            }
            stage = "SHUTDOWN_IMMEDIATE";
            check(stage, immediate, "cycles=" + CYCLES);
            check("SUBMISSION_REJECTED", rejected, "admission=closed");
            check("CLEANUP_AFTER_WORK", order && peekClean, "peek_ms=" + PEEK_WAIT_MS);
            check("CLEANUP_ONCE", once, "expected=1");
            check("JOURNAL_BOUNDED", bounded, "cap=" + JOURNAL_CAP);
            check("TERMINATION_BOUNDED", terminated, "wait_ms=" + TERMINATE_WAIT_MS);
            check("THREAD_BOUND", peak <= baseline + THREAD_SLACK && after <= baseline + 1,
                    "baseline=" + baseline + " peak=" + peak + " after=" + after);
            Runtime rt = Runtime.getRuntime();
            boolean heapOk = true;
            for (int i = 0; i < CYCLES; i++) {
                if (heap[i] <= 0 || heap[i] > rt.maxMemory() * 8L / 10L) heapOk = false;
                if (i > 0 && heap[i] > heap[0] + 2L * 1024L * 1024L) heapOk = false;
            }
            check("HEAP_BOUND", heapOk, "cycles=" + CYCLES + " drift_cap_bytes=2097152");
            boolean same = true;
            for (int i = 1; i < signatures.size(); i++) {
                if (!signatures.get(i).equals(signatures.get(0))) same = false;
            }
            check("CYCLE_DETERMINISM", same, "cycles=" + CYCLES);
        } catch (Throwable t) {
            check(stage, false, t.getClass().getSimpleName());
            return finish(scratchDir);
        }

        if (!PRIVACY_ENABLED) {
            report.add("X-Qa47-Trace " + secretHex());
        }
        check("PRIVACY_SCAN", privacyClean(join(report, "\n"), actual), "patterns=4");

        if (!semantic) {
            String golden = new String(actual.get("expected.txt"), StandardCharsets.US_ASCII);
            String observed = join(expectedJournal(), "\n") + "\n";
            check("GOLDEN_JOURNAL", golden.equals(observed), "lines=" + expectedJournal().size());
            byte[] prod = sha256(Paths.get(production));
            check("PRODUCTION_SOURCE_PIN", hex(prod).equals(PINNED_PRODUCTION_SHA), "algorithm=sha256");
        }
        return finish(scratchDir);
    }

    private int finish(Path scratchDir) throws IOException {
        List<String> sanitized = new ArrayList<String>();
        for (String line : report) {
            sanitized.add(line.replace(secretHex(), "[REDACTED]"));
        }
        Files.write(scratchDir.resolve("qa47-report.txt"),
                (join(sanitized, "\n") + "\n").getBytes(StandardCharsets.US_ASCII));
        for (String line : sanitized) {
            System.out.println(line);
        }
        if (first != null) {
            System.out.println("FIRST " + first);
        } else {
            System.out.println("FIRST NONE");
        }
        System.out.println("SUMMARY total=" + (passed + failed) + " pass=" + passed + " fail=" + failed);
        return failed == 0 && first == null ? 0 : 1;
    }

    private void check(String id, boolean ok, String detail) {
        if (ok) {
            passed++;
            report.add("PASS\t" + id + "\t" + detail);
        } else {
            failed++;
            if (first == null) first = id;
            report.add("FAIL\t" + id + "\t" + detail);
        }
    }

    private void fail(String id, String detail) {
        check(id, false, detail);
    }

    private static String secretHex() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SEED + ":admission-marker").getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }

    private static Map<String, byte[]> buildFixture() {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        StringBuilder dims = new StringBuilder();
        dims.append("schema=").append(SCHEMA).append('\n');
        dims.append("max_tasks=").append(MAX_TASKS).append('\n');
        dims.append("work_tasks=").append(WORK_TASKS).append('\n');
        dims.append("journal_cap=").append(JOURNAL_CAP).append('\n');
        dims.append("cycles=").append(CYCLES).append('\n');
        dims.append("peek_wait_ms=").append(PEEK_WAIT_MS).append('\n');
        dims.append("terminate_wait_ms=").append(TERMINATE_WAIT_MS).append('\n');
        dims.append("seed=").append(SEED).append('\n');

        StringBuilder tasks = new StringBuilder("id\top\tmarker\n");
        for (int i = 0; i < WORK_TASKS; i++) {
            tasks.append("w").append(String.format(Locale.ROOT, "%03d", i)).append("\twork\tqa47m")
                    .append(String.format(Locale.ROOT, "%04d", i)).append('\n');
        }
        tasks.append("c000\tcleanup\tqa47m0006\n");

        StringBuilder corrupt = new StringBuilder("name|rule|value\n");
        corrupt.append("bad-op|op|frobnicate\n");
        corrupt.append("negative-delay|delay|-1\n");
        corrupt.append("over-cap|count|13\n");
        corrupt.append("duplicate-id|id|w000\n");
        corrupt.append("bad-fields|fields|w006+work\n");
        corrupt.append("truncated|terminator|w001~work\n");
        corrupt.append("swapped|order|cleanup-first\n");

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < WORK_TASKS; i++) {
            expected.append("WORK w").append(String.format(Locale.ROOT, "%03d", i)).append('\n');
        }
        expected.append("CLEANUP\n");

        files.put("dimensions.txt", bytes(dims));
        files.put("tasks.tsv", bytes(tasks));
        files.put("corrupt.tsv", bytes(corrupt));
        files.put("expected.txt", bytes(expected));

        StringBuilder manifest = new StringBuilder();
        List<String> names = new ArrayList<String>(Arrays.asList("corrupt.tsv", "dimensions.txt", "expected.txt", "tasks.tsv"));
        Collections.sort(names);
        for (String name : names) {
            manifest.append(hex(sha256(files.get(name)))).append("  ").append(name).append('\n');
        }
        files.put("sha256.txt", bytes(manifest));
        return files;
    }

    private static byte[] aggregate(Map<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : FILE_ORDER) {
                md.update(name.getBytes(StandardCharsets.US_ASCII));
                md.update((byte) 0);
                md.update(files.get(name));
            }
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }

    private static boolean manifestConsistent(Map<String, byte[]> actual) {
        String text = new String(actual.get("sha256.txt"), StandardCharsets.US_ASCII);
        String[] ls = splitLines(text);
        if (ls.length != 4) return false;
        Set<String> seen = new HashSet<String>();
        for (String line : ls) {
            String[] f = line.split("  ", -1);
            if (f.length != 2 || f[0].length() != 64 || !actual.containsKey(f[1])) return false;
            if (!hex(sha256(actual.get(f[1]))).equals(f[0])) return false;
            seen.add(f[1]);
        }
        return seen.size() == 4;
    }

    private static Map<String, String> dimensions(byte[] b) throws Reject {
        Map<String, String> out = new HashMap<String, String>();
        String[] ls = splitLines(new String(b, StandardCharsets.US_ASCII));
        if (ls.length == 0 || !ls[0].equals("schema=" + SCHEMA)) throw new Reject("DIMENSIONS_HEADER");
        for (String line : ls) {
            int i = line.indexOf('=');
            if (i <= 0 || i == line.length() - 1) throw new Reject("DIMENSIONS_FIELD");
            if (out.put(line.substring(0, i), line.substring(i + 1)) != null) throw new Reject("DIMENSIONS_DUPLICATE");
        }
        return out;
    }

    private static int num(Map<String, String> dims, String key) {
        try {
            return Integer.parseInt(dims.get(key));
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private static List<String[]> tasks(byte[] b) throws Reject {
        String[] ls = splitLines(new String(b, StandardCharsets.US_ASCII));
        if (ls.length == 0 || !ls[0].equals("id\top\tmarker")) throw new Reject("TASKS_HEADER");
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 1; i < ls.length; i++) {
            String[] f = ls[i].split("\t", -1);
            if (f.length != 3) throw new Reject("TASKS_FIELDS");
            out.add(f);
        }
        return out;
    }

    private static List<String[]> corruptRows(byte[] b) throws Reject {
        String[] ls = splitLines(new String(b, StandardCharsets.US_ASCII));
        if (ls.length == 0 || !ls[0].equals("name|rule|value")) throw new Reject("CORRUPT_HEADER");
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 1; i < ls.length; i++) {
            String[] f = ls[i].split("\\|", -1);
            if (f.length != 3 || f[0].isEmpty()) throw new Reject("CORRUPT_FIELDS");
            out.add(f);
        }
        return out;
    }

    private static boolean allFields(List<String[]> rows) {
        if (rows.size() != WORK_TASKS + 1) return false;
        for (String[] r : rows) if (r.length != 3) return false;
        return true;
    }

    private static boolean allOps(List<String[]> rows) {
        int work = 0;
        int cleanup = 0;
        for (String[] r : rows) {
            if ("work".equals(r[1])) work++;
            else if ("cleanup".equals(r[1])) cleanup++;
            else return false;
        }
        return work == WORK_TASKS && cleanup == 1;
    }

    private static boolean uniqueIds(List<String[]> rows) {
        Set<String> ids = new HashSet<String>();
        for (String[] r : rows) if (!ids.add(r[0])) return false;
        return ids.size() == WORK_TASKS + 1;
    }

    private static boolean cleanupLast(List<String[]> rows) {
        if (!"cleanup".equals(rows.get(rows.size() - 1)[1])) return false;
        for (int i = 0; i < WORK_TASKS; i++) if (!"work".equals(rows.get(i)[1])) return false;
        return true;
    }

    private static boolean strictRejectsAll(List<String[]> bad, List<String[]> good) {
        if (bad.size() != 7) return false;
        Set<String> ids = new HashSet<String>();
        for (String[] r : good) ids.add(r[0]);
        String[] expectedCodes = {"bad-op", "negative-delay", "over-cap", "duplicate-id", "bad-fields", "truncated", "swapped"};
        for (int i = 0; i < bad.size(); i++) {
            String[] v = bad.get(i);
            if (!v[0].equals(expectedCodes[i])) return false;
            if (!STRICT_CORRUPT_ENABLED) return false;
            boolean rejected;
            if (v[1].equals("op")) rejected = !"work".equals(v[2]) && !"cleanup".equals(v[2]);
            else if (v[1].equals("delay")) rejected = parseIntOrMin(v[2]) < 0;
            else if (v[1].equals("count")) rejected = parseIntOrMin(v[2]) > MAX_TASKS;
            else if (v[1].equals("id")) rejected = ids.contains(v[2]);
            else if (v[1].equals("fields")) rejected = v[2].indexOf('+') >= 0;
            else if (v[1].equals("terminator")) rejected = v[2].indexOf('~') >= 0;
            else if (v[1].equals("order")) rejected = "cleanup-first".equals(v[2]);
            else rejected = false;
            if (!rejected) return false;
        }
        return true;
    }

    private static boolean laxAcceptsAll(List<String[]> bad) {
        for (String[] v : bad) if (v[0].isEmpty() || v[1].isEmpty() || v[2].isEmpty()) return false;
        return bad.size() == 7;
    }

    private static int parseIntOrMin(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    private static boolean privacyClean(String diagnostics, Map<String, byte[]> files) {
        if (diagnostics.contains(secretHex())) return false;
        String all = diagnostics;
        for (String name : FILE_ORDER) all += "\n" + new String(files.get(name), StandardCharsets.US_ASCII);
        return !URL.matcher(all).find() && !CREDENTIAL.matcher(all).find() && !PAYLOAD.matcher(all).find();
    }

    private static List<String> expectedJournal() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < WORK_TASKS; i++) out.add("WORK w" + String.format(Locale.ROOT, "%03d", i));
        out.add("CLEANUP");
        return out;
    }

    private static int liveThreads() {
        return Thread.getAllStackTraces().size();
    }

    private static byte[] bytes(StringBuilder sb) {
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new IllegalStateException(e.getClass().getSimpleName()); }
    }

    private static byte[] sha256(Path p) throws IOException {
        return sha256(Files.readAllBytes(p));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    private static String[] splitLines(String s) {
        if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return new String[0];
        return s.split("\n", -1);
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    private static final class Reject extends Exception {
        final String code;
        Reject(String code) { this.code = code; }
    }
}
