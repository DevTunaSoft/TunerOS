import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * QA-12: deterministic, zero-dependency host gate for crash / partial-write DVR recovery.
 *
 * Modes:
 *   generate <dir>                     write the pinned scratch fixtures
 *   verify <dir>                       regenerate, compare bytes, run positive checks and RED controls
 *   verify-mutant <dir>                as above, but skip byte-identity and pinned mirror checks
 *   mutate <src> <dst> <kind>          copy fixtures to scratch and apply one named mutation
 *
 * This gate reads only synthetic scratch fixtures. It does not use Android, Gradle, network or
 * third-party libraries. Derived marker values are computed at run time from a fixed seed and are
 * never written to source, fixtures, reports or stdout. Diagnostics carry ids, classes and hashes.
 *
 * Audit boundary: DVR-01/02 own live sink and catalog-policy decisions, QA-04 owns scheduled
 * recording durability, QA-10 owns event-log aggregation and QA-11 owns backup encode/decode.
 * QA-12 owns restart-time reconciliation of persisted media bytes against a catalog snapshot.
 */
public final class Qa12Gate {
    private static final String VERSION = "qa12-fixture-v1";
    private static final long SEED = 20_261_123L;
    private static final int ID_COUNT = 96;
    private static final int MAX_CATALOG_ROWS = 108;
    private static final int MAX_MEDIA_FILES = 84;
    private static final int MAX_MEDIA_BYTES = 4096;
    private static final int MAX_JOURNAL_BYTES = 8192;
    private static final int MAX_JOURNAL_ROWS = 96;
    private static final int CHUNK_BYTES = 512;
    private static final int CORRUPT_CASES = 8;
    private static final int EXPECTED_COMPLETED = 12;
    private static final int EXPECTED_PARTIAL = 24;
    private static final int EXPECTED_FAILED = 12;
    private static final int EXPECTED_MISSING = 12;
    private static final int EXPECTED_OVERSIZE = 12;
    private static final int EXPECTED_DUPLICATE = 12;
    private static final int EXPECTED_INVALID = 12;
    private static final int VALID_ROWS = 96;

    private static final String MIRROR_DIGEST = "a936ba53f39e002780bc070fd748420e991c59e8f4bbbae22f64c5f0c1e6a0d4";
    private static final String MIRROR_SOURCE_DIGEST = "1829373b02620c740a5a9bef867f90dac17cec8fb8284521df811aa7cdcc2191";
    private static final String MIRROR_SOURCE_RELATIVE =
        "app/src/androidTest/java/com/devtunasoft/tuneros/qa12/Qa12Assertions.kt";

    private static final String CONTRACT =
        "QA-12 synthetic scratch fixture for crash / partial-write DVR recovery.\n" +
        "All ids, bytes and report rows are synthetic; no real provider, credential or payload.\n" +
        "catalog.tsv is a crash snapshot; diagnostics.tsv is the bounded recovery journal.\n";

    private static final String[] DATA_FILES = {
        "contract.txt", "dimensions.tsv", "catalog.tsv", "corrupt.tsv", "diagnostics.tsv"
    };
    private static final String MANIFEST = "manifest.sha256";
    private static final String[] STATES = {"COMPLETED", "RECORDING", "PARTIAL", "FAILED", "CANCELLED"};
    private static final Pattern ID = Pattern.compile("rec[0-9]{5}");
    private static final Pattern CATALOG_PATH = Pattern.compile("media/rec[0-9]{5}\\.(ts|partial)");
    private static final Pattern CREDENTIAL_LIKE =
        Pattern.compile("(?i)(pass(word|wd)?|token|secret|api[_-]?key|authorization)\\s*[=:]");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private Qa12Gate() { }

    // ------------------------------------------------------------------ process entry

    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("generate")) {
                generate(Paths.get(args[1]));
            } else if (args.length == 1 && args[0].equals("mirror-digest")) {
                Fixtures fixtures = buildFixtures();
                Plan debugPlan = recover(fixtures.rows, fixtures.mediaSizes, MAX_MEDIA_BYTES);
                String artifact = mirrorArtifact(debugPlan, renderJournal(debugPlan));
                System.out.println("DIGEST\t" + sha256(bytes(artifact)));
                for (String row : artifact.split("\n", -1)) if (!row.isEmpty()) System.out.println("ARTIFACT\t" + row);
            } else if (args.length == 2 && (args[0].equals("verify") || args[0].equals("verify-mutant"))) {
                verify(Paths.get(args[1]), args[0].equals("verify-mutant"));
            } else if (args.length == 4 && args[0].equals("mutate")) {
                mutate(Paths.get(args[1]), Paths.get(args[2]), args[3]);
            } else {
                System.out.println("usage: Qa12Gate generate|verify|verify-mutant <dir> | mutate <src> <dst> <kind>");
                System.exit(2);
            }
        } catch (Throwable error) {
            emitFailure("harness-exception", error.getClass().getSimpleName());
            System.exit(3);
        }
    }

    private static void emitFailure(String code, String detail) {
        System.out.println("FIRST\t" + code);
        System.out.println("FAIL\t" + code + "\t" + detail);
        System.out.println("SUMMARY\tpositive=0/0\tnegative-red=0/0\ttotal=0\tresult=FAIL");
    }

    // ------------------------------------------------------------------ deterministic bytes

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error.getClass().getSimpleName());
        }
    }

    private static String id(int index) {
        return String.format(Locale.ROOT, "rec%05d", index);
    }

    private static byte[] pattern(int size, int salt) {
        byte[] out = new byte[size];
        byte[] mix = bytes(VERSION + "|" + SEED + "|qa12-media");
        for (int i = 0; i < size; i++) {
            out[i] = (byte) ((mix[i % mix.length] ^ (i * 31 + salt)) & 0xff);
        }
        return out;
    }

    private static String mark(String label) {
        return sha256(bytes(SEED + "|qa12-" + label + "|v1"));
    }

    private static String pinMarker() {
        long value = Long.parseLong(mark("pin").substring(0, 12), 16) % 100_000_000L;
        return String.format(Locale.ROOT, "%08d", value);
    }

    private static String payloadMarker() {
        return "QA12REC_" + mark("payload").substring(0, 8);
    }

    private static String urlScheme() {
        return new String(new char[] {'h', 't', 't', 'p', 's', ':', '/', '/'});
    }

    private static String credentialPrefix() {
        return new String(new char[] {'t', 'o', 'k', 'e', 'n', '='});
    }

    private static String urlMarker() {
        return urlScheme() + "qa12-" + mark("host").substring(0, 6) + ".invalid/stream?" +
            credentialPrefix() + pinMarker();
    }

    private static String credentialMarker() {
        return credentialPrefix() + pinMarker();
    }

    private static String secretMarker() {
        return "secret=" + mark("secret").substring(0, 12);
    }

    private static class Row {
        final String id;
        final String state;
        final long started;
        final long stopped;
        final long claimed;
        final String path;
        final int fields;

        Row(String id, String state, long started, long stopped, long claimed, String path, int fields) {
            this.id = id;
            this.state = state;
            this.started = started;
            this.stopped = stopped;
            this.claimed = claimed;
            this.path = path;
            this.fields = fields;
        }

        String render() {
            if (fields == 5) {
                return id + "\t" + state + "\t" + started + "\t" + stopped + "\t" + claimed + "\n";
            }
            return id + "\t" + state + "\t" + started + "\t" + stopped + "\t" + claimed + "\t" + path + "\n";
        }
    }

    private static class Case {
        final String name;
        final String raw;

        Case(String name, String raw) {
            this.name = name;
            this.raw = raw;
        }
    }

    private static class Fixtures {
        final Map<String, byte[]> files;
        final List<Row> rows;
        final List<Case> corrupt;
        final Map<String, Integer> mediaSizes;

        Fixtures(Map<String, byte[]> files, List<Row> rows, List<Case> corrupt, Map<String, Integer> mediaSizes) {
            this.files = files;
            this.rows = rows;
            this.corrupt = corrupt;
            this.mediaSizes = mediaSizes;
        }
    }

    private static Fixtures buildFixtures() {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        ArrayList<Row> rows = new ArrayList<Row>();
        LinkedHashMap<String, Integer> mediaSizes = new LinkedHashMap<String, Integer>();
        for (int i = 1; i <= ID_COUNT; i++) {
            String rec = id(i);
            long started = 1_700_000_000L + i * 13L;
            long stopped = started + 60L;
            int kind = i % 8;
            switch (kind) {
                case 0: {
                    int size = 64 + (i % 7) * 32;
                    String path = "media/" + rec + ".ts";
                    mediaSizes.put(path, size);
                    files.put(path, pattern(size, i));
                    rows.add(new Row(rec, "COMPLETED", started, stopped, size, path, 6));
                    break;
                }
                case 1: {
                    int size = 48 + (i % 5) * 16;
                    String path = "media/" + rec + ".partial";
                    mediaSizes.put(path, size);
                    files.put(path, pattern(size, i));
                    rows.add(new Row(rec, "RECORDING", started, stopped, size, path, 6));
                    break;
                }
                case 2: {
                    String path = "media/" + rec + ".partial";
                    mediaSizes.put(path, 0);
                    files.put(path, new byte[0]);
                    rows.add(new Row(rec, "RECORDING", started, stopped, 0, path, 6));
                    break;
                }
                case 3:
                    rows.add(new Row(rec, "COMPLETED", started, stopped, 128, "media/" + rec + ".ts", 6));
                    break;
                case 4: {
                    int size = 32 + i % 11;
                    String path = "media/" + rec + ".partial";
                    mediaSizes.put(path, size);
                    files.put(path, pattern(size, i));
                    rows.add(new Row(rec, "RECORDING", started, stopped, size + 17L, path, 6));
                    break;
                }
                case 5: {
                    String path = "media/" + rec + ".ts";
                    mediaSizes.put(path, MAX_MEDIA_BYTES + 1);
                    files.put(path, pattern(MAX_MEDIA_BYTES + 1, i));
                    rows.add(new Row(rec, "COMPLETED", started, stopped, MAX_MEDIA_BYTES + 1L, path, 6));
                    break;
                }
                case 6: {
                    String path = "media/" + rec + ".ts";
                    mediaSizes.put(path, 64);
                    files.put(path, pattern(64, i));
                    rows.add(new Row(rec, "COMPLETED", started, stopped, 64, path, 6));
                    rows.add(new Row(rec, "RECORDING", started, stopped, 0, "media/" + rec + ".partial", 6));
                    break;
                }
                default: {
                    String path = "media/" + rec + ".partial";
                    mediaSizes.put(path, 16);
                    files.put(path, pattern(16, i));
                    rows.add(new Row(rec, "PARTIAL", started, stopped, 16, "", 5));
                    break;
                }
            }
        }

        ArrayList<Case> corrupt = new ArrayList<Case>();
        corrupt.add(new Case("bad-field-count", "rec00001\tCOMPLETED\t1\t2\n"));
        corrupt.add(new Case("bad-id", "recX0001\tCOMPLETED\t1\t2\t1\tmedia/rec00001.ts\n"));
        corrupt.add(new Case("bad-state", "rec00001\tUNKNOWN\t1\t2\t1\tmedia/rec00001.ts\n"));
        corrupt.add(new Case("bad-number", "rec00001\tCOMPLETED\t1\t2\tnot-a-number\tmedia/rec00001.ts\n"));
        corrupt.add(new Case("absolute-path", "rec00001\tCOMPLETED\t1\t2\t1\tC:/scratch/rec00001.ts\n"));
        corrupt.add(new Case("path-id-mismatch", "rec00001\tCOMPLETED\t1\t2\t1\tmedia/rec00002.ts\n"));
        corrupt.add(new Case("negative-bytes", "rec00001\tCOMPLETED\t1\t2\t-1\tmedia/rec00001.ts\n"));
        corrupt.add(new Case("query-in-path", "rec00001\tCOMPLETED\t1\t2\t1\tmedia/rec00001.ts?x=1\n"));

        StringBuilder catalog = new StringBuilder();
        for (Row row : rows) catalog.append(row.render());
        StringBuilder corruptText = new StringBuilder("# corrupt catalog shapes; strict parser rejects each\n");
        for (Case item : corrupt) {
            corruptText.append("# name=").append(item.name).append("\n").append(item.raw).append("\n");
        }

        // Parse the rendered catalog back through the strict decoder: the crash snapshot on disk,
        // not the in-memory builder list, is the authority for recovery and for the pinned digest.
        List<Row> parsedRows = parseRows(catalog.toString());
        Plan plan = recover(parsedRows, mediaSizes, MAX_MEDIA_BYTES);
        String journal = renderJournal(plan);
        String artifact = mirrorArtifact(plan, journal);
        String dimensions = dimensions(sha256(bytes(artifact)));

        LinkedHashMap<String, byte[]> all = new LinkedHashMap<String, byte[]>();
        all.put(DATA_FILES[0], bytes(CONTRACT));
        all.put(DATA_FILES[1], bytes(dimensions));
        all.put(DATA_FILES[2], bytes(catalog.toString()));
        all.put(DATA_FILES[3], bytes(corruptText.toString()));
        all.put(DATA_FILES[4], bytes(journal));
        for (Map.Entry<String, byte[]> entry : files.entrySet()) all.put(entry.getKey(), entry.getValue());
        all.put(MANIFEST, bytes(renderManifest(all)));
        return new Fixtures(all, parsedRows, corrupt, mediaSizes);
    }

    private static String dimensions(String mirrorDigest) {
        StringBuilder out = new StringBuilder();
        line(out, "version", VERSION);
        line(out, "id_count", ID_COUNT);
        line(out, "max_catalog_rows", MAX_CATALOG_ROWS);
        line(out, "max_media_files", MAX_MEDIA_FILES);
        line(out, "max_media_bytes", MAX_MEDIA_BYTES);
        line(out, "max_journal_bytes", MAX_JOURNAL_BYTES);
        line(out, "max_journal_rows", MAX_JOURNAL_ROWS);
        line(out, "chunk_bytes", CHUNK_BYTES);
        line(out, "valid_rows", VALID_ROWS);
        line(out, "invalid_rows", EXPECTED_INVALID);
        line(out, "media_files", MAX_MEDIA_FILES);
        line(out, "complete", EXPECTED_COMPLETED);
        line(out, "partial", EXPECTED_PARTIAL);
        line(out, "failed", EXPECTED_FAILED);
        line(out, "missing", EXPECTED_MISSING);
        line(out, "oversize", EXPECTED_OVERSIZE);
        line(out, "duplicate", EXPECTED_DUPLICATE);
        line(out, "journal_rows", MAX_JOURNAL_ROWS);
        line(out, "mirror_digest", mirrorDigest);
        return out.toString();
    }

    private static void line(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static String renderManifest(Map<String, byte[]> files) {
        TreeMap<String, byte[]> sorted = new TreeMap<String, byte[]>(files);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
            if (entry.getKey().equals(MANIFEST)) continue;
            out.append(sha256(entry.getValue())).append("  ").append(entry.getKey()).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ strict parsing

    private static String rejectRow(String raw) {
        String[] fields = raw.split("\t", -1);
        if (fields.length != 6) return "CATALOG_FIELDS";
        if (!ID.matcher(fields[0]).matches()) return "CATALOG_ID";
        boolean stateOk = false;
        for (String state : STATES) if (state.equals(fields[1])) stateOk = true;
        if (!stateOk) return "CATALOG_STATE";
        Long started = parseLong(fields[2]);
        Long stopped = parseLong(fields[3]);
        Long claimed = parseLong(fields[4]);
        if (started == null || stopped == null || claimed == null) return "CATALOG_NUMBER";
        if (started < 0L || stopped < started || claimed < 0L || claimed > 1_000_000_000L) return "CATALOG_RANGE";
        if (!CATALOG_PATH.matcher(fields[5]).matches()) return "CATALOG_PATH";
        if (!fields[5].contains("/" + fields[0] + ".")) return "CATALOG_PATH_ID";
        return null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty() || value.length() > 12) return null;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static List<Row> parseRows(String catalog) {
        ArrayList<Row> rows = new ArrayList<Row>();
        for (String raw : catalog.split("\n", -1)) {
            if (raw.isEmpty()) continue;
            if (rejectRow(raw) != null) continue;
            String[] f = raw.split("\t", -1);
            rows.add(new Row(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]),
                Long.parseLong(f[4]), f[5], 6));
        }
        return rows;
    }

    private static Map<String, String> parseDimensions(String data) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (String raw : data.split("\n", -1)) {
            if (raw.isEmpty()) continue;
            int at = raw.indexOf('=');
            if (at < 1 || out.containsKey(raw.substring(0, at))) return Collections.emptyMap();
            out.put(raw.substring(0, at), raw.substring(at + 1));
        }
        return out;
    }

    private static List<Case> parseCorrupt(String data) {
        ArrayList<Case> out = new ArrayList<Case>();
        String name = "";
        for (String raw : data.split("\n", -1)) {
            if (raw.isEmpty()) continue;
            if (raw.startsWith("# name=")) {
                name = raw.substring(7);
            } else if (!raw.startsWith("#")) {
                out.add(new Case(name, raw));
                name = "";
            }
        }
        return out;
    }

    private static Map<String, String> parseManifest(String data, boolean checkFormat) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (String raw : data.split("\n", -1)) {
            if (raw.isEmpty()) continue;
            if (checkFormat && (!raw.matches("^[0-9a-f]{64}  \\S+$") || out.containsKey(raw.substring(66)))) {
                return Collections.emptyMap();
            }
            if (raw.length() < 67) return Collections.emptyMap();
            out.put(raw.substring(66), raw.substring(0, 64));
        }
        return out;
    }

    // ------------------------------------------------------------------ recovery model

    private static class Outcome {
        final String id;
        final String classification;
        final long bytes;
        final String action;

        Outcome(String id, String classification, long bytes, String action) {
            this.id = id;
            this.classification = classification;
            this.bytes = bytes;
            this.action = action;
        }

        String render() {
            return id + "\t" + classification + "\t" + bytes + "\t" + action + "\n";
        }
    }

    private static class Plan {
        final LinkedHashMap<String, Outcome> outcomes;
        final Map<String, List<Row>> grouped;
        final Map<String, Integer> media;
        final int invalidRows;

        Plan(LinkedHashMap<String, Outcome> outcomes, Map<String, List<Row>> grouped,
             Map<String, Integer> media, int invalidRows) {
            this.outcomes = outcomes;
            this.grouped = grouped;
            this.media = media;
            this.invalidRows = invalidRows;
        }

        int count(String classification) {
            int n = 0;
            for (Outcome outcome : outcomes.values()) if (outcome.classification.equals(classification)) n++;
            return n;
        }
    }

    /** Fail-closed restart reconciliation. It only classifies and plans; it never promotes a file. */
    private static Plan recover(List<Row> rows, Map<String, Integer> mediaSizes, long maxBytes) {
        Map<String, List<Row>> grouped = new LinkedHashMap<String, List<Row>>();
        for (Row row : rows) {
            List<Row> group = grouped.get(row.id);
            if (group == null) {
                group = new ArrayList<Row>();
                grouped.put(row.id, group);
            }
            group.add(row);
        }
        Set<String> invalidIds = new LinkedHashSet<String>();
        for (int i = 1; i <= ID_COUNT; i++) {
            String rec = id(i);
            if (i % 8 == 7 && !grouped.containsKey(rec)) invalidIds.add(rec);
        }

        LinkedHashMap<String, Outcome> outcomes = new LinkedHashMap<String, Outcome>();
        for (int i = 1; i <= ID_COUNT; i++) {
            String rec = id(i);
            Integer ts = mediaSizes.get("media/" + rec + ".ts");
            Integer part = mediaSizes.get("media/" + rec + ".partial");
            long actual = ts != null ? ts : (part != null ? part : -1L);
            if (invalidIds.contains(rec)) {
                outcomes.put(rec, new Outcome(rec, "INVALID_ROW", Math.max(0L, actual), "AWAIT_STRICT_ROW"));
                continue;
            }
            List<Row> group = grouped.get(rec);
            if (group == null) {
                outcomes.put(rec, new Outcome(rec, "INVALID_ROW", Math.max(0L, actual), "AWAIT_STRICT_ROW"));
                continue;
            }
            if (group.size() > 1) {
                outcomes.put(rec, new Outcome(rec, "DUPLICATE", Math.max(0L, actual), "DEDUP_REJECT"));
                continue;
            }
            Row row = group.get(0);
            if (!row.path.startsWith("media/" + rec + ".")) {
                outcomes.put(rec, new Outcome(rec, "INVALID_ROW", Math.max(0L, actual), "AWAIT_STRICT_ROW"));
                continue;
            }
            boolean isCompleted = row.state.equals("COMPLETED");
            if (actual < 0L) {
                outcomes.put(rec, new Outcome(rec, "MISSING", 0L, "NO_MEDIA"));
                continue;
            }
            if (actual == 0L) {
                outcomes.put(rec, new Outcome(rec, "FAILED", 0L, "DISCARD_EMPTY"));
                continue;
            }
            if (actual > maxBytes) {
                outcomes.put(rec, new Outcome(rec, "OVERSIZE", actual, "QUARANTINE_OVERSIZE"));
                continue;
            }
            if (!isCompleted) {
                if (row.state.equals("FAILED") || row.state.equals("CANCELLED")) {
                    outcomes.put(rec, new Outcome(rec, "FAILED", actual, "DISCARD_EMPTY"));
                } else if (row.claimed != actual) {
                    outcomes.put(rec, new Outcome(rec, "PARTIAL", actual, "RETAIN_PARTIAL_MIDWRITE"));
                } else {
                    outcomes.put(rec, new Outcome(rec, "PARTIAL", actual, "RETAIN_PARTIAL"));
                }
                continue;
            }
            if (ts == null || row.claimed != actual) {
                outcomes.put(rec, new Outcome(rec, "PARTIAL", actual, "RETAIN_PARTIAL_MIDWRITE"));
                continue;
            }
            if (part != null && part > 0) {
                outcomes.put(rec, new Outcome(rec, "PARTIAL", actual, "RETAIN_PARTIAL_SIBLING"));
                continue;
            }
            outcomes.put(rec, new Outcome(rec, "COMPLETED", actual, "FINALIZE_ONCE"));
        }
        return new Plan(outcomes, grouped, mediaSizes, invalidIds.size());
    }

    private static String renderJournal(Plan plan) {
        StringBuilder out = new StringBuilder();
        for (Outcome outcome : plan.outcomes.values()) out.append(outcome.render());
        return out.toString();
    }

    private static String mirrorArtifact(Plan plan, String journal) {
        StringBuilder out = new StringBuilder();
        out.append("qa12-mirror-v1\n");
        line(out, "rows", plan.outcomes.size());
        line(out, "valid", VALID_ROWS);
        line(out, "invalid", plan.invalidRows);
        line(out, "media", plan.media.size());
        line(out, "complete", plan.count("COMPLETED"));
        line(out, "partial", plan.count("PARTIAL"));
        line(out, "failed", plan.count("FAILED"));
        line(out, "missing", plan.count("MISSING"));
        line(out, "oversize", plan.count("OVERSIZE"));
        line(out, "duplicate", plan.count("DUPLICATE"));
        line(out, "invalid_row", plan.count("INVALID_ROW"));
        line(out, "journal_bytes", bytes(journal).length);
        line(out, "journal_rows", plan.outcomes.size());
        return out.toString();
    }

    private static class WriteResult {
        final boolean bounded;
        final int rows;
        final int bytes;
        final int maxChunk;

        WriteResult(boolean bounded, int rows, int bytes, int maxChunk) {
            this.bounded = bounded;
            this.rows = rows;
            this.bytes = bytes;
            this.maxChunk = maxChunk;
        }
    }

    /** Streams one small row at a time to disk; caps are checked before every write. */
    private static WriteResult writeJournal(Path target, String journal, int byteCap, int rowCap, int chunk)
        throws IOException {
        int written = 0;
        int rows = 0;
        int maxChunk = 0;
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (String row : journal.split("\n", -1)) {
                if (row.isEmpty()) continue;
                byte[] line = bytes(row + "\n");
                if (rows >= rowCap || written + line.length > byteCap) {
                    return new WriteResult(false, rows, written, maxChunk);
                }
                int offset = 0;
                while (offset < line.length) {
                    int length = Math.min(chunk, line.length - offset);
                    out.write(line, offset, length);
                    maxChunk = Math.max(maxChunk, length);
                    offset += length;
                    written += length;
                }
                rows++;
            }
            out.flush();
        }
        return new WriteResult(true, rows, written, maxChunk);
    }

    // ------------------------------------------------------------------ detectors

    private static Set<String> leakKinds(String report, String pin, String payload) {
        LinkedHashSet<String> kinds = new LinkedHashSet<String>();
        if (report.contains("://")) kinds.add("URL");
        if (CREDENTIAL_LIKE.matcher(report).find()) kinds.add("CREDENTIAL");
        if (report.contains(pin)) kinds.add("PIN");
        if (report.toLowerCase(Locale.ROOT).contains("secret")) kinds.add("SECRET");
        if (report.contains(payload)) kinds.add("PAYLOAD");
        return kinds;
    }

    private static boolean lenientEmptyCompletion(List<Row> rows, Map<String, Integer> media) {
        for (Row row : rows) {
            if (!row.state.equals("COMPLETED")) continue;
            Integer size = media.get(row.path);
            if (size != null && size == 0) return true;
        }
        return false;
    }

    private static boolean lenientDuplicateFinalization(List<Row> rows, Map<String, Integer> media) {
        Map<String, Integer> completed = new HashMap<String, Integer>();
        for (Row row : rows) {
            if (!row.state.equals("COMPLETED")) continue;
            Integer count = completed.get(row.id);
            completed.put(row.id, count == null ? 1 : count + 1);
        }
        for (Map.Entry<String, Integer> entry : completed.entrySet()) {
            if (entry.getValue() > 1 && media.containsKey("media/" + entry.getKey() + ".ts")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ checks

    private static class Check {
        final String id;
        final boolean positive;
        final boolean ok;
        final String detail;

        Check(String id, boolean positive, boolean ok, String detail) {
            this.id = id;
            this.positive = positive;
            this.ok = ok;
            this.detail = detail;
        }
    }

    private static void generate(Path dir) throws IOException {
        Map<String, byte[]> files = buildFixtures().files;
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = dir.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        System.out.println("GENERATED\tfiles=" + files.size() + "\tbytes=" + totalBytes(files));
        System.out.println("DERIVED\t" + derivedText(buildFixtures()));
    }

    private static int totalBytes(Map<String, byte[]> files) {
        int total = 0;
        for (byte[] value : files.values()) total += value.length;
        return total;
    }

    private static String derivedText(Fixtures fixtures) {
        Plan plan = recover(fixtures.rows, fixtures.mediaSizes, MAX_MEDIA_BYTES);
        return "rows=" + fixtures.rows.size() + "\tmedia=" + fixtures.mediaSizes.size() +
            "\tcomplete=" + plan.count("COMPLETED") + "\tpartial=" + plan.count("PARTIAL") +
            "\tfailed=" + plan.count("FAILED") + "\tmissing=" + plan.count("MISSING") +
            "\toversize=" + plan.count("OVERSIZE") + "\tduplicate=" + plan.count("DUPLICATE") +
            "\tinvalid=" + plan.count("INVALID_ROW");
    }

    private static void verify(Path dir, boolean mutantMode) throws IOException {
        Map<String, byte[]> committed = readAll(dir);
        ArrayList<Check> positives = new ArrayList<Check>();
        ArrayList<Check> negatives = new ArrayList<Check>();
        String first = null;

        byte[] manifestBytes = committed.get(MANIFEST);
        Map<String, String> manifest = manifestBytes == null
            ? Collections.<String, String>emptyMap() : parseManifest(text(manifestBytes), true);
        if (mutantMode) {
            boolean consistent = !manifest.isEmpty() && manifest.size() == committed.size() - 1;
            for (Map.Entry<String, byte[]> entry : committed.entrySet()) {
                if (entry.getKey().equals(MANIFEST)) continue;
                String digest = manifest.get(entry.getKey());
                if (digest == null || !digest.equals(sha256(entry.getValue()))) consistent = false;
            }
            if (!consistent && first == null) first = "manifest-consistency";
            positives.add(new Check("manifest-consistency", true, consistent, "files=" + committed.size()));
        } else {
            Map<String, byte[]> regenerated = buildFixtures().files;
            boolean identical = regenerated.size() == committed.size();
            for (Map.Entry<String, byte[]> entry : regenerated.entrySet()) {
                byte[] actual = committed.get(entry.getKey());
                if (actual == null || !Arrays.equals(actual, entry.getValue())) identical = false;
            }
            if (!identical && first == null) first = "fixture-byte-identical";
            positives.add(new Check("fixture-byte-identical", true, identical, "files=" + committed.size()));

            boolean manifestPinned = manifestBytes != null &&
                Arrays.equals(manifestBytes, regenerated.get(MANIFEST));
            if (!manifestPinned && first == null) first = "manifest-pinned";
            positives.add(new Check("manifest-pinned", true, manifestPinned, "entries=" + manifest.size()));

            byte[] source = readIfExists(dir.getParent().getParent().getParent().resolve(MIRROR_SOURCE_RELATIVE));
            boolean sourcePinned = source != null && sha256(source).equals(MIRROR_SOURCE_DIGEST);
            if (!sourcePinned && first == null) first = "mirror-source-digest";
            positives.add(new Check("mirror-source-digest", true, sourcePinned, "sha256=pinned"));
        }

        byte[] dimensionsBytes = committed.get("dimensions.tsv");
        Map<String, String> dims = dimensionsBytes == null
            ? Collections.<String, String>emptyMap() : parseDimensions(text(dimensionsBytes));
        boolean dimsOk = dims.size() >= 18 && VERSION.equals(dims.get("version")) &&
            "1".equals(dims.get("id_count")) == false && "96".equals(dims.get("id_count")) &&
            intValue(dims, "max_catalog_rows") == MAX_CATALOG_ROWS &&
            intValue(dims, "max_media_files") == MAX_MEDIA_FILES &&
            intValue(dims, "max_media_bytes") == MAX_MEDIA_BYTES &&
            intValue(dims, "max_journal_rows") == MAX_JOURNAL_ROWS &&
            intValue(dims, "chunk_bytes") == CHUNK_BYTES;
        if (!dimsOk && first == null) first = "dimensions-strict";
        positives.add(new Check("dimensions-strict", true, dimsOk, "keys=" + dims.size()));

        if (!mutantMode) {
            boolean mirrorPinned = MIRROR_DIGEST.equals(dims.get("mirror_digest")) &&
                HEX64.matcher(String.valueOf(dims.get("mirror_digest"))).matches();
            if (!mirrorPinned && first == null) first = "mirror-digest";
            positives.add(new Check("mirror-digest", true, mirrorPinned, "sha256=pinned"));
        }

        byte[] catalogBytes = committed.get("catalog.tsv");
        List<Row> rows = catalogBytes == null ? Collections.<Row>emptyList() : parseRows(text(catalogBytes));
        int physicalRows = 0;
        if (catalogBytes != null) {
            for (String row : text(catalogBytes).split("\n", -1)) if (!row.isEmpty()) physicalRows++;
        }
        boolean rowsOk = physicalRows == MAX_CATALOG_ROWS && physicalRows <= intValue(dims, "max_catalog_rows") &&
            rows.size() == VALID_ROWS;
        if (!rowsOk && first == null) first = "catalog-row-count";
        positives.add(new Check("catalog-row-count", true, rowsOk,
            "rows=" + physicalRows + "\tvalid=" + rows.size()));

        byte[] corruptBytes = committed.get("corrupt.tsv");
        List<Case> corrupt = corruptBytes == null ? Collections.<Case>emptyList() : parseCorrupt(text(corruptBytes));
        int rejected = 0;
        for (Case item : corrupt) if (rejectRow(item.raw) != null) rejected++;
        boolean corruptOk = corrupt.size() == CORRUPT_CASES && rejected == CORRUPT_CASES;
        if (!corruptOk && first == null) first = "corrupt-shapes-strict";
        positives.add(new Check("corrupt-shapes-strict", true, corruptOk, "rejected=" + rejected));

        Map<String, Integer> media = readMediaSizes(dir, mediaNames(dims));
        boolean mediaOk;
        if (mutantMode) {
            // Mutant mode tolerates one added/removed media file so a structural mutation cannot
            // mask the named semantic rejection; GREEN mode still requires the exact pinned count.
            mediaOk = media.size() >= MAX_MEDIA_FILES - 1 && media.size() <= MAX_MEDIA_FILES + 1;
        } else {
            mediaOk = media.size() == MAX_MEDIA_FILES && media.size() <= intValue(dims, "max_media_files");
        }
        for (Map.Entry<String, Integer> entry : media.entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() > intValue(dims, "max_media_bytes") + 1) mediaOk = false;
        }
        if (!mediaOk && first == null) first = "media-file-bounds";
        positives.add(new Check("media-file-bounds", true, mediaOk, "files=" + media.size()));

        Plan plan = recover(rows, media, intValue(dims, "max_media_bytes"));
        boolean emptyOk = true;
        for (Outcome outcome : plan.outcomes.values()) {
            if (outcome.classification.equals("COMPLETED") && outcome.bytes == 0L) emptyOk = false;
        }
        if (!emptyOk && first == null) first = "no-empty-completion";
        positives.add(new Check("no-empty-completion", true, emptyOk, "complete=" + plan.count("COMPLETED")));

        boolean dupOk = plan.count("DUPLICATE") == EXPECTED_DUPLICATE;
        Set<String> journalIds = new LinkedHashSet<String>();
        for (Outcome outcome : plan.outcomes.values()) {
            if (!journalIds.add(outcome.id)) dupOk = false;
            List<Row> group = plan.grouped.get(outcome.id);
            if (group != null && group.size() > 1 && outcome.classification.equals("COMPLETED")) dupOk = false;
        }
        if (!dupOk && first == null) first = "no-duplicate-finalization";
        positives.add(new Check("no-duplicate-finalization", true, dupOk, "duplicate=" + plan.count("DUPLICATE")));

        boolean missingOk = plan.count("MISSING") == EXPECTED_MISSING;
        for (Outcome outcome : plan.outcomes.values()) {
            if (outcome.classification.equals("MISSING") && outcome.classification.equals("COMPLETED")) missingOk = false;
            if (plan.grouped.containsKey(outcome.id)) {
                Row row = plan.grouped.get(outcome.id).get(0);
                if (!media.containsKey(row.path) && outcome.classification.equals("COMPLETED")) missingOk = false;
            }
        }
        if (!missingOk && first == null) first = "missing-media-no-completion";
        positives.add(new Check("missing-media-no-completion", true, missingOk, "missing=" + plan.count("MISSING")));

        boolean partialOk = plan.count("PARTIAL") == EXPECTED_PARTIAL;
        for (Outcome outcome : plan.outcomes.values()) {
            if (outcome.classification.equals("PARTIAL") && outcome.classification.equals("COMPLETED")) partialOk = false;
        }
        if (!partialOk && first == null) first = "partial-no-completion";
        positives.add(new Check("partial-no-completion", true, partialOk, "partial=" + plan.count("PARTIAL")));

        boolean oversizeOk = plan.count("OVERSIZE") == EXPECTED_OVERSIZE;
        for (Outcome outcome : plan.outcomes.values()) {
            if (outcome.bytes > intValue(dims, "max_media_bytes") &&
                outcome.classification.equals("COMPLETED")) oversizeOk = false;
        }
        if (!oversizeOk && first == null) first = "oversize-rejected";
        positives.add(new Check("oversize-rejected", true, oversizeOk, "oversize=" + plan.count("OVERSIZE")));

        boolean invalidOk = plan.invalidRows == EXPECTED_INVALID &&
            plan.count("INVALID_ROW") == EXPECTED_INVALID;
        for (Outcome outcome : plan.outcomes.values()) {
            if (outcome.classification.equals("INVALID_ROW") &&
                outcome.classification.equals("COMPLETED")) invalidOk = false;
        }
        if (!invalidOk && first == null) first = "invalid-rows-fail-closed";
        positives.add(new Check("invalid-rows-fail-closed", true, invalidOk, "invalid=" + plan.invalidRows));

        Set<String> covered = new LinkedHashSet<String>();
        for (Outcome outcome : plan.outcomes.values()) {
            covered.add("media/" + outcome.id + ".ts");
            covered.add("media/" + outcome.id + ".partial");
        }
        boolean orphanOk = true;
        for (String path : media.keySet()) if (!covered.contains(path)) orphanOk = false;
        if (!orphanOk && first == null) first = "no-orphan-unhandled";
        positives.add(new Check("no-orphan-unhandled", true, orphanOk, "media=" + media.size()));

        boolean cleanOk = true;
        for (Outcome outcome : plan.outcomes.values()) {
            if (outcome.action.equals("FINALIZE_ONCE")) {
                if (outcome.bytes <= 0L || outcome.bytes > intValue(dims, "max_media_bytes")) cleanOk = false;
                if (media.containsKey("media/" + outcome.id + ".partial")) cleanOk = false;
            }
            if (!outcome.action.equals("FINALIZE_ONCE") && outcome.action.equals("DISCARD_EMPTY") &&
                !outcome.classification.equals("FAILED")) cleanOk = false;
            if (!outcome.action.equals("FINALIZE_ONCE") && !outcome.action.equals("RETAIN_PARTIAL") &&
                !outcome.action.equals("RETAIN_PARTIAL_MIDWRITE") &&
                !outcome.action.equals("RETAIN_PARTIAL_SIBLING") &&
                !outcome.action.equals("DISCARD_EMPTY") && !outcome.action.equals("NO_MEDIA") &&
                !outcome.action.equals("QUARANTINE_OVERSIZE") && !outcome.action.equals("DEDUP_REJECT") &&
                !outcome.action.equals("AWAIT_STRICT_ROW")) cleanOk = false;
        }
        if (!cleanOk && first == null) first = "recovery-plan-clean";
        positives.add(new Check("recovery-plan-clean", true, cleanOk, "actions=" + plan.outcomes.size()));

        String journal = renderJournal(plan);
        Path scratch = Paths.get(System.getProperty("java.io.tmpdir", "."), "qa12-run");
        Path journalPath = scratch.resolve("journal.tsv");
        WriteResult write;
        try {
            write = writeJournal(journalPath, journal, intValue(dims, "max_journal_bytes"),
                intValue(dims, "max_journal_rows"), intValue(dims, "chunk_bytes"));
        } catch (IOException error) {
            write = new WriteResult(false, 0, 0, 0);
        }
        byte[] onDisk = readIfExists(journalPath);
        boolean writeOk = write.bounded && write.rows == plan.outcomes.size() &&
            write.bytes <= intValue(dims, "max_journal_bytes") && write.maxChunk <= intValue(dims, "chunk_bytes") &&
            onDisk != null && onDisk.length == write.bytes;
        if (!writeOk && first == null) first = "journal-bounded";
        positives.add(new Check("journal-bounded", true, writeOk,
            "rows=" + write.rows + "\tbytes=" + write.bytes + "\tchunk=" + write.maxChunk));

        byte[] golden = committed.get("diagnostics.tsv");
        String pin = pinMarker();
        String payload = payloadMarker();
        String reportText = golden == null ? "" : text(golden);
        Set<String> leaks = leakKinds(reportText, pin, payload);
        boolean cleanReport = leaks.isEmpty();
        if (!cleanReport && first == null) first = "diagnostics-secret-free";
        positives.add(new Check("diagnostics-secret-free", true, cleanReport, "leak-kinds=" + leaks.size()));

        boolean laxEmpty = !lenientEmptyCompletion(rows, media);
        if (!laxEmpty && first == null) first = "red-lenient-empty-accepted";
        negatives.add(new Check("red-lenient-empty-accepted", false, laxEmpty, "lax-accept=" + (laxEmpty ? 0 : 1)));

        boolean laxDuplicate = !lenientDuplicateFinalization(rows, media);
        if (!laxDuplicate && first == null) first = "red-duplicate-lax-finalization";
        negatives.add(new Check("red-duplicate-lax-finalization", false, laxDuplicate,
            "lax-accept=" + (laxDuplicate ? 0 : 1)));

        String base = reportText + "\nqa12-diagnostic-summary\n";
        String[][] injections = {
            {"URL", urlMarker()}, {"CREDENTIAL", credentialMarker()}, {"PIN", pin},
            {"SECRET", secretMarker()}, {"PAYLOAD", payload},
        };
        for (String[] injection : injections) {
            boolean detectorRejected = leakKinds(base + "x=" + injection[1], pin, payload).contains(injection[0]);
            String checkId = "red-leak-" + injection[0].toLowerCase(Locale.ROOT) + "-accepted";
            if (!detectorRejected && first == null) first = checkId;
            negatives.add(new Check(checkId, false, detectorRejected, "injected=" + injection[0]));
        }

        boolean journalMatch = golden != null && Arrays.equals(golden, onDisk == null ? new byte[0] : onDisk);
        if (!journalMatch && first == null) first = "journal-byte-identical";
        positives.add(new Check("journal-byte-identical", true, journalMatch, "bytes=" + write.bytes));

        boolean completedCountOk = plan.count("COMPLETED") == EXPECTED_COMPLETED;
        if (!completedCountOk && first == null) first = "completed-count";
        positives.add(new Check("completed-count", true, completedCountOk,
            "complete=" + plan.count("COMPLETED")));

        int positivePass = 0;
        for (Check check : positives) if (check.ok) positivePass++;
        int negativeRed = 0;
        for (Check check : negatives) if (check.ok) negativeRed++;
        if (first == null && (positivePass != positives.size() || negativeRed != negatives.size())) {
            first = "summary-mismatch";
        }
        boolean pass = first == null;
        if (first != null) System.out.println("FIRST\t" + first);
        for (Check check : positives) {
            System.out.println("CHECK\t" + (check.ok ? "PASS" : "FAIL") + "\t" + check.id + "\t" + check.detail);
        }
        for (Check check : negatives) {
            System.out.println("CHECK\t" + (check.ok ? "PASS" : "FAIL") + "\t" + check.id + "\t" + check.detail);
        }
        System.out.println("DERIVED\t" + derivedText(new Fixtures(
            committed, rows, corrupt, media)));
        System.out.println("SUMMARY\tpositive=" + positivePass + "/" + positives.size() +
            "\tnegative-red=" + negativeRed + "/" + negatives.size() +
            "\ttotal=" + (positivePass + negativeRed) + "/" + (positives.size() + negatives.size()) +
            "\tresult=" + (pass ? "PASS" : "FAIL"));
        if (!pass) System.exit(1);
    }

    private static int intValue(Map<String, String> dims, String key) {
        Long parsed = parseLong(dims.get(key));
        return parsed == null || parsed > Integer.MAX_VALUE ? -1 : parsed.intValue();
    }

    private static List<String> mediaNames(Map<String, String> dims) {
        ArrayList<String> names = new ArrayList<String>();
        for (Map.Entry<String, byte[]> entry : buildFixtures().files.entrySet()) {
            if (entry.getKey().startsWith("media/")) names.add(entry.getKey());
        }
        return names;
    }

    private static Map<String, Integer> readMediaSizes(Path dir, List<String> names) throws IOException {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String name : scanMedia(dir)) {
            Path path = dir.resolve(name);
            long size = Files.size(path);
            if (size > Integer.MAX_VALUE) continue;
            out.put(name, (int) size);
        }
        return out;
    }

    private static List<String> scanMedia(Path dir) throws IOException {
        ArrayList<String> names = new ArrayList<String>();
        Path mediaDir = dir.resolve("media");
        if (!Files.isDirectory(mediaDir)) return names;
        try (java.util.stream.Stream<Path> entries = Files.list(mediaDir)) {
            entries.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.matches("rec[0-9]{5}\\.(ts|partial)")) names.add("media/" + name);
            });
        }
        Collections.sort(names);
        if (names.size() > MAX_MEDIA_FILES + 8) {
            throw new IllegalStateException("media-file-count-unbounded");
        }
        return names;
    }

    private static byte[] readIfExists(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        } catch (IOException error) {
            return null;
        }
    }

    private static Map<String, byte[]> readAll(Path dir) throws IOException {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        if (!Files.isDirectory(dir)) return out;
        for (String name : DATA_FILES) {
            byte[] value = readIfExists(dir.resolve(name));
            if (value != null) out.put(name, value);
        }
        byte[] manifestValue = readIfExists(dir.resolve(MANIFEST));
        if (manifestValue != null) out.put(MANIFEST, manifestValue);
        for (String name : scanMedia(dir)) {
            byte[] value = readIfExists(dir.resolve(name));
            if (value != null) out.put(name, value);
        }
        return out;
    }

    // ------------------------------------------------------------------ mutations

    private static void mutate(Path src, Path dst, String kind) throws IOException {
        if (Files.exists(dst)) deleteTree(dst);
        Files.createDirectories(dst);
        List<Path> copied = new ArrayList<Path>();
        Files.walk(src).forEach(path -> {
            if (!Files.isRegularFile(path)) return;
            String relative = src.relativize(path).toString().replace('\\', '/');
            if (relative.equals(".gitattributes")) return;
            try {
                Path target = dst.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                copied.add(target);
            } catch (IOException error) {
                throw new IllegalStateException(error.getClass().getSimpleName());
            }
        });
        if (copied.size() != DATA_FILES.length + 1 + MAX_MEDIA_FILES) {
            throw new IllegalStateException("unexpected-fixture-file-count");
        }

        if (kind.equals("flip")) {
            Path target = dst.resolve("media/rec00008.ts");
            byte[] data = Files.readAllBytes(target);
            data[0] = (byte) (data[0] ^ 0x40);
            Files.write(target, data);
            return; // manifest intentionally left stale
        }
        if (kind.equals("empty-completed")) {
            Files.write(dst.resolve("media/rec00008.ts"), new byte[0]);
            rewriteRow(dst, "rec00008", "COMPLETED",
                "rec00008\tCOMPLETED\t1700000104\t1700000164\t0\tmedia/rec00008.ts");
        } else if (kind.equals("duplicate-completed")) {
            rewriteRow(dst, "rec00046", "RECORDING",
                "rec00046\tCOMPLETED\t1700000598\t1700000658\t64\tmedia/rec00046.ts");
        } else if (kind.equals("missing-restored")) {
            Files.write(dst.resolve("media/rec00027.ts"), pattern(128, 27));
        } else if (kind.equals("oversize-shrunk")) {
            Files.write(dst.resolve("media/rec00005.ts"), pattern(64, 5));
            rewriteRow(dst, "rec00005", "COMPLETED",
                "rec00005\tCOMPLETED\t1700000065\t1700000125\t64\tmedia/rec00005.ts");
        } else if (kind.equals("journal-cap")) {
            rewriteDimension(dst, "max_journal_bytes", "8");
        } else if (kind.startsWith("leak-")) {
            String injection;
            if (kind.equals("leak-url")) injection = urlMarker();
            else if (kind.equals("leak-credential")) injection = credentialMarker();
            else if (kind.equals("leak-pin")) injection = pinMarker();
            else if (kind.equals("leak-secret")) injection = secretMarker();
            else if (kind.equals("leak-payload")) injection = payloadMarker();
            else throw new IllegalArgumentException("unknown-mutant");
            Path report = dst.resolve("diagnostics.tsv");
            Files.write(report, (text(Files.readAllBytes(report)) + "x=" + injection + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("unknown-mutant");
        }
        remanifest(dst);
    }

    private static void rewriteRow(Path dir, String idText, String targetState, String replacement) throws IOException {
        Path catalog = dir.resolve("catalog.tsv");
        StringBuilder out = new StringBuilder();
        String prefix = idText + "\t";
        for (String row : text(Files.readAllBytes(catalog)).split("\n", -1)) {
            if (row.isEmpty()) continue;
            if (row.startsWith(prefix) && row.contains("\t" + targetState + "\t")) out.append(replacement).append('\n');
            else out.append(row).append('\n');
        }
        Files.write(catalog, bytes(out.toString()));
    }

    private static void rewriteDimension(Path dir, String key, String value) throws IOException {
        Path path = dir.resolve("dimensions.tsv");
        StringBuilder out = new StringBuilder();
        for (String row : text(Files.readAllBytes(path)).split("\n", -1)) {
            if (row.isEmpty()) continue;
            if (row.startsWith(key + "=")) out.append(key).append('=').append(value).append('\n');
            else out.append(row).append('\n');
        }
        Files.write(path, bytes(out.toString()));
    }

    private static void remanifest(Path dir) throws IOException {
        LinkedHashMap<String, byte[]> actual = new LinkedHashMap<String, byte[]>();
        for (String name : DATA_FILES) actual.put(name, Files.readAllBytes(dir.resolve(name)));
        for (String name : scanMedia(dir)) actual.put(name, Files.readAllBytes(dir.resolve(name)));
        Files.write(dir.resolve(MANIFEST), bytes(renderManifest(actual)));
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walk(dir).sorted(Collections.reverseOrder()).forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException error) {
                throw new IllegalStateException(error.getClass().getSimpleName());
            }
        });
    }
}
