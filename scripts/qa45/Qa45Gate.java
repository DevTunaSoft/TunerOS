import com.devtunasoft.tuneros.core.SourceRecoveryPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QA-45 deterministic host gate: process-restart source restoration and stale
 * asynchronous import commit admission. Zero third-party dependencies.
 *
 * The gate calls the real pure-Kotlin production object SourceRecoveryPolicy;
 * it never reimplements the policy. Fixture bytes are generated from constants
 * and compared byte-for-byte on verify.
 */
public final class Qa45Gate {
    static final String SCHEMA = "qa45-recovery-epoch-v1";
    static final int RESTORE_CAP = 256;
    static final int COMMIT_CAP = 128;
    static final int CORRUPT_CAP = 8;

    static final String[] DATA_FILES = {
        "restore.tsv", "commit.tsv", "corrupt.tsv", "dimensions.tsv", "notes.txt"
    };
    static final String MANIFEST = "sha256.txt";

    /** Pinned digest of the concatenation of DATA_FILES in the order above. */
    static final String PINNED_DATA_DIGEST = "99f5489d43e3b90a5daf7db372afda304b60dc04718a9bded05715450aee7c65";

    static final String RESTORE_HEADER = "#qa45-restore-v1";
    static final String COMMIT_HEADER = "#qa45-commit-v1";
    static final String CORRUPT_HEADER = "#qa45-corrupt-v1";

    static int checks = 0;
    static int passed = 0;
    static int threadPeak = -1;
    static long heapUsed = -1L;

    static final class Fail extends RuntimeException {
        final String code;
        Fail(String code) {
            super(code);
            this.code = code;
        }
    }

    static final class RestoreRow {
        String id;
        String saved;
        String display;
        boolean loaded;
        boolean active;
        boolean expected;
    }

    static final class CommitRow {
        String id;
        boolean valid;
        int requestEpoch;
        int latestEpoch;
        boolean expected;
    }

    static final class CorruptRow {
        String name;
        String kind;
        String code;
        String payload;
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                throw new Fail("ARGS");
            }
            String mode = args[0];
            switch (mode) {
                case "generate":
                    require(args.length == 2, "ARGS");
                    generate(Path.of(args[1]));
                    System.out.println("MODE\tgenerate");
                    return;
                case "remanifest":
                    require(args.length == 2, "ARGS");
                    remanifest(Path.of(args[1]));
                    System.out.println("MODE\tremanifest");
                    return;
                case "scan":
                    require(args.length >= 2, "ARGS");
                    scan(args, 1);
                    return;
                case "selfleak":
                    require(args.length == 2, "ARGS");
                    writeLeakSample(Path.of(args[1]));
                    System.out.println("MODE\tselfleak");
                    return;
                case "verify":
                    require(args.length >= 2 && args.length <= 3, "ARGS");
                    boolean mutant = args.length == 3 && "--mutant".equals(args[2]);
                    verify(Path.of(args[1]), mutant);
                    return;
                default:
                    throw new Fail("ARGS");
            }
        } catch (Fail f) {
            System.out.println("CHECK\tFAIL\t" + f.code);
            System.out.println("FIRST\t" + f.code);
            System.out.println("SUMMARY\tchecks=" + checks + "\tpass=" + passed + "\tfail=1");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("CHECK\tFAIL\tHOST_EXCEPTION");
            System.out.println("FIRST\tHOST_EXCEPTION");
            System.out.println("SUMMARY\tchecks=" + checks + "\tpass=" + passed + "\tfail=1");
            System.exit(2);
        }
    }

    static void require(boolean condition, String code) {
        if (!condition) {
            throw new Fail(code);
        }
    }

    static void pass(String id) {
        checks++;
        passed++;
        System.out.println("CHECK\tPASS\t" + id);
    }

    // ---------------------------------------------------------------- fixture

    static String token(int index) {
        return String.format(Locale.ROOT, "S%05d", index);
    }

    static boolean expectedRestore(String saved, String display, boolean loaded, boolean active) {
        return !"N".equals(saved) && !"B".equals(saved) && saved.equals(display)
            && !loaded && !active;
    }

    static List<RestoreRow> buildRestores() {
        List<RestoreRow> rows = new ArrayList<>(RESTORE_CAP);
        for (int i = 0; i < RESTORE_CAP; i++) {
            int mode = i % 5;
            RestoreRow r = new RestoreRow();
            r.id = String.format(Locale.ROOT, "R%05d", i);
            r.saved = mode == 0 ? "N" : mode == 1 ? "B" : mode == 3 ? token(i + 1000) : token(i);
            r.display = mode == 4 ? "B" : token(i);
            r.loaded = ((i / 5) & 1) == 1;
            r.active = ((i / 10) & 1) == 1;
            r.expected = expectedRestore(r.saved, r.display, r.loaded, r.active);
            rows.add(r);
        }
        return rows;
    }

    static List<CommitRow> buildCommits() {
        List<CommitRow> rows = new ArrayList<>(COMMIT_CAP);
        for (int i = 0; i < COMMIT_CAP; i++) {
            CommitRow c = new CommitRow();
            c.id = String.format(Locale.ROOT, "C%05d", i);
            c.valid = i % 3 != 0;
            c.requestEpoch = i % 13;
            c.latestEpoch = i % 4 == 0 ? c.requestEpoch : (i * 5) % 17;
            c.expected = c.valid && c.requestEpoch == c.latestEpoch;
            rows.add(c);
        }
        return rows;
    }

    static List<CorruptRow> buildCorrupt() {
        List<CorruptRow> rows = new ArrayList<>(CORRUPT_CAP);
        rows.add(corrupt("bad-header", "restore", "HEADER_STRICT", "#wrong-header"));
        rows.add(corrupt("field-count", "restore", "FIELD_COUNT", "R00001~N~S00001~0"));
        rows.add(corrupt("bad-row-id", "restore", "BAD_ROW_ID", "X00001~N~S00001~0~0~0"));
        rows.add(corrupt("bad-source-token", "restore", "BAD_SOURCE_TOKEN", "R00001~Q~S00001~0~0~0"));
        rows.add(corrupt("bad-flag", "restore", "BAD_FLAG", "R00001~N~S00001~2~0~0"));
        rows.add(corrupt("bad-expectation", "restore", "BAD_EXPECTATION", "R00001~N~S00001~0~0~9"));
        rows.add(corrupt("bad-epoch", "commit", "BAD_EPOCH", "C00001~1~x~4~0"));
        rows.add(corrupt("commit-field-count", "commit", "FIELD_COUNT", "C00001~1~2"));
        return rows;
    }

    static CorruptRow corrupt(String name, String kind, String code, String payload) {
        CorruptRow c = new CorruptRow();
        c.name = name;
        c.kind = kind;
        c.code = code;
        c.payload = payload;
        return c;
    }

    static String renderRestore(List<RestoreRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(RESTORE_HEADER).append('\n');
        for (RestoreRow r : rows) {
            sb.append(r.id).append('\t').append(r.saved).append('\t').append(r.display)
                .append('\t').append(bit(r.loaded)).append('\t').append(bit(r.active))
                .append('\t').append(bit(r.expected)).append('\n');
        }
        return sb.toString();
    }

    static String renderCommit(List<CommitRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(COMMIT_HEADER).append('\n');
        for (CommitRow c : rows) {
            sb.append(c.id).append('\t').append(bit(c.valid)).append('\t')
                .append(c.requestEpoch).append('\t').append(c.latestEpoch).append('\t')
                .append(bit(c.expected)).append('\n');
        }
        return sb.toString();
    }

    static String renderCorrupt(List<CorruptRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(CORRUPT_HEADER).append('\n');
        for (CorruptRow c : rows) {
            sb.append(c.name).append('\t').append(c.kind).append('\t').append(c.code)
                .append('\t').append(c.payload).append('\n');
        }
        return sb.toString();
    }

    static String bit(boolean value) {
        return value ? "1" : "0";
    }

    static String renderDimensions(List<RestoreRow> restores, List<CommitRow> commits) {
        int rt = 0;
        int rf = 0;
        int ct = 0;
        int cf = 0;
        for (RestoreRow r : restores) {
            if (r.expected) rt++; else rf++;
        }
        for (CommitRow c : commits) {
            if (c.expected) ct++; else cf++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("schema\t").append(SCHEMA).append('\n');
        sb.append("restore_cap\t").append(RESTORE_CAP).append('\n');
        sb.append("commit_cap\t").append(COMMIT_CAP).append('\n');
        sb.append("corrupt_cap\t").append(CORRUPT_CAP).append('\n');
        sb.append("restore_true\t").append(rt).append('\n');
        sb.append("restore_false\t").append(rf).append('\n');
        sb.append("commit_true\t").append(ct).append('\n');
        sb.append("commit_false\t").append(cf).append('\n');
        return sb.toString();
    }

    static final String NOTES =
        "qa45 bounded synthetic fixture\n"
        + "all source identifiers are invented tokens\n"
        + "no provider material is present\n";

    static LinkedHashMap<String, byte[]> buildFiles() {
        List<RestoreRow> restores = buildRestores();
        List<CommitRow> commits = buildCommits();
        List<CorruptRow> corrupt = buildCorrupt();
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("restore.tsv", renderRestore(restores).getBytes(StandardCharsets.UTF_8));
        files.put("commit.tsv", renderCommit(commits).getBytes(StandardCharsets.UTF_8));
        files.put("corrupt.tsv", renderCorrupt(corrupt).getBytes(StandardCharsets.UTF_8));
        files.put("dimensions.tsv", renderDimensions(restores, commits).getBytes(StandardCharsets.UTF_8));
        files.put("notes.txt", NOTES.getBytes(StandardCharsets.UTF_8));
        files.put(MANIFEST, renderManifest(files).getBytes(StandardCharsets.UTF_8));
        return files;
    }

    static String renderManifest(Map<String, byte[]> files) {
        StringBuilder sb = new StringBuilder();
        for (String name : DATA_FILES) {
            sb.append(sha256Hex(files.get(name))).append("  ").append(name).append('\n');
        }
        return sb.toString();
    }

    static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> e : buildFiles().entrySet()) {
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
    }

    static void remanifest(Path dir) throws IOException {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        for (String name : DATA_FILES) {
            files.put(name, Files.readAllBytes(dir.resolve(name)));
        }
        Files.write(dir.resolve(MANIFEST), renderManifest(files).getBytes(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------- parser

    static List<RestoreRow> parseRestore(String text, boolean withHeader) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        if (withHeader) {
            require(lines.length > 0 && RESTORE_HEADER.equals(lines[0]), "HEADER_STRICT");
            start = 1;
        }
        List<RestoreRow> rows = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 6, "FIELD_COUNT");
            require(f[0].matches("R[0-9]{5}"), "BAD_ROW_ID");
            require(tokenOk(f[1]) && tokenOk(f[2]), "BAD_SOURCE_TOKEN");
            require(flagOk(f[3]) && flagOk(f[4]), "BAD_FLAG");
            require(flagOk(f[5]), "BAD_EXPECTATION");
            RestoreRow r = new RestoreRow();
            r.id = f[0];
            r.saved = f[1];
            r.display = f[2];
            r.loaded = "1".equals(f[3]);
            r.active = "1".equals(f[4]);
            r.expected = "1".equals(f[5]);
            rows.add(r);
        }
        return rows;
    }

    static boolean tokenOk(String value) {
        return "N".equals(value) || "B".equals(value) || value.matches("S[0-9]{5}");
    }

    static boolean flagOk(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    static List<CommitRow> parseCommit(String text, boolean withHeader) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        if (withHeader) {
            require(lines.length > 0 && COMMIT_HEADER.equals(lines[0]), "HEADER_STRICT");
            start = 1;
        }
        List<CommitRow> rows = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 5, "FIELD_COUNT");
            require(f[0].matches("C[0-9]{5}"), "BAD_ROW_ID");
            require(flagOk(f[1]), "BAD_FLAG");
            int request = parseEpoch(f[2]);
            int latest = parseEpoch(f[3]);
            require(flagOk(f[4]), "BAD_EXPECTATION");
            CommitRow c = new CommitRow();
            c.id = f[0];
            c.valid = "1".equals(f[1]);
            c.requestEpoch = request;
            c.latestEpoch = latest;
            c.expected = "1".equals(f[4]);
            rows.add(c);
        }
        return rows;
    }

    static int parseEpoch(String value) {
        require(value.matches("[0-9]{1,7}"), "BAD_EPOCH");
        int parsed = Integer.parseInt(value);
        require(parsed >= 0 && parsed <= 1000000, "BAD_EPOCH");
        return parsed;
    }

    static List<CorruptRow> parseCorrupt(String text) {
        String[] lines = text.split("\n", -1);
        require(lines.length > 0 && CORRUPT_HEADER.equals(lines[0]), "HEADER_STRICT");
        List<CorruptRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 4, "FIELD_COUNT");
            require(f[0].matches("[a-z0-9-]+"), "BAD_ROW_ID");
            require("restore".equals(f[1]) || "commit".equals(f[1]), "BAD_KIND");
            require(f[2].matches("[A-Z_]+"), "BAD_CODE");
            require(!f[3].isEmpty() && f[3].length() <= 120, "BAD_PAYLOAD");
            rows.add(corrupt(f[0], f[1], f[2], f[3]));
        }
        return rows;
    }

    static Map<String, String> parseDimensions(String text) {
        String[] lines = text.split("\n", -1);
        require(lines.length > 0 && ("schema\t" + SCHEMA).equals(lines[0]), "HEADER_STRICT");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 2 && !f[0].isEmpty() && !f[1].isEmpty(), "FIELD_COUNT");
            values.put(f[0], f[1]);
        }
        require(values.size() == 7, "FIELD_COUNT");
        return values;
    }

    static Map<String, String> parseManifest(String text) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("  ", -1);
            require(f.length == 2 && f[0].matches("[0-9a-f]{64}"), "MANIFEST_PARSE");
            values.put(f[1], f[0]);
        }
        require(values.size() == DATA_FILES.length, "MANIFEST_PARSE");
        return values;
    }

    // ----------------------------------------------------------------- verify

    static void verify(Path dir, boolean mutant) throws IOException {
        LinkedHashMap<String, byte[]> actual = new LinkedHashMap<>();
        for (String name : DATA_FILES) {
            actual.put(name, Files.readAllBytes(dir.resolve(name)));
        }
        actual.put(MANIFEST, Files.readAllBytes(dir.resolve(MANIFEST)));

        List<RestoreRow> restores = parseRestore(text(actual.get("restore.tsv")), true);
        List<CommitRow> commits = parseCommitInThread(text(actual.get("commit.tsv")));
        List<CorruptRow> corrupt = parseCorrupt(text(actual.get("corrupt.tsv")));
        Map<String, String> dims = parseDimensions(text(actual.get("dimensions.tsv")));

        for (CorruptRow c : corrupt) {
            boolean rejected = false;
            String observed = "NONE";
            try {
                dispatchCorrupt(c);
            } catch (Fail f) {
                rejected = true;
                observed = f.code;
            }
            require(rejected && c.code.equals(observed), "CORRUPT_REJECT");
        }
        pass("CORRUPT_REJECT");

        for (RestoreRow r : restores) {
            boolean got = SourceRecoveryPolicy.INSTANCE.shouldRestore(
                decode(r.saved), r.display, r.loaded, r.active);
            require(got == r.expected, "RESTORE_TRUTH_TABLE");
        }
        pass("RESTORE_TRUTH_TABLE");

        for (CommitRow c : commits) {
            boolean got = SourceRecoveryPolicy.INSTANCE.mayCommit(c.valid, c.requestEpoch, c.latestEpoch);
            require(got == c.expected, "COMMIT_EPOCH");
        }
        pass("COMMIT_EPOCH");

        require(restores.size() == RESTORE_CAP, "DIMENSION_CAP");
        require(commits.size() == COMMIT_CAP, "DIMENSION_CAP");
        require(corrupt.size() == CORRUPT_CAP, "DIMENSION_CAP");
        require(intValue(dims, "restore_cap") == RESTORE_CAP, "DIMENSION_CAP");
        require(intValue(dims, "commit_cap") == COMMIT_CAP, "DIMENSION_CAP");
        require(intValue(dims, "corrupt_cap") == CORRUPT_CAP, "DIMENSION_CAP");
        int rt = 0;
        int rf = 0;
        int ct = 0;
        int cf = 0;
        for (RestoreRow r : restores) {
            if (r.expected) rt++; else rf++;
        }
        for (CommitRow c : commits) {
            if (c.expected) ct++; else cf++;
        }
        require(intValue(dims, "restore_true") == rt, "DIMENSION_CAP");
        require(intValue(dims, "restore_false") == rf, "DIMENSION_CAP");
        require(intValue(dims, "commit_true") == ct, "DIMENSION_CAP");
        require(intValue(dims, "commit_false") == cf, "DIMENSION_CAP");
        require(rt > 0 && rf > 0 && ct > 0 && cf > 0, "DIMENSION_CAP");
        pass("DIMENSION_CAP");

        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        System.gc();
        try {
            Thread.sleep(30L);
        } catch (InterruptedException e) {
            throw new Fail("BOUNDED_RUNTIME");
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        heapUsed = used;
        require(maxHeap <= 96L * 1024L * 1024L, "BOUNDED_RUNTIME");
        require(used < 40L * 1024L * 1024L, "BOUNDED_RUNTIME");
        require(threadPeak > 0 && threadPeak <= 6, "BOUNDED_RUNTIME");
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            require(!(thread.isAlive() && thread.getName().startsWith("qa45-")), "BOUNDED_RUNTIME");
        }
        pass("BOUNDED_RUNTIME");

        StringBuilder diagnostics = new StringBuilder();
        for (String name : DATA_FILES) {
            diagnostics.append(text(actual.get(name)));
        }
        diagnostics.append(text(actual.get(MANIFEST)));
        assertClean(diagnostics.toString());
        pass("PRIVACY_SCAN");

        Map<String, String> manifest = parseManifest(text(actual.get(MANIFEST)));
        for (String name : DATA_FILES) {
            require(manifest.containsKey(name), "MANIFEST_CONSISTENCY");
            require(manifest.get(name).equals(sha256Hex(actual.get(name))), "MANIFEST_CONSISTENCY");
        }
        pass("MANIFEST_CONSISTENCY");

        if (!mutant) {
            LinkedHashMap<String, byte[]> regenerated = buildFiles();
            for (String name : regenerated.keySet()) {
                require(java.util.Arrays.equals(regenerated.get(name), actual.get(name)), "BYTE_IDENTITY");
            }
            pass("BYTE_IDENTITY");
            require(pinnedDigest(actual).equals(PINNED_DATA_DIGEST), "PINNED_DIGEST");
            pass("PINNED_DIGEST");
        }

        System.out.println("SUMMARY\tchecks=" + checks + "\tpass=" + passed
            + "\tfail=0\tmutant=" + mutant + "\theap_bytes=" + heapUsed
            + "\tthread_peak=" + threadPeak);
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String decode(String token) {
        return "N".equals(token) ? null : "B".equals(token) ? "" : token;
    }

    static int intValue(Map<String, String> values, String key) {
        String value = values.get(key);
        require(value != null && value.matches("[0-9]{1,7}"), "DIMENSION_CAP");
        return Integer.parseInt(value);
    }

    static void dispatchCorrupt(CorruptRow c) {
        String payload = c.payload.replace('~', '\t');
        boolean headerCase = payload.startsWith("#");
        if ("restore".equals(c.kind)) {
            parseRestore(payload, headerCase);
        } else {
            parseCommit(payload, headerCase);
        }
    }

    static List<CommitRow> parseCommitInThread(String text) {
        final AtomicReference<List<CommitRow>> result = new AtomicReference<>();
        final AtomicReference<Fail> failure = new AtomicReference<>();
        int before = Thread.activeCount();
        Thread worker = new Thread(() -> {
            try {
                result.set(parseCommit(text, true));
            } catch (Fail f) {
                failure.set(f);
            }
        }, "qa45-commit-parser");
        worker.setDaemon(true);
        worker.start();
        int peak = Thread.activeCount();
        threadPeak = Math.max(peak, before);
        try {
            worker.join(5000L);
        } catch (InterruptedException e) {
            throw new Fail("BOUNDED_RUNTIME");
        }
        require(!worker.isAlive(), "BOUNDED_RUNTIME");
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    // --------------------------------------------------------------- hashing

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new Fail("HOST_EXCEPTION");
        }
    }

    static String pinnedDigest(Map<String, byte[]> files) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Fail("HOST_EXCEPTION");
        }
        for (String name : DATA_FILES) {
            digest.update(files.get(name));
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- privacy

    static final String SECRET_SEED = "QA45-DERIVED-DIAGNOSTIC-TOKEN-SEED-V1";

    static String derivedSecret() {
        return sha256Hex((SECRET_SEED + "|" + SCHEMA).getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    static void assertClean(String text) {
        String[] patterns = {
            "(?i)https?://",
            "(?i)ftp://",
            "(?i)www\\.",
            "(?i)\\b(password|passwd|pwd|api[_-]?key|authorization|bearer|secret)[\\s:=]",
            "(?i)\\bpin[\\s:=]",
            "RECPAYLOAD-",
            "(?i)\\brec[0-9]{4,}\\b"
        };
        for (String expression : patterns) {
            require(!Pattern.compile(expression).matcher(text).find(), "PRIVACY_SCAN");
        }
        require(!text.contains(derivedSecret()), "PRIVACY_SCAN");
    }

    static void scan(String[] args, int from) throws IOException {
        for (int i = from; i < args.length; i++) {
            assertClean(text(Files.readAllBytes(Path.of(args[i]))));
        }
        System.out.println("SCAN\tPASS\tfiles=" + (args.length - from));
    }

    static void writeLeakSample(Path out) throws IOException {
        String sample = "synthetic diagnostic leak sample\n"
            + "url=https://example.invalid/no-real-provider\n"
            + "password=not-a-real-value\n"
            + "pin=0000\n"
            + "RECPAYLOAD-SYNTHETIC\n"
            + "derived=" + derivedSecret() + "\n";
        Files.write(out, sample.getBytes(StandardCharsets.UTF_8));
    }
}
