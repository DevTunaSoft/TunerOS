import com.devtunasoft.tuneros.core.RecordingPolicy;
import com.devtunasoft.tuneros.core.StreamRecorder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * QA-48: deterministic, zero-dependency host gate over bounded DVR capture
 * storage and cancellation byte-copy accounting. All behaviour checks call the
 * real compiled production units StreamRecorder and RecordingPolicy.
 */
public final class Qa48Gate {
    static final String SCHEMA = "qa48-capture-storage-1";
    static final String SEED = "qa48-seed-20261124";
    static final int MAX_CASES = 15;
    static final int MAX_CORRUPT = 8;
    static final int MAX_INPUT_BYTES = 10000;
    static final int COPY_BUFFER_BYTES = 4096;
    static final long MIN_FREE_BYTES = 512L;
    static final int REPORT_LINE_CAP = 256;
    static final int MAX_WORKER_THREADS = 2;
    static final long MAX_HEAP_BYTES = 48L * 1024L * 1024L;
    static final int THREAD_SLACK = 6;

    // Mutation targets. The runner flips exactly one per RED control.
    static final boolean STRICT_CORRUPT_ENABLED = true;
    static final boolean PRIVACY_ENABLED = true;

    // Pinned after the first deterministic generation.
    static final String PINNED_FIXTURE_DIGEST = "591084c3b6e675c94e32811c4b15faa09311879930c84a727e7e6447a572b481";

    static final String[] FILE_ORDER = {
        "dimensions.txt", "cases.tsv", "corrupt.tsv", "expected.txt", "sha256.txt"
    };
    static final String[] DATA_FILES = {
        "dimensions.txt", "cases.tsv", "corrupt.tsv", "expected.txt"
    };
    static final String CASE_HEADER =
        "caseId\tscenario\tinputBytes\tfreeBefore\tfreeAfterRead"
        + "\twrittenSinceReport\tcancelAfterRead\texpectedStop"
        + "\texpectedWritten\texpectedRemaining";
    static final String CORRUPT_HEADER = "code\t" + CASE_HEADER;
    static final Set<String> SCENARIOS = new HashSet<String>(Arrays.asList(
        "clean_eos", "empty_eos", "cancel_before", "cancel_after_read",
        "low_before", "low_after_read", "low_during_write", "low_reserved_exact",
        "zero_read", "capacity_none", "capacity_zero", "capacity_negative",
        "capacity_exact", "capacity_partial", "capacity_overdrawn"
    ));
    static final Set<String> STOPS = new HashSet<String>(Arrays.asList(
        "END_OF_STREAM", "CANCELLED", "LOW_STORAGE", "NO_PROGRESS", "CAPACITY",
        "STORAGE_REJECTED"
    ));
    static final Pattern ID = Pattern.compile("[a-z][0-9]{4}");
    static final Pattern URL = Pattern.compile("(?i)(?:https?|ftp)://");
    static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)\\b(?:password|passwd|secret|api[-_]?key|credential|pin)\\s*[:=]\\s*\\S{4,}");
    static final Pattern PIN = Pattern.compile("(?i)\\bpin\\b.{0,12}[0-9]{4}");
    static final Pattern PAYLOAD = Pattern.compile("(?i)recording[-_]?payload");

    private final List<String> report = new ArrayList<String>();
    private String first = null;
    private int total = 0;
    private int passed = 0;
    private int failed = 0;

    public static void main(String[] args) {
        int rc = 1;
        try {
            rc = new Qa48Gate().run(args);
        } catch (Throwable t) {
            System.out.println("FAIL GATE_EXCEPTION " + t.getClass().getSimpleName());
            System.out.println("FIRST GATE_EXCEPTION");
            System.out.println("SUMMARY total=1 passed=0 failed=1");
            rc = 1;
        }
        System.exit(rc);
    }

    private int run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("FAIL USAGE");
            return 1;
        }
        if ("generate".equals(args[0])) {
            if (args.length < 2) {
                System.out.println("FAIL USAGE");
                return 1;
            }
            Path dir = Paths.get(args[1]);
            Files.createDirectories(dir);
            Map<String, byte[]> files = buildFixture();
            for (String name : FILE_ORDER) {
                Files.write(dir.resolve(name), files.get(name));
            }
            System.out.println("DIGEST " + aggregate(files));
            return 0;
        }
        if (!"verify".equals(args[0])) {
            System.out.println("FAIL USAGE");
            return 1;
        }
        Path fixture = Paths.get(args[1]);
        Path scratch = null;
        boolean semantic = false;
        for (int i = 2; i < args.length; i++) {
            if ("--scratch".equals(args[i]) && i + 1 < args.length) {
                scratch = Paths.get(args[++i]);
            } else if ("--semantic".equals(args[i])) {
                semantic = true;
            }
        }
        if (scratch == null) {
            System.out.println("FAIL USAGE");
            return 1;
        }
        Files.createDirectories(scratch);
        verify(fixture, scratch, semantic);
        return failed == 0 ? 0 : 1;
    }

    // ---------- fixture construction ----------

    private static Map<String, byte[]> buildFixture() {
        StringBuilder dim = new StringBuilder();
        dim.append("schema=").append(SCHEMA).append('\n');
        dim.append("seed=").append(SEED).append('\n');
        dim.append("max_cases=").append(MAX_CASES).append('\n');
        dim.append("max_corrupt=").append(MAX_CORRUPT).append('\n');
        dim.append("max_input_bytes=").append(MAX_INPUT_BYTES).append('\n');
        dim.append("copy_buffer_bytes=").append(COPY_BUFFER_BYTES).append('\n');
        dim.append("min_free_bytes=").append(MIN_FREE_BYTES).append('\n');
        dim.append("report_line_cap=").append(REPORT_LINE_CAP).append('\n');

        String[][] cases = {
            {"c0001", "clean_eos",        "6000", "8192", "8192", "0",    "0", "END_OF_STREAM", "6000", "0"},
            {"c0002", "empty_eos",        "0",    "8192", "8192", "0",    "0", "END_OF_STREAM", "0",    "0"},
            {"c0003", "cancel_before",    "6000", "8192", "8192", "0",    "1", "CANCELLED",     "0",    "0"},
            {"c0004", "cancel_after_read","10000", "8192", "8192", "0",    "3", "CANCELLED",     "4096", "0"},
            {"c0005", "low_before",       "6000", "300",  "300",  "0",    "0", "STORAGE_REJECTED", "0", "0"},
            {"c0006", "low_after_read",   "6000", "8192", "300",  "0",    "0", "LOW_STORAGE",   "0",    "0"},
            {"c0007", "low_during_write", "6000", "600",  "600",  "0",    "0", "LOW_STORAGE",   "0",    "0"},
            {"c0008", "low_reserved_exact","512", "4608", "4608", "0",    "0", "END_OF_STREAM", "512",  "0"},
            {"c0009", "zero_read",        "6000", "8192", "8192", "0",    "0", "NO_PROGRESS",   "0",    "0"},
            {"c0010", "capacity_none",    "0",    "-1",   "-1",   "0",    "0", "CAPACITY",      "0",    "0"},
            {"c0011", "capacity_zero",    "0",    "0",    "0",    "0",    "0", "CAPACITY",      "0",    "0"},
            {"c0012", "capacity_negative","0",    "-5",   "-5",   "0",    "0", "CAPACITY",      "0",    "0"},
            {"c0013", "capacity_exact",   "0",    "1000", "1000", "1000", "0", "CAPACITY",      "0",    "0"},
            {"c0014", "capacity_partial", "0",    "1000", "1000", "300",  "0", "CAPACITY",      "0",    "700"},
            {"c0015", "capacity_overdrawn","0",   "1000", "1000", "5000", "0", "CAPACITY",      "0",    "0"},
        };
        StringBuilder cs = new StringBuilder(CASE_HEADER).append('\n');
        StringBuilder ex = new StringBuilder("caseId\texpectedStop\texpectedWritten\texpectedRemaining\n");
        for (String[] r : cases) {
            cs.append(join(r)).append('\n');
            ex.append(r[0]).append('\t').append(r[7]).append('\t')
              .append(r[8]).append('\t').append(r[9]).append('\n');
        }

        StringBuilder co = new StringBuilder(CORRUPT_HEADER).append('\n');
        co.append("BAD_ID\tC-1\tclean_eos\t6000\t8192\t8192\t0\t0\tEND_OF_STREAM\t6000\t0\n");
        co.append("FIELD_COUNT\tc0002\tclean_eos\t6000\t8192\t8192\t0\t0\n");
        co.append("BAD_SCENARIO\tc0003\tno_such_scenario\t6000\t8192\t8192\t0\t0\tEND_OF_STREAM\t6000\t0\n");
        co.append("BAD_NUMBER\tc0004\tclean_eos\tNOTANUMBER\t8192\t8192\t0\t0\tEND_OF_STREAM\t6000\t0\n");
        co.append("NEGATIVE_INPUT\tc0005\tclean_eos\t-1\t8192\t8192\t0\t0\tEND_OF_STREAM\t6000\t0\n");
        co.append("UNBOUNDED_INPUT\tc0006\tclean_eos\t10001\t8192\t8192\t0\t0\tEND_OF_STREAM\t6000\t0\n");
        co.append("BAD_STOP\tc0007\tclean_eos\t6000\t8192\t8192\t0\t0\tUNKNOWN\t6000\t0\n");
        co.append("NEGATIVE_WRITTEN\tc0008\tclean_eos\t6000\t8192\t8192\t-3\t0\tEND_OF_STREAM\t6000\t0\n");

        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        files.put("dimensions.txt", bytes(dim.toString()));
        files.put("cases.tsv", bytes(cs.toString()));
        files.put("corrupt.tsv", bytes(co.toString()));
        files.put("expected.txt", bytes(ex.toString()));
        StringBuilder mf = new StringBuilder();
        for (String name : DATA_FILES) {
            mf.append(hex(sha256(files.get(name)))).append('\t').append(name).append('\n');
        }
        files.put("sha256.txt", bytes(mf.toString()));
        return files;
    }

    private static String join(String[] row) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) b.append('\t');
            b.append(row[i]);
        }
        return b.toString();
    }

    // ---------- verification ----------

    private void verify(Path fixture, Path scratch, boolean semantic) throws Exception {
        Map<String, byte[]> disk = new LinkedHashMap<String, byte[]>();
        for (String name : FILE_ORDER) {
            byte[] b = Files.readAllBytes(fixture.resolve(name));
            if (b.length > 65536) {
                fail("FIXTURE_SIZE", name);
                continue;
            }
            disk.put(name, b);
        }

        Map<String, byte[]> regen = buildFixture();
        if (!semantic) {
            boolean identity = true;
            for (String name : FILE_ORDER) {
                if (!Arrays.equals(regen.get(name), disk.get(name))) {
                    identity = false;
                    break;
                }
            }
            check(identity, "FIXTURE_BYTE_IDENTITY", "files=5");
            check(PINNED_FIXTURE_DIGEST.equals(aggregate(disk)), "PINNED_DIGEST", "digest");
            boolean manifest = true;
            String mf = text(disk.get("sha256.txt"));
            List<String> mfLines = lines(mf);
            if (mfLines.size() != DATA_FILES.length) {
                manifest = false;
            } else {
                for (int i = 0; i < mfLines.size(); i++) {
                    String[] f = mfLines.get(i).split("\t", -1);
                    if (f.length != 2 || !f[1].equals(DATA_FILES[i])
                            || !f[0].equals(hex(sha256(disk.get(DATA_FILES[i]))))) {
                        manifest = false;
                    }
                }
            }
            check(manifest, "MANIFEST", "files=4");
        }

        Map<String, String> dim = parseDimensions(text(disk.get("dimensions.txt")));
        check(dim != null && SCHEMA.equals(dim.get("schema")), "SCHEMA_STRICT", "schema");
        if (dim == null) dim = new HashMap<String, String>();

        List<String[]> cases = parseTable(text(disk.get("cases.tsv")), CASE_HEADER, 10);
        List<String[]> corrupt = parseTable(text(disk.get("corrupt.tsv")), CORRUPT_HEADER, 11);
        check(cases.size() == MAX_CASES, "CASE_COUNT", "actual=" + cases.size());
        boolean caseFields = true;
        for (String[] row : cases) if (row.length != 10) { caseFields = false; break; }
        check(caseFields, "CASE_FIELDS", "columns=10");
        check(corrupt.size() == MAX_CORRUPT, "CORRUPT_COUNT", "actual=" + corrupt.size());

        // Strict corrupt-input parser: each row violates exactly one named rule.
        boolean corruptOk = true;
        for (String[] row : corrupt) {
            String actual = rejectReason(row);
            if (actual == null || !actual.equals(row[0])) {
                corruptOk = false;
                break;
            }
        }
        check(STRICT_CORRUPT_ENABLED && corruptOk, "CORRUPT_FAIL_CLOSED", "rows=" + corrupt.size());

        // Golden projection.
        List<String> golden = lines(text(disk.get("expected.txt")));
        boolean goldenOk = golden.size() == cases.size() + 1
            && golden.get(0).equals("caseId\texpectedStop\texpectedWritten\texpectedRemaining");
        if (goldenOk) {
            for (int i = 0; i < cases.size(); i++) {
                String[] c = cases.get(i);
                String want = c[0] + '\t' + c[7] + '\t' + c[8] + '\t' + c[9];
                if (!golden.get(i + 1).equals(want)) { goldenOk = false; break; }
            }
        }
        check(goldenOk, "GOLDEN_PROJECTION", "rows=" + cases.size());

        // Behavioural checks against the real production classes, on a bounded pool.
        List<CaseResult> results = runCases(cases, scratch);
        for (int i = 0; i < cases.size(); i++) {
            String[] c = cases.get(i);
            CaseResult r = results.get(i);
            String code = checkCodeFor(c[1]);
            boolean ok = r != null && r.ok;
            check(ok, code, c[0] + " " + c[1]);
        }

        // Bound checks.
        long heapUsed = usedHeap();
        check(heapUsed <= MAX_HEAP_BYTES, "HEAP_BOUND", "bytes=" + heapUsed);
        int threads = peakThreads.get();
        int threadLimit = baselineThreads + THREAD_SLACK;
        check(threads <= threadLimit, "THREAD_BOUND",
            "peak=" + threads + " limit=" + threadLimit);

        // Diagnostics privacy scan.
        String derived = derivedToken();
        StringBuilder all = new StringBuilder(aggregateText(disk));
        for (String line : report) all.append(line).append('\n');
        boolean privacy = PRIVACY_ENABLED
            && (!URL.matcher(all).find()
            && !CREDENTIAL.matcher(all).find()
            && !PIN.matcher(all).find()
            && !PAYLOAD.matcher(all).find()
            && all.indexOf(derived) < 0);
        check(privacy, "PRIVACY_SCAN", "patterns=5");

        check(report.size() <= REPORT_LINE_CAP, "REPORT_BOUND", "lines=" + report.size());

        System.out.println("SUMMARY total=" + total + " passed=" + passed + " failed=" + failed);
        System.out.println("FIRST " + (first == null ? "NONE" : first));
    }

    private static String checkCodeFor(String scenario) {
        if (scenario.startsWith("low_")) return "COPY_STORAGE_RECHECK";
        if (scenario.startsWith("capacity_")) return "CAPACITY_FAIL_CLOSED";
        if (scenario.startsWith("cancel_")) return "CANCEL_ACCOUNTING";
        if (scenario.equals("zero_read")) return "NO_PROGRESS_REJECTION";
        return "COPY_BYTE_ACCOUNTING";
    }

    // ---------- real production calls ----------

    private static final AtomicInteger peakThreads = new AtomicInteger(0);
    private static int baselineThreads = 0;

    private static List<CaseResult> runCases(final List<String[]> cases, final Path scratch) throws Exception {
        baselineThreads = Thread.activeCount();
        final java.util.concurrent.atomic.AtomicBoolean stopMonitor = new AtomicBoolean(false);
        Thread monitor = new Thread(new Runnable() {
            @Override public void run() {
                while (!stopMonitor.get()) {
                    int n = Thread.activeCount();
                    if (n > peakThreads.get()) peakThreads.set(n);
                    try { Thread.sleep(2); } catch (InterruptedException e) { return; }
                }
            }
        }, "qa48-monitor");
        monitor.setDaemon(true);
        monitor.start();

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);
            @Override public Thread newThread(Runnable r) {
                return new Thread(r, "qa48-copy-" + seq.incrementAndGet());
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKER_THREADS, factory);
        List<Future<CaseResult>> futures = new ArrayList<Future<CaseResult>>();
        final StreamRecorder recorder = new StreamRecorder(COPY_BUFFER_BYTES, MIN_FREE_BYTES);
        for (final String[] c : cases) {
            futures.add(pool.submit(new java.util.concurrent.Callable<CaseResult>() {
                @Override public CaseResult call() {
                    return runOne(recorder, c, scratch);
                }
            }));
        }
        List<CaseResult> out = new ArrayList<CaseResult>();
        try {
            for (Future<CaseResult> f : futures) {
                try {
                    out.add(f.get(20, TimeUnit.SECONDS));
                } catch (Throwable t) {
                    out.add(new CaseResult(false, t.getClass().getSimpleName()));
                }
            }
        } finally {
            stopMonitor.set(true);
            pool.shutdownNow();
            try { pool.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            try { monitor.join(500); } catch (InterruptedException ignored) {}
        }
        return out;
    }

    private static final class CaseResult {
        final boolean ok;
        final String detail;
        CaseResult(boolean ok, String detail) { this.ok = ok; this.detail = detail; }
    }

    private static CaseResult runOne(StreamRecorder recorder, String[] c, Path scratch) {
        String scenario = c[1];
        int inputBytes = Integer.parseInt(c[2]);
        long freeBefore = Long.parseLong(c[3]);
        long freeAfter = Long.parseLong(c[4]);
        long written = Long.parseLong(c[5]);
        int cancelAfter = Integer.parseInt(c[6]);
        String wantStop = c[7];
        long wantWritten = Long.parseLong(c[8]);
        long wantRemaining = Long.parseLong(c[9]);

        try {
            if (scenario.startsWith("capacity_")) {
                Long reported = freeBefore < 0 && scenario.equals("capacity_none")
                    ? null : Long.valueOf(freeBefore);
                long got = RecordingPolicy.INSTANCE.remainingFreeBytes(reported, written);
                boolean ok = got == wantRemaining;
                return new CaseResult(ok, "remaining=" + got);
            }
            final AtomicLong free = new AtomicLong(freeBefore);
            final AtomicBoolean cancelled = new AtomicBoolean(cancelAfter == 1);
            final AtomicInteger reads = new AtomicInteger(0);
            byte[] payload = new byte[inputBytes];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) ((i * 31 + 7) & 0x7f);
            }
            InputStream raw = new ByteArrayInputStream(payload);
            InputStream input = new InputStream() {
                @Override public int read() throws IOException { return raw.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = raw.read(b, off, len);
                    if (n > 0) {
                        int r = reads.incrementAndGet();
                        if (cancelAfter >= 2 && r == cancelAfter - 1) cancelled.set(true);
                        if (r == 1) free.set(freeAfter);
                    }
                    return n;
                }
                @Override public void close() throws IOException { raw.close(); }
            };
            boolean zeroRead = scenario.equals("zero_read");
            if (zeroRead) {
                input = new InputStream() {
                    @Override public int read() { return 0; }
                    @Override public int read(byte[] b, int off, int len) { return 0; }
                };
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            kotlin.jvm.functions.Function0<Long> freeFn = new kotlin.jvm.functions.Function0<Long>() {
                @Override public Long invoke() { return Long.valueOf(free.get()); }
            };
            kotlin.jvm.functions.Function0<Boolean> cancelFn = new kotlin.jvm.functions.Function0<Boolean>() {
                @Override public Boolean invoke() { return Boolean.valueOf(cancelled.get()); }
            };
            kotlin.jvm.functions.Function1<Long, kotlin.Unit> progressFn =
                new kotlin.jvm.functions.Function1<Long, kotlin.Unit>() {
                    @Override public kotlin.Unit invoke(Long v) { return kotlin.Unit.INSTANCE; }
                };
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            StreamRecorder.Result result = recorder.copy(input, output, freeFn, cancelFn, progressFn, buffer);
            if (zeroRead) {
                return new CaseResult(false, "no_progress_exception_absent");
            }
            String gotStop = result.getStopReason().name();
            long gotWritten = result.getBytesWritten();
            boolean ok = gotStop.equals(wantStop) && gotWritten == wantWritten;
            if (!ok) {
                return new CaseResult(false, "stop=" + gotStop + " written=" + gotWritten);
            }
            return new CaseResult(ok, "stop=" + gotStop + " written=" + gotWritten);
        } catch (Throwable t) {
            String cn = t.getClass().getSimpleName();
            if ("zero_read".equals(scenario)) {
                boolean ok = "IOException".equals(cn)
                    && String.valueOf(t.getMessage()).contains("no progress");
                return new CaseResult(ok, "exception=" + cn);
            }
            if ("low_before".equals(scenario)) {
                boolean ok = "IllegalArgumentException".equals(cn)
                    && String.valueOf(t.getMessage()).contains("Insufficient free storage");
                return new CaseResult(ok, "exception=" + cn);
            }
            return new CaseResult(false, "exception=" + cn);
        }
    }

    // ---------- strict parsers ----------

    private static Map<String, String> parseDimensions(String text) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String line : lines(text)) {
            String[] f = line.split("=", -1);
            if (f.length != 2 || f[1].isEmpty()) return null;
            m.put(f[0], f[1]);
        }
        String[] keys = {"schema", "seed", "max_cases", "max_corrupt", "max_input_bytes",
            "copy_buffer_bytes", "min_free_bytes", "report_line_cap"};
        if (m.size() != keys.length) return null;
        for (String k : keys) if (!m.containsKey(k)) return null;
        if (!m.get("seed").equals(SEED)) return null;
        if (!m.get("max_cases").equals(String.valueOf(MAX_CASES))) return null;
        if (!m.get("max_corrupt").equals(String.valueOf(MAX_CORRUPT))) return null;
        if (!m.get("copy_buffer_bytes").equals(String.valueOf(COPY_BUFFER_BYTES))) return null;
        if (!m.get("min_free_bytes").equals(String.valueOf(MIN_FREE_BYTES))) return null;
        return m;
    }

    private static List<String[]> parseTable(String text, String header, int cols) {
        List<String[]> out = new ArrayList<String[]>();
        List<String> ls = lines(text);
        if (ls.isEmpty() || !ls.get(0).equals(header)) return out;
        for (int i = 1; i < ls.size(); i++) {
            out.add(ls.get(i).split("\t", -1));
        }
        return out;
    }

    /** Returns the single rule violated by a corrupt row, or null when accepted. */
    private static String rejectReason(String[] row) {
        if (row.length != 11) return "FIELD_COUNT";
        if (!ID.matcher(row[1]).matches()) return "BAD_ID";
        if (!SCENARIOS.contains(row[2])) return "BAD_SCENARIO";
        int[] numericColumns = {3, 4, 5, 6, 7, 9, 10};
        long[] numeric = new long[numericColumns.length];
        for (int k = 0; k < numericColumns.length; k++) {
            try {
                numeric[k] = Long.parseLong(row[numericColumns[k]]);
            } catch (NumberFormatException e) {
                return "BAD_NUMBER";
            }
        }
        if (numeric[0] < 0) return "NEGATIVE_INPUT";
        if (numeric[3] < 0) return "NEGATIVE_WRITTEN";
        if (numeric[0] > MAX_INPUT_BYTES) return "UNBOUNDED_INPUT";
        if (!STOPS.contains(row[8])) return "BAD_STOP";
        return null;
    }

    // ---------- reporting and helpers ----------

    private void check(boolean ok, String code, String detail) {
        total++;
        if (ok) {
            passed++;
            String line = "PASS\t" + code + "\t" + detail;
            report.add(line);
            System.out.println(line);
        } else {
            fail(code, detail);
        }
    }

    private void fail(String code, String detail) {
        total++;
        failed++;
        if (first == null) first = code;
        String line = "FAIL\t" + code + "\t" + detail;
        report.add(line);
        System.out.println(line);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String derivedToken() {
        byte[] d = sha256(bytes(SEED));
        StringBuilder b = new StringBuilder("qa48live");
        for (int i = 0; i < 4; i++) b.append(String.format("%02x", d[i]));
        return b.toString();
    }

    private static String aggregateText(Map<String, byte[]> files) {
        StringBuilder b = new StringBuilder();
        for (String name : FILE_ORDER) {
            byte[] data = files.get(name);
            if (data != null) b.append(new String(data, StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static String aggregate(Map<String, byte[]> files) {
        MessageDigest md = newDigest();
        for (String name : FILE_ORDER) {
            byte[] data = files.get(name);
            if (data == null) continue;
            md.update(name.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(data);
        }
        return hex(md.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] b) {
        MessageDigest md = newDigest();
        md.update(b);
        return md.digest();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Keeps a trailing empty element only when the file really has a blank line. */
    private static List<String> lines(String s) {
        List<String> out = new ArrayList<String>();
        if (s.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }
}
