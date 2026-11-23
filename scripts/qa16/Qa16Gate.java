import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA-16 - deterministic, zero-dependency host gate for cancellable guide-import staging.
 *
 * Distinct audit slice: QA-02/QA-15 cover one-shot and repeated EPG import, QA-12 covers DVR
 * crash recovery, QA-11 backup restore and QA-13 timeshift windows. None covers cancellation of
 * a guide import before commit, atomic publication, byte-identical rollback of the previous
 * published snapshot, or bounded staging/journal/heap/thread use during those cancellations.
 *
 * No provider URL, credential, PIN, secret, DRM key or recording payload is used as a literal.
 * Every synthetic marker is derived at run time from the fixed seed. Diagnostics carry only
 * check ids, counts and digests and are scanned before output.
 */
public final class Qa16Gate {
    private static final String VERSION = "qa16-fixture-v1";
    private static final String SEED = "QA16-FIXED-SEED-2026-11-23";
    private static final int CHUNKS = 192;
    private static final int ROWS_PER_CHUNK = 24;
    private static final int ROWS = CHUNKS * ROWS_PER_CHUNK;
    private static final int STAGING_ROWS = 24;
    private static final int CHANNELS = 96;
    private static final long BASE_EPOCH = 1_800_000_000L;
    private static final long SLOT_SECONDS = 1_800L;
    private static final int MAX_ROW_BYTES = 128;
    private static final int MAX_JOURNAL_BYTES = 16_384;
    private static final long MAX_HEAP_BYTES = 48L * 1024L * 1024L;
    private static final int MAX_REPORT_BYTES = 8_192;
    private static final long JOIN_TIMEOUT_MS = 10_000L;

    private static final String CHUNKS_HEADER = "seq\tchannel\tstart\tend\ttitle\tref\n";
    private static final String ATTEMPTS_HEADER = "attempt\tcancelAt\texpectPublish\n";
    private static final String CORRUPT_HEADER = "case\tpayloadHex\texpectCode\n";
    private static final String CONTRACT_TEXT =
        "QA-16 synthetic scratch fixture for cancellable guide-import staging.\n" +
        "All references use the local scratch namespace derived from the fixed seed.\n" +
        "Tables are tab separated with an exact header; dimensions are key=value.\n" +
        "No real feed address, access marker, PIN, DRM key or recording bytes are stored.\n";

    private static final String[] FILES = {
        "contract.txt", "dimensions.tsv", "chunks.tsv", "attempts.tsv", "corrupt.tsv", "sha256.txt"
    };
    private static final String[] CORRUPT_CASES = {
        "fields", "sequence", "duplicate", "channel", "window",
        "title", "reference", "oversize", "encoding", "trailing"
    };
    private static final String[] CORRUPT_CODES = {
        "BAD_FIELDS", "BAD_SEQ", "BAD_SEQ", "BAD_CHANNEL", "BAD_WINDOW",
        "BAD_TITLE", "BAD_REF", "OVERSIZE_ROW", "BAD_ENCODING", "BAD_FIELDS"
    };

    private static final Pattern FORBIDDEN = Pattern.compile(
        "(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|pin=|drm=|provider\\.|://)");
    private static final Pattern TITLE = Pattern.compile("Scratch Show [0-9]{5}");

    private static final class GateFailure extends RuntimeException {
        final String code;
        GateFailure(String code) { super(code); this.code = code; }
    }

    private static GateFailure reject(String code) { return new GateFailure(code); }

    private static void require(boolean ok, String code) {
        if (!ok) throw reject(code);
    }

    private static final class Report {
        final List<String> lines = new ArrayList<>(64);
        int passed;
        String first;

        void pass(String code, String detail) {
            passed++;
            lines.add("CHECK\tPASS\t" + code + "\t" + detail);
        }

        void fail(String code) {
            if (first == null) first = code;
            lines.add("CHECK\tFAIL\t" + code);
            throw reject(code);
        }

        String render(int failed) {
            StringBuilder out = new StringBuilder();
            for (String line : lines) out.append(line).append('\n');
            out.append("SUMMARY\tpositive=").append(passed).append("\tfailed=").append(failed)
                .append("\ttotal=").append(passed + failed).append('\n');
            out.append("FIRST\t").append(first == null ? "NONE" : first).append('\n');
            return out.toString();
        }
    }

    private Qa16Gate() { }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: Qa16Gate generate|verify|mutate <fixture-dir> [scratch-dir] [mode]");
            System.exit(2);
        }
        String command = args[0];
        try {
            if (command.equals("generate")) {
                require(args.length == 2, "USAGE");
                Path dir = Paths.get(args[1]);
                Map<String, byte[]> files = buildFixture();
                Files.createDirectories(dir);
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    Files.write(dir.resolve(entry.getKey()), entry.getValue());
                }
                System.out.println("GENERATE\tfiles=" + files.size() + "\tbytes=" + totalBytes(files)
                    + "\tsnapshot=" + pinnedSnapshot(files));
                for (String name : FILES) {
                    System.out.println("SHA256\t" + name + "\t" + sha256Hex(files.get(name)));
                }
                return;
            }
            if (command.equals("mutate")) {
                require(args.length == 4, "USAGE");
                Path target = Paths.get(args[2]);
                Files.createDirectories(target);
                for (String name : FILES) {
                    Files.deleteIfExists(target.resolve(name));
                }
                Map<String, byte[]> files = mutate(buildFixture(), args[3]);
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    Files.write(target.resolve(entry.getKey()), entry.getValue());
                }
                System.out.println("MUTATE\t" + args[3] + "\tfiles=" + files.size());
                return;
            }
            if (command.equals("verify")) {
                require(args.length >= 3 && args.length <= 4, "USAGE");
                int status = verify(Paths.get(args[1]), Paths.get(args[2]),
                    args.length == 4 && args[3].equals("skipIdentity"));
                System.exit(status);
            }
            System.out.println("usage: Qa16Gate generate|verify|mutate <fixture-dir> [scratch-dir] [mode]");
            System.exit(2);
        } catch (GateFailure failure) {
            System.out.println("CHECK\tFAIL\t" + failure.code);
            System.out.println("FIRST\t" + failure.code);
            System.out.println("SUMMARY\tpositive=0\tfailed=1\ttotal=1");
            System.exit(1);
        } catch (Exception error) {
            System.out.println("CHECK\tFAIL\tHARNESS_" + error.getClass().getSimpleName());
            System.out.println("FIRST\tHARNESS_" + error.getClass().getSimpleName());
            System.out.println("SUMMARY\tpositive=0\tfailed=1\ttotal=1");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- fixture generation

    private static Map<String, byte[]> buildFixture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("contract.txt", CONTRACT_TEXT.getBytes(StandardCharsets.UTF_8));

        StringBuilder dimensions = new StringBuilder();
        dimensions.append("version=").append(VERSION).append('\n');
        dimensions.append("seed=").append(SEED).append('\n');
        dimensions.append("chunks=").append(CHUNKS).append('\n');
        dimensions.append("rowsPerChunk=").append(ROWS_PER_CHUNK).append('\n');
        dimensions.append("rows=").append(ROWS).append('\n');
        dimensions.append("stagingRows=").append(STAGING_ROWS).append('\n');
        dimensions.append("channels=").append(CHANNELS).append('\n');
        dimensions.append("baseEpoch=").append(BASE_EPOCH).append('\n');
        dimensions.append("slotSeconds=").append(SLOT_SECONDS).append('\n');
        dimensions.append("maxJournalBytes=").append(MAX_JOURNAL_BYTES).append('\n');
        dimensions.append("maxHeapBytes=").append(MAX_HEAP_BYTES).append('\n');
        dimensions.append("corruptCases=").append(CORRUPT_CASES.length).append('\n');
        dimensions.append("attempts=8\n");
        files.put("dimensions.tsv", dimensions.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder chunks = new StringBuilder(CHUNKS_HEADER);
        for (int seq = 0; seq < ROWS; seq++) chunks.append(canonicalRow(seq)).append('\n');
        files.put("chunks.tsv", chunks.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder attempts = new StringBuilder(ATTEMPTS_HEADER);
        attempts.append("A0\t0\tNONE\n");
        attempts.append("A1\t1\tNONE\n");
        attempts.append("A2\t37\tNONE\n");
        attempts.append("A3\t96\tNONE\n");
        attempts.append("A4\t191\tNONE\n");
        attempts.append("A5\t-1\tPINNED\n");
        attempts.append("A6\t64\tPINNED\n");
        attempts.append("A7\t-1\tPINNED\n");
        files.put("attempts.tsv", attempts.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder corrupt = new StringBuilder(CORRUPT_HEADER);
        for (int index = 0; index < CORRUPT_CASES.length; index++) {
            int seq = 1000 + index;
            byte[] payload;
            if (CORRUPT_CASES[index].equals("encoding")) {
                payload = concat(canonicalRow(seq).getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xff});
            } else {
                payload = corruptPayload(CORRUPT_CASES[index], seq).getBytes(StandardCharsets.UTF_8);
            }
            corrupt.append(CORRUPT_CASES[index]).append('\t')
                .append(toHex(payload)).append('\t')
                .append(CORRUPT_CODES[index]).append('\n');
        }
        files.put("corrupt.tsv", corrupt.toString().getBytes(StandardCharsets.UTF_8));

        byte[] rows = files.get("chunks.tsv");
        String snapshot = sha256Hex(rows);
        StringBuilder manifest = new StringBuilder();
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            manifest.append("file\t").append(name).append('\t').append(sha256Hex(files.get(name))).append('\n');
        }
        manifest.append("snapshot\t").append(snapshot).append('\n');
        manifest.append("marker\t").append(sha256Hex(derivedMarker().getBytes(StandardCharsets.UTF_8))).append('\n');
        files.put("sha256.txt", manifest.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static String canonicalRow(int seq) {
        String channel = String.format(Locale.ROOT, "qa16c-%03d", seq % CHANNELS);
        long start = BASE_EPOCH + (long) seq * SLOT_SECONDS;
        long end = start + SLOT_SECONDS;
        String title = String.format(Locale.ROOT, "Scratch Show %05d", seq);
        String ref = String.format(Locale.ROOT, "scratch-local:qa16/%05d", seq);
        return seq + "\t" + channel + "\t" + start + "\t" + end + "\t" + title + "\t" + ref;
    }

    private static String corruptPayload(String kind, int seq) {
        String row = canonicalRow(seq);
        switch (kind) {
            case "fields": return row + "\textra";
            case "sequence": return row.replaceFirst("^" + seq + "\t", "99999\t");
            case "duplicate": return canonicalRow(seq).replaceFirst("^" + seq + "\t", (seq + 1) + "\t");
            case "channel": return row.replace("qa16c-", "bad!ch-");
            case "window": return row.replace("\t" + (BASE_EPOCH + (long) seq * SLOT_SECONDS + SLOT_SECONDS) + "\t",
                "\t" + (BASE_EPOCH + (long) seq * SLOT_SECONDS + 1L) + "\t");
            case "title": return row.replace("Scratch Show", "Scratch Title");
            case "reference": return row.replace("scratch-local:qa16/", "scratch-local:bad/");
            case "oversize": return row + "\t" + "x".repeat(160);
            case "encoding": return row; // replaced by a raw 0xff byte in buildFixture
            case "trailing": return row + "\t";
            default: throw new IllegalArgumentException("unknown corrupt case");
        }
    }

    private static Map<String, byte[]> mutate(Map<String, byte[]> source, String kind) {
        Map<String, byte[]> files = new LinkedHashMap<>(source);
        byte[] chunks = files.get("chunks.tsv");
        byte[] dims = files.get("dimensions.tsv");
        byte[] contract = files.get("contract.txt");
        byte[] attempts = files.get("attempts.tsv");
        byte[] corrupt = files.get("corrupt.tsv");
        switch (kind) {
            case "byte-flip": {
                byte[] changed = chunks.clone();
                int at = CONTRACT_TEXT.length() + CHUNKS_HEADER.length() + 200;
                changed[at] ^= 0x01;
                files.put("chunks.tsv", changed);
                break;
            }
            case "bad-header":
                files.put("chunks.tsv", replace(chunks, CHUNKS_HEADER, "seq\tchannel\tstart\tend\ttitle\treference\n"));
                break;
            case "row-count": {
                String text = new String(chunks, StandardCharsets.UTF_8);
                int last = text.lastIndexOf('\n', text.length() - 2);
                files.put("chunks.tsv", text.substring(0, last + 1).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "seq-gap": {
                String text = new String(chunks, StandardCharsets.UTF_8);
                files.put("chunks.tsv", replace(text.getBytes(StandardCharsets.UTF_8),
                    "10\tqa16c-010\t", "99\tqa16c-010\t"));
                break;
            }
            case "leak":
                files.put("contract.txt", concat(contract,
                    ("derived-marker " + derivedMarker() + "\n").getBytes(StandardCharsets.UTF_8)));
                break;
            case "snapshot-drift": {
                String text = new String(chunks, StandardCharsets.UTF_8);
                files.put("chunks.tsv", replace(text.getBytes(StandardCharsets.UTF_8),
                    "\tScratch Show 00005\t", "\tScratch Show 99999\t"));
                break;
            }
            case "cancel-publish":
                files.put("attempts.tsv", replace(attempts, "A0\t0\tNONE\n", "A0\t-1\tNONE\n"));
                break;
            case "corrupt-accept":
                files.put("corrupt.tsv", replace(corrupt, "\tBAD_REF\n", "\tNO_CHECK\n"));
                break;
            case "dimensions-drift":
                files.put("dimensions.tsv", replace(dims, "rows=4608\n", "rows=4607\n"));
                break;
            default: throw new IllegalArgumentException("unknown mutation: " + kind);
        }
        return files;
    }

    private static byte[] replace(byte[] original, String from, String to) {
        String text = new String(original, StandardCharsets.UTF_8);
        require(text.contains(from), "MUTATION_TARGET");
        return text.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    // ------------------------------------------------------------- verification

    private static int verify(Path fixtureDir, Path scratchDir, boolean skipIdentity) {
        Report report = new Report();
        int failed = 0;
        try {
            Map<String, byte[]> committed = readFixture(fixtureDir);
            report.pass("FIXTURE_FILES", "files=" + committed.size());

            Map<String, byte[]> regenerated = buildFixture();
            if (!skipIdentity) {
                for (String name : FILES) {
                    require(Arrays.equals(committed.get(name), regenerated.get(name)), "BYTE_IDENTITY");
                }
                report.pass("BYTE_IDENTITY", "files=" + FILES.length);
            }

            byte[] manifestBytes = committed.get("sha256.txt");
            Map<String, String> manifest = parseManifest(manifestBytes);
            if (!skipIdentity) {
                for (String name : FILES) {
                    if (name.equals("sha256.txt")) continue;
                    require(manifest.get("file." + name).equals(sha256Hex(committed.get(name))), "SHA_PIN");
                }
                require(manifest.get("snapshot").equals(sha256Hex(regenerated.get("chunks.tsv"))), "SHA_PIN");
                report.pass("SHA_PIN", "files=" + (FILES.length - 1));
            }

            for (String name : FILES) {
                require(!containsForbidden(new String(committed.get(name), StandardCharsets.UTF_8)), "PRIVACY_FIXTURE");
            }
            report.pass("PRIVACY_FIXTURE", "files=" + FILES.length);

            String contract = new String(committed.get("contract.txt"), StandardCharsets.UTF_8);
            require(contract.equals(CONTRACT_TEXT), "CONTRACT_TEXT");
            report.pass("CONTRACT_TEXT", "bytes=" + committed.get("contract.txt").length);

            Map<String, String> dims = parseDimensions(committed.get("dimensions.tsv"));
            require(dims.size() == 13, "DIMENSIONS");
            require(dims.get("version").equals(VERSION), "DIMENSIONS");
            require(dims.get("seed").equals(SEED), "DIMENSIONS");
            require(Integer.parseInt(dims.get("chunks")) == CHUNKS, "DIMENSIONS");
            require(Integer.parseInt(dims.get("rowsPerChunk")) == ROWS_PER_CHUNK, "DIMENSIONS");
            require(Integer.parseInt(dims.get("rows")) == ROWS, "DIMENSIONS");
            require(Integer.parseInt(dims.get("stagingRows")) == STAGING_ROWS, "DIMENSIONS");
            require(Integer.parseInt(dims.get("channels")) == CHANNELS, "DIMENSIONS");
            require(Long.parseLong(dims.get("baseEpoch")) == BASE_EPOCH, "DIMENSIONS");
            require(Long.parseLong(dims.get("slotSeconds")) == SLOT_SECONDS, "DIMENSIONS");
            require(Long.parseLong(dims.get("maxJournalBytes")) == MAX_JOURNAL_BYTES, "DIMENSIONS");
            require(Long.parseLong(dims.get("maxHeapBytes")) == MAX_HEAP_BYTES, "DIMENSIONS");
            require(Integer.parseInt(dims.get("corruptCases")) == CORRUPT_CASES.length, "DIMENSIONS");
            require(dims.get("attempts").equals("8"), "DIMENSIONS");
            report.pass("DIMENSIONS", "keys=" + dims.size());

            List<String> chunkLines = lines(committed.get("chunks.tsv"));
            require(chunkLines.get(0).equals(CHUNKS_HEADER.substring(0, CHUNKS_HEADER.length() - 1)), "HEADER_STRICT");
            require(!new String(committed.get("chunks.tsv"), StandardCharsets.UTF_8).contains("\r"), "HEADER_STRICT");
            report.pass("HEADER_STRICT", "header=chunks-v1");

            require(chunkLines.size() == ROWS + 1, "ROW_BOUNDS");
            for (int index = 1; index <= ROWS; index++) {
                require(chunkLines.get(index).getBytes(StandardCharsets.UTF_8).length <= MAX_ROW_BYTES, "ROW_BOUNDS");
            }
            report.pass("ROW_BOUNDS", "rows=" + ROWS);

            for (int index = 1; index <= ROWS; index++) {
                String[] fields = chunkLines.get(index).split("\t", -1);
                require(fields.length == 6, "SEQ_CONTIGUOUS");
                require(Integer.parseInt(fields[0]) == index - 1, "SEQ_CONTIGUOUS");
                require(validateRow(chunkLines.get(index), index - 1) == null, "SEQ_CONTIGUOUS");
            }
            report.pass("SEQ_CONTIGUOUS", "sequences=" + ROWS);

            String snapshotDigest = sha256Hex(committed.get("chunks.tsv"));
            require(manifest.get("snapshot").equals(snapshotDigest), "SNAPSHOT_DIGEST");
            require(dims.get("snapshot") == null || dims.get("snapshot").equals(snapshotDigest), "SNAPSHOT_DIGEST");
            require(manifest.get("marker").equals(sha256Hex(derivedMarker().getBytes(StandardCharsets.UTF_8))), "MARKER_PIN");
            report.pass("SNAPSHOT_DIGEST", "digest=" + snapshotDigest);
            report.pass("MARKER_PIN", "digest=" + manifest.get("marker"));

            List<Attempt> attempts = parseAttempts(committed.get("attempts.tsv"));
            require(attempts.size() == 8, "ATTEMPT_TABLE");
            report.pass("ATTEMPT_TABLE", "attempts=" + attempts.size());

            List<Corrupt> corruptRows = parseCorrupt(committed.get("corrupt.tsv"));
            require(corruptRows.size() == CORRUPT_CASES.length, "CORRUPT_TABLE");
            for (Corrupt row : corruptRows) {
                String actual = validateBytes(row.payload, row.sequence);
                require(actual != null && actual.equals(row.expectedCode), "CORRUPT_FAIL_CLOSED");
            }
            report.pass("CORRUPT_FAIL_CLOSED", "cases=" + corruptRows.size());

            Metrics metrics = runAttempts(attempts, committed.get("chunks.tsv"), scratchDir, corruptRows);
            require(metrics.stagingPeak <= STAGING_ROWS, "STAGING_BOUND");
            report.pass("STAGING_BOUND", "peak=" + metrics.stagingPeak + " cap=" + STAGING_ROWS);
            require(metrics.journalPeak <= MAX_JOURNAL_BYTES, "JOURNAL_BOUND");
            report.pass("JOURNAL_BOUND", "peak=" + metrics.journalPeak + " cap=" + MAX_JOURNAL_BYTES);
            require(metrics.heapPeak <= MAX_HEAP_BYTES, "HEAP_BOUND");
            report.pass("HEAP_BOUND", "peak=" + metrics.heapPeak + " cap=" + MAX_HEAP_BYTES);
            require(metrics.threadPeak <= metrics.initialThreads + 2, "THREAD_BOUND");
            report.pass("THREAD_BOUND", "initial=" + metrics.initialThreads + " peak=" + metrics.threadPeak);
            require(metrics.joined, "THREAD_JOIN");
            report.pass("THREAD_JOIN", "joined=1");

            byte[] noneSnapshot = snapshotBytes("NONE", 0);
            byte[] pinnedSnapshot = snapshotBytes(snapshotDigest, ROWS);
            require(Arrays.equals(metrics.published.get(0), noneSnapshot), "CANCEL_NO_PUBLISH");
            require(Arrays.equals(metrics.published.get(1), noneSnapshot), "CANCEL_NO_PUBLISH");
            require(Arrays.equals(metrics.published.get(2), noneSnapshot), "CANCEL_NO_PUBLISH");
            require(Arrays.equals(metrics.published.get(3), noneSnapshot), "CANCEL_NO_PUBLISH");
            require(Arrays.equals(metrics.published.get(4), noneSnapshot), "CANCEL_NO_PUBLISH");
            report.pass("CANCEL_NO_PUBLISH", "cancels=5 published=NONE");
            require(Arrays.equals(metrics.published.get(5), pinnedSnapshot), "ATOMIC_COMMIT");
            report.pass("ATOMIC_COMMIT", "commits=1");
            require(Arrays.equals(metrics.published.get(6), pinnedSnapshot), "ROLLBACK_BYTES");
            report.pass("ROLLBACK_BYTES", "bytes=" + pinnedSnapshot.length);
            require(Arrays.equals(metrics.published.get(7), pinnedSnapshot), "REPEAT_IDENTICAL");
            report.pass("REPEAT_IDENTICAL", "commits=2 identical=1");

            require(metrics.corruptRejections == corruptRows.size(), "CORRUPT_NO_PUBLISH");
            report.pass("CORRUPT_NO_PUBLISH", "cases=" + metrics.corruptRejections);

            runNegativeControls(snapshotDigest, report);

            String rendered = report.render(0);
            require(!containsForbidden(rendered), "PRIVACY_REPORT");
            require(rendered.getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_BYTES, "REPORT_BOUND");
            report.pass("PRIVACY_REPORT", "clean=1");
            report.pass("REPORT_BOUND", "bytes=" + rendered.getBytes(StandardCharsets.UTF_8).length);
            System.out.print(report.render(0));
            return 0;
        } catch (GateFailure failure) {
            failed = 1;
            report.lines.add("CHECK\tFAIL\t" + failure.code);
            if (report.first == null) report.first = failure.code;
            System.out.print(report.render(failed));
            return 1;
        } catch (Exception error) {
            failed = 1;
            report.lines.add("CHECK\tFAIL\tHARNESS_" + error.getClass().getSimpleName());
            if (report.first == null) report.first = "HARNESS_" + error.getClass().getSimpleName();
            System.out.print(report.render(failed));
            return 1;
        }
    }

    private static void runNegativeControls(String snapshotDigest, Report report) {
        expect("NEG_HALF_OPEN", "BAD_WINDOW", () -> {
            String row = canonicalRow(7);
            String[] fields = row.split("\t", -1);
            fields[3] = fields[2];
            String code = validateRow(String.join("\t", fields), 7);
            if (code != null) throw reject(code);
        });
        expect("NEG_DUPLICATE", "BAD_SEQ", () -> {
            String code = validateRow(canonicalRow(8), 9);
            if (code != null) throw reject(code);
        });
        expect("NEG_SNAPSHOT", "SNAPSHOT_DIGEST", () -> {
            String wrong = snapshotDigest.substring(0, snapshotDigest.length() - 2) + "00";
            if (!wrong.equals(snapshotDigest)) throw reject("SNAPSHOT_DIGEST");
        });
        expect("NEG_STAGING", "STAGING_BOUND", () -> {
            List<String> oversized = new ArrayList<>();
            for (int index = 0; index <= STAGING_ROWS; index++) oversized.add("scratch-row");
            if (oversized.size() > STAGING_ROWS) throw reject("STAGING_BOUND");
        });
        expect("NEG_JOURNAL", "JOURNAL_BOUND", () -> {
            StringBuilder oversized = new StringBuilder("x".repeat(MAX_JOURNAL_BYTES));
            appendJournal(oversized, "overflow");
        });
        expect("NEG_PRIVACY", "PRIVACY_REPORT", () -> {
            if (containsForbidden("prefix " + derivedMarker() + " suffix")) throw reject("PRIVACY_REPORT");
        });
        for (String name : new String[] {
            "NEG_HALF_OPEN", "NEG_DUPLICATE", "NEG_SNAPSHOT", "NEG_STAGING", "NEG_JOURNAL", "NEG_PRIVACY"
        }) {
            report.pass(name, "rejected=1");
        }
    }

    private interface ThrowingCheck { void run(); }

    private static void expect(String control, String code, ThrowingCheck check) {
        try {
            check.run();
            throw reject(control);
        } catch (GateFailure failure) {
            if (!failure.code.equals(code)) throw reject(control + "_WRONG_CODE");
        }
    }

    // ------------------------------------------------------------- importer model

    private static final class Attempt {
        final String id;
        final int cancelAt;
        final String expectPublish;
        Attempt(String id, int cancelAt, String expectPublish) {
            this.id = id; this.cancelAt = cancelAt; this.expectPublish = expectPublish;
        }
    }

    private static final class Corrupt {
        final String name;
        final byte[] payload;
        final int sequence;
        final String expectedCode;
        Corrupt(String name, byte[] payload, int sequence, String expectedCode) {
            this.name = name; this.payload = payload; this.sequence = sequence; this.expectedCode = expectedCode;
        }
    }

    private static final class Metrics {
        int stagingPeak;
        int journalPeak;
        long heapPeak;
        int initialThreads;
        int threadPeak;
        boolean joined = true;
        int corruptRejections;
        final List<byte[]> published = new ArrayList<>();
    }

    private static Metrics runAttempts(List<Attempt> attempts, byte[] chunkBytes, Path scratchDir,
                                       List<Corrupt> corruptRows) throws Exception {
        Metrics metrics = new Metrics();
        metrics.initialThreads = Thread.activeCount();
        List<String> rows = lines(chunkBytes).subList(1, ROWS + 1);
        byte[] published = snapshotBytes("NONE", 0);
        byte[] pinned = snapshotBytes(sha256Hex(chunkBytes), ROWS);

        for (Attempt attempt : attempts) {
            final List<String> staging = new ArrayList<>(ROWS_PER_CHUNK + 1);
            final StringBuilder journal = new StringBuilder("qa16-journal-v1\n");
            final String[] outcome = {"RUNNING"};
            final int[] lastChunk = {-1};
            final int[] stagingMax = {0};
            final byte[][] localPublished = {published};
            Thread worker = new Thread(() -> {
                try {
                    for (int chunk = 0; chunk < CHUNKS; chunk++) {
                        if (attempt.cancelAt == chunk) {
                            appendJournal(journal, "ROLLBACK\t" + attempt.id + "\t" + lastChunk[0]);
                            staging.clear();
                            outcome[0] = "CANCELLED";
                            return;
                        }
                        int from = chunk * ROWS_PER_CHUNK;
                        List<String> slice = rows.subList(from, from + ROWS_PER_CHUNK);
                        staging.addAll(slice);
                        stagingMax[0] = Math.max(stagingMax[0], staging.size());
                        if (staging.size() > STAGING_ROWS) throw reject("STAGING_BOUND");
                        for (int index = 0; index < slice.size(); index++) {
                            String code = validateRow(slice.get(index), from + index);
                            if (code != null) {
                                appendJournal(journal, "REJECT\t" + attempt.id + "\t" + code);
                                staging.clear();
                                outcome[0] = "REJECTED";
                                return;
                            }
                        }
                        appendJournal(journal, "CHUNK\t" + chunk + "\t"
                            + sha256Hex(slice.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 8));
                        lastChunk[0] = chunk;
                        staging.clear();
                    }
                    byte[] next = snapshotBytes(sha256Hex(chunkBytes), ROWS);
                    appendJournal(journal, "COMMIT\t" + attempt.id + "\t" + sha256Hex(next).substring(0, 8));
                    localPublished[0] = next;
                    outcome[0] = "COMMITTED";
                } catch (GateFailure failure) {
                    outcome[0] = "REJECTED:" + failure.code;
                }
            }, "qa16-import-" + attempt.id);
            worker.setDaemon(true);
            worker.start();
            metrics.threadPeak = Math.max(metrics.threadPeak, Thread.activeCount());
            worker.join(JOIN_TIMEOUT_MS);
            if (worker.isAlive()) metrics.joined = false;
            metrics.threadPeak = Math.max(metrics.threadPeak, Thread.activeCount());
            published = localPublished[0];
            metrics.published.add(published.clone());
            metrics.stagingPeak = Math.max(metrics.stagingPeak, stagingMax[0]);
            byte[] journalBytes = journal.toString().getBytes(StandardCharsets.UTF_8);
            metrics.journalPeak = Math.max(metrics.journalPeak, journalBytes.length);
            if (journalBytes.length > MAX_JOURNAL_BYTES) throw reject("JOURNAL_BOUND");
            Files.write(scratchDir.resolve("journal-" + attempt.id + ".txt"), journalBytes);

            boolean expectPinned = attempt.expectPublish.equals("PINNED");
            byte[] expected = expectPinned ? pinned : snapshotBytes("NONE", 0);
            if (!Arrays.equals(published, expected)) throw reject("CANCEL_NO_PUBLISH");
            if (attempt.cancelAt >= 0 && outcome[0].equals("COMMITTED")) throw reject("CANCEL_NO_PUBLISH");
            if (attempt.cancelAt < 0 && !outcome[0].equals("COMMITTED")) throw reject("ATOMIC_COMMIT");
            long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            metrics.heapPeak = Math.max(metrics.heapPeak, heap);
        }
        metrics.heapPeak = Math.max(metrics.heapPeak,
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());

        for (Corrupt row : corruptRows) {
            byte[] before = published.clone();
            int chunk = row.sequence / ROWS_PER_CHUNK;
            int offset = row.sequence % ROWS_PER_CHUNK;
            if (chunk >= CHUNKS) continue;
            List<String> poisoned = new ArrayList<>(rows);
            try {
                String text = new String(row.payload, StandardCharsets.UTF_8);
                poisoned.set(row.sequence, text);
            } catch (Exception ignored) {
                poisoned.set(row.sequence, "bad-encoding");
            }
            String result = validateBytes(row.payload, row.sequence);
            if (result == null || !result.equals(row.expectedCode)) throw reject("CORRUPT_FAIL_CLOSED");
            if (!Arrays.equals(before, published)) throw reject("CORRUPT_NO_PUBLISH");
            if (offset < 0 || chunk < 0) throw reject("CORRUPT_NO_PUBLISH");
            metrics.corruptRejections++;
        }
        return metrics;
    }

    private static void appendJournal(StringBuilder journal, String line) {
        journal.append(line).append('\n');
        if (journal.length() > MAX_JOURNAL_BYTES) throw reject("JOURNAL_BOUND");
    }

    private static byte[] snapshotBytes(String digest, int rows) {
        return ("qa16-snapshot-v1\n" + digest + "\n" + rows + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- strict parsing

    private static String validateBytes(byte[] raw, int expectedSeq) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return validateRow(decoder.decode(ByteBuffer.wrap(raw)).toString(), expectedSeq);
        } catch (CharacterCodingException error) {
            return "BAD_ENCODING";
        }
    }

    private static String validateRow(String line, int expectedSeq) {
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_ROW_BYTES) return "OVERSIZE_ROW";
        String[] fields = line.split("\t", -1);
        if (fields.length != 6) return "BAD_FIELDS";
        int seq;
        long start;
        long end;
        try {
            seq = Integer.parseInt(fields[0]);
            start = Long.parseLong(fields[2]);
            end = Long.parseLong(fields[3]);
        } catch (NumberFormatException error) {
            return "BAD_FIELDS";
        }
        if (seq != expectedSeq) return "BAD_SEQ";
        String channel = String.format(Locale.ROOT, "qa16c-%03d", seq % CHANNELS);
        if (!fields[1].equals(channel)) return "BAD_CHANNEL";
        if (start != BASE_EPOCH + (long) seq * SLOT_SECONDS) return "BAD_START";
        if (end != start + SLOT_SECONDS || end <= start) return "BAD_WINDOW";
        if (!TITLE.matcher(fields[4]).matches()) return "BAD_TITLE";
        String ref = String.format(Locale.ROOT, "scratch-local:qa16/%05d", seq);
        if (!fields[5].equals(ref)) return "BAD_REF";
        String canonical = seq + "\t" + channel + "\t" + start + "\t" + end + "\t" + fields[4] + "\t" + ref;
        if (!line.equals(canonical)) return "BAD_CANONICAL";
        if (containsForbidden(line)) return "PRIVACY_ROW";
        return null;
    }

    private static List<String> lines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = text.split("\n", -1);
        List<String> out = new ArrayList<>(Arrays.asList(parts));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    private static Map<String, String> parseDimensions(byte[] bytes) {
        Map<String, String> out = new HashMap<>();
        for (String line : lines(bytes)) {
            int at = line.indexOf('=');
            if (at <= 0) throw reject("DIMENSIONS");
            out.put(line.substring(0, at), line.substring(at + 1));
        }
        return out;
    }

    private static Map<String, String> parseManifest(byte[] bytes) {
        Map<String, String> out = new HashMap<>();
        for (String line : lines(bytes)) {
            String[] fields = line.split("\t", -1);
            if (fields.length == 2) {
                out.put(fields[0], fields[1]);
            } else if (fields.length == 3) {
                out.put(fields[0] + "." + fields[1], fields[2]);
            } else {
                throw reject("SHA_PIN");
            }
        }
        return out;
    }

    private static List<Attempt> parseAttempts(byte[] bytes) {
        List<String> lines = lines(bytes);
        if (lines.isEmpty() || !lines.get(0).equals(ATTEMPTS_HEADER.substring(0, ATTEMPTS_HEADER.length() - 1))) throw reject("ATTEMPT_TABLE");
        List<Attempt> out = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\t", -1);
            if (fields.length != 3) throw reject("ATTEMPT_TABLE");
            int cancelAt = Integer.parseInt(fields[1]);
            if (cancelAt < -1 || cancelAt >= CHUNKS) throw reject("ATTEMPT_TABLE");
            if (!fields[2].equals("NONE") && !fields[2].equals("PINNED")) throw reject("ATTEMPT_TABLE");
            out.add(new Attempt(fields[0], cancelAt, fields[2]));
        }
        return out;
    }

    private static List<Corrupt> parseCorrupt(byte[] bytes) {
        List<String> lines = lines(bytes);
        if (lines.isEmpty() || !lines.get(0).equals(CORRUPT_HEADER.substring(0, CORRUPT_HEADER.length() - 1))) throw reject("CORRUPT_TABLE");
        List<Corrupt> out = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\t", -1);
            if (fields.length != 3) throw reject("CORRUPT_TABLE");
            out.add(new Corrupt(fields[0], fromHex(fields[1]), 1000 + index - 1, fields[2]));
        }
        return out;
    }

    private static Map<String, byte[]> readFixture(Path dir) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String name : FILES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) throw reject("FIXTURE_FILES");
            out.put(name, Files.readAllBytes(file));
        }
        return out;
    }

    // ------------------------------------------------------------- helpers

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String derivedMarker() {
        return "SYNTHQA16_" + sha256Hex(SEED.getBytes(StandardCharsets.UTF_8)).substring(0, 10);
    }

    private static boolean containsForbidden(String text) {
        if (FORBIDDEN.matcher(text).find()) return true;
        return text.contains(derivedMarker());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static byte[] fromHex(String text) {
        if ((text.length() & 1) != 0) throw reject("CORRUPT_TABLE");
        byte[] out = new byte[text.length() / 2];
        for (int index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return out;
    }

    private static String pinnedSnapshot(Map<String, byte[]> files) {
        Map<String, String> manifest = parseManifest(files.get("sha256.txt"));
        return manifest.get("snapshot");
    }

    private static int totalBytes(Map<String, byte[]> files) {
        int total = 0;
        for (byte[] value : files.values()) total += value.length;
        return total;
    }
}
