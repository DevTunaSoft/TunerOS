import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.devtunasoft.tuneros.core.TimeshiftWindow;
import com.devtunasoft.tuneros.core.TimeshiftWindowKt;

/**
 * QA-67 host gate: bounded HLS program-date wall-clock rendering and playlist tag placement.
 * Compiles the unmodified production TimeshiftWindow.kt read-only; no production file is edited.
 * Modes: generate <dir> | verify <dir> [full|byte|manifest|digest|semantic].
 */
public final class Qa67Gate {
    private static final int SEED = 67024;
    private static final String[] FAMILIES = {
        "EPOCH_ZERO", "PRE_EPOCH", "LEAP_DAY", "MILLIS_CARRY",
        "YEAR_BOUNDARY", "FAR_FUTURE", "UTC_WALL_CLOCK", "MAX_YEAR",
    };
    private static final int CASES_PER_FAMILY = 6;
    private static final int CASE_COUNT = 48;
    private static final int MAX_LINE_CHARS = 160;
    private static final int MAX_SEGMENTS = 3;
    private static final long WINDOW_MAX_BYTES = 5000L;
    private static final int CYCLES = 6;
    private static final long MAX_HEAP_BYTES = 50L * 1024L * 1024L;
    private static final String CASES_HEADER = "id\tfamily\tepochMillis\texpected\tdurationMs\tbytes";
    private static final String DIMS_HEADER = "key\tvalue";
    private static final String[] DATA_FILES = {"cases.tsv", "corrupt.hex", "dimensions.tsv"};
    private static final String[] DIM_KEYS = {
        "seed", "case_count", "family_count", "cases_per_family", "max_line_chars",
        "max_segments", "window_max_bytes", "cycles", "max_heap_bytes", "max_thread_drift",
        "expected_cases_sha", "expected_corrupt_sha",
    };
    private static final String[] CORRUPT_NAMES = {
        "BAD_FIELD_COUNT", "BAD_EPOCH", "BAD_EXPECTED", "BAD_FAMILY", "BAD_ID", "BAD_DURATION",
    };
    private static final Pattern EXPECTED_DATE = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
    private static final Pattern ID_PATTERN = Pattern.compile("qa67x-[0-9]{5}");
    private static final Pattern[] PRIVACY = {
        Pattern.compile("https?://", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(m3u8?|xmltv)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bbearer\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(password|passwd|token|secret|api[_-]?key|authorization|pin)\\s*[=:]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(drm|widevine|fairplay|playready|keyid)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(recording[-_ ]?payload)\\b", Pattern.CASE_INSENSITIVE),
    };

    /* Mutation seam: the RED driver flips this to true in a scratch copy only. */
    private static final boolean LEAK_PROBE = false;

    private static final List<String> report = new ArrayList<String>();
    private static int passes = 0;
    private static int failures = 0;
    private static String first = null;

    private static final DateTimeFormatter REFERENCE = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final class ACase {
        String id;
        String family;
        long epoch;
        String expected;
        int durationMs;
        long bytes;
    }

    private static void emit(String line) {
        report.add(line);
        System.out.println(line);
    }

    private static void check(String code, boolean ok, String detail) {
        if (ok) {
            passes++;
            emit("CHECK\t" + code + "\tPASS\t" + detail);
        } else {
            failures++;
            if (first == null) first = code;
            emit("CHECK\t" + code + "\tFAIL\t" + detail);
        }
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("DIGEST_UNAVAILABLE");
        }
    }

    private static String derivedToken() {
        String hexDigest = sha256(utf8("qa67-seed-" + SEED));
        return "QA67SEED" + hexDigest.substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private static long[] epochs(String family) {
        if (family.equals("EPOCH_ZERO")) return new long[]{0L, 1L, 999L, 1000L, 1234L, 59999L};
        if (family.equals("PRE_EPOCH")) return new long[]{-1L, -2L, -999L, -1000L, -123456789L, -86400000L};
        if (family.equals("LEAP_DAY")) {
            long base = 1709164800000L;
            return new long[]{base, base + 1, base + 999, base + 1000, base + 3600000L, base + 86399000L};
        }
        if (family.equals("MILLIS_CARRY")) {
            long base = 1700000000999L;
            return new long[]{base, base + 1, base + 1000, base + 1001, base + 2000, base + 999999L};
        }
        if (family.equals("YEAR_BOUNDARY")) {
            long base = 946684799999L;
            return new long[]{base, base + 1, base + 1000, base + 86400000L, base + 2L, base + 999L};
        }
        if (family.equals("FAR_FUTURE")) {
            long base = 4102444800000L;
            return new long[]{base, base + 1, base + 999, base + 1000, base + 3600000L, base + 86400000L};
        }
        if (family.equals("UTC_WALL_CLOCK")) {
            long base = 1700000008000L;
            return new long[]{base, base + 1, base + 999, base + 1000, base + 60000L, base + 3600000L};
        }
        long base = 253402300799000L;
        return new long[]{base, base + 1, base + 999, base - 1, base - 999, base - 1000};
    }

    private static List<ACase> buildCases() {
        List<ACase> cases = new ArrayList<ACase>();
        int n = 0;
        for (String family : FAMILIES) {
            long[] values = epochs(family);
            for (long epoch : values) {
                ACase c = new ACase();
                c.id = String.format(Locale.ROOT, "qa67x-%05d", ++n);
                c.family = family;
                c.epoch = epoch;
                c.expected = REFERENCE.format(Instant.ofEpochMilli(epoch));
                c.durationMs = 1000 + (n % 7) * 250;
                c.bytes = 100L + (n % 11) * 25L;
                cases.add(c);
            }
        }
        return cases;
    }

    private static String casesText(List<ACase> cases) {
        StringBuilder out = new StringBuilder(CASES_HEADER).append('\n');
        for (ACase c : cases) {
            out.append(c.id).append('\t').append(c.family).append('\t').append(c.epoch)
                .append('\t').append(c.expected).append('\t').append(c.durationMs)
                .append('\t').append(c.bytes).append('\n');
        }
        return out.toString();
    }

    private static String corruptRow(String name) {
        if (name.equals("BAD_FIELD_COUNT")) return "qa67x-90001\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\t1000";
        if (name.equals("BAD_EPOCH")) return "qa67x-90002\tEPOCH_ZERO\tNOT_A_NUMBER\t1970-01-01T00:00:00.000Z\t1000\t10";
        if (name.equals("BAD_EXPECTED")) return "qa67x-90003\tEPOCH_ZERO\t0\t1970-01-01T00:00:00Z\t1000\t10";
        if (name.equals("BAD_FAMILY")) return "qa67x-90004\t\t0\t1970-01-01T00:00:00.000Z\t1000\t10";
        if (name.equals("BAD_ID")) return "bad id\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\t1000\t10";
        return "qa67x-90006\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\tZERO\t10";
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static String corruptText() {
        StringBuilder out = new StringBuilder();
        for (String name : CORRUPT_NAMES) out.append(name).append('\t').append(hex(utf8(corruptRow(name)))).append('\n');
        return out.toString();
    }

    private static String dimensionsText(String casesSha, String corruptSha) {
        StringBuilder out = new StringBuilder(DIMS_HEADER).append('\n');
        out.append("seed\t").append(SEED).append('\n');
        out.append("case_count\t").append(CASE_COUNT).append('\n');
        out.append("family_count\t").append(FAMILIES.length).append('\n');
        out.append("cases_per_family\t").append(CASES_PER_FAMILY).append('\n');
        out.append("max_line_chars\t").append(MAX_LINE_CHARS).append('\n');
        out.append("max_segments\t").append(MAX_SEGMENTS).append('\n');
        out.append("window_max_bytes\t").append(WINDOW_MAX_BYTES).append('\n');
        out.append("cycles\t").append(CYCLES).append('\n');
        out.append("max_heap_bytes\t").append(MAX_HEAP_BYTES).append('\n');
        out.append("max_thread_drift\t").append(0).append('\n');
        out.append("expected_cases_sha\t").append(casesSha).append('\n');
        out.append("expected_corrupt_sha\t").append(corruptSha).append('\n');
        return out.toString();
    }

    private static Map<String, byte[]> generatedBytes() {
        byte[] cases = utf8(casesText(buildCases()));
        byte[] corrupt = utf8(corruptText());
        byte[] dims = utf8(dimensionsText(sha256(cases), sha256(corrupt)));
        StringBuilder manifest = new StringBuilder();
        manifest.append(sha256(cases)).append('\t').append("cases.tsv").append('\n');
        manifest.append(sha256(corrupt)).append('\t').append("corrupt.hex").append('\n');
        manifest.append(sha256(dims)).append('\t').append("dimensions.tsv").append('\n');
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        out.put(".gitattributes", utf8("* -text\n"));
        out.put("cases.tsv", cases);
        out.put("corrupt.hex", corrupt);
        out.put("dimensions.tsv", dims);
        out.put("sha256.txt", utf8(manifest.toString()));
        return out;
    }

    private static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, byte[]> files = generatedBytes();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
        System.out.println("GENERATE\tfiles=" + files.size() + "\tcases=" + CASE_COUNT);
    }

    private static Map<String, String> parseDims(String text) {
        String[] lines = text.split("\n", -1);
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!lines[0].equals(DIMS_HEADER)) return null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] f = lines[i].split("\t", -1);
            if (f.length != 2 || out.containsKey(f[0])) return null;
            out.put(f[0], f[1]);
        }
        if (out.size() != DIM_KEYS.length) return null;
        for (String key : DIM_KEYS) if (!out.containsKey(key)) return null;
        return out;
    }

    private static Map<String, String> parseManifest(String text) {
        String[] lines = text.split("\n", -1);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 2 || !f[0].matches("[0-9a-f]{64}")) return null;
            out.put(f[1], f[0]);
        }
        if (out.size() != DATA_FILES.length) return null;
        for (String name : DATA_FILES) if (!out.containsKey(name)) return null;
        return out;
    }

    private static ACase parseCase(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 6) return null;
        if (!ID_PATTERN.matcher(f[0]).matches()) return null;
        boolean known = false;
        for (String family : FAMILIES) if (family.equals(f[1])) known = true;
        if (!known) return null;
        long epoch;
        int duration;
        long bytes;
        try {
            epoch = Long.parseLong(f[2]);
            duration = Integer.parseInt(f[4]);
            bytes = Long.parseLong(f[5]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (duration <= 0 || bytes < 0 || !EXPECTED_DATE.matcher(f[3]).matches()) return null;
        ACase c = new ACase();
        c.id = f[0];
        c.family = f[1];
        c.epoch = epoch;
        c.expected = f[3];
        c.durationMs = duration;
        c.bytes = bytes;
        return c;
    }

    private static byte[] decodeHex(String raw) {
        if (raw.length() % 2 != 0) return null;
        byte[] out = new byte[raw.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(raw.charAt(2 * i), 16);
            int lo = Character.digit(raw.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static boolean privacyFree(String text) {
        for (Pattern p : PRIVACY) if (p.matcher(text).find()) return false;
        return !text.contains(derivedToken());
    }

    private static void verify(Path dir, String mode) throws Exception {
        boolean full = mode.equals("full");
        boolean skipByte = !full && (mode.equals("byte") ? false : true);
        boolean skipManifest = !full && (mode.equals("byte") || mode.equals("digest") || mode.equals("semantic"));
        boolean skipDigest = !full && mode.equals("semantic");
        if (!full && !(mode.equals("byte") || mode.equals("manifest") || mode.equals("digest") || mode.equals("semantic"))) {
            System.out.println("VERIFY\tFAIL\tunknown-mode");
            System.exit(2);
        }
        if (!full && mode.equals("manifest")) skipByte = true;
        if (!full && mode.equals("digest")) skipByte = true;
        if (!full && mode.equals("semantic")) skipByte = true;

        Map<String, byte[]> actual = new LinkedHashMap<String, byte[]>();
        for (String name : DATA_FILES) actual.put(name, Files.readAllBytes(dir.resolve(name)));
        String dimsText = new String(actual.get("dimensions.tsv"), StandardCharsets.UTF_8);
        String casesText = new String(actual.get("cases.tsv"), StandardCharsets.UTF_8);
        String corruptText = new String(actual.get("corrupt.hex"), StandardCharsets.UTF_8);
        String manifestText = new String(Files.readAllBytes(dir.resolve("sha256.txt")), StandardCharsets.UTF_8);

        String[] dimLines = dimsText.split("\n", -1);
        String[] caseLines = casesText.split("\n", -1);
        check("HEADER_STRICT", dimLines.length > 0 && dimLines[0].equals(DIMS_HEADER)
            && caseLines.length > 0 && caseLines[0].equals(CASES_HEADER), "dims-and-cases");

        Map<String, String> manifest = parseManifest(manifestText);
        boolean manifestOk = manifest != null;
        if (manifestOk) {
            for (String name : DATA_FILES) {
                if (!sha256(actual.get(name)).equals(manifest.get(name))) manifestOk = false;
            }
        }
        if (skipManifest) emit("SKIP\tMANIFEST_CONSISTENT\tmode=" + mode);
        else check("MANIFEST_CONSISTENT", manifestOk, "data-files");

        if (skipByte) emit("SKIP\tBYTE_IDENTITY\tmode=" + mode);
        else {
            Map<String, byte[]> generated = generatedBytes();
            boolean identical = true;
            for (String name : generated.keySet()) {
                byte[] committed = Files.readAllBytes(dir.resolve(name));
                if (!Arrays.equals(committed, generated.get(name))) identical = false;
            }
            check("BYTE_IDENTITY", identical, "regenerated-bytes");
        }

        Map<String, String> dims = parseDims(dimsText);
        boolean digestOk = dims != null
            && dims.get("expected_cases_sha").equals(sha256(actual.get("cases.tsv")))
            && dims.get("expected_corrupt_sha").equals(sha256(actual.get("corrupt.hex")));
        if (skipDigest) emit("SKIP\tPINNED_DIGEST\tmode=" + mode);
        else check("PINNED_DIGEST", digestOk, "pinned-data-digests");

        int rawRows = 0;
        boolean rowShape = true;
        Set<String> ids = new HashSet<String>();
        List<ACase> cases = new ArrayList<ACase>();
        for (int i = 1; i < caseLines.length; i++) {
            if (caseLines[i].isEmpty()) continue;
            rawRows++;
            String line = caseLines[i];
            if (line.length() > MAX_LINE_CHARS) rowShape = false;
            int tabs = 0;
            for (int j = 0; j < line.length(); j++) if (line.charAt(j) == '\t') tabs++;
            if (tabs != 5) rowShape = false;
            ACase c = parseCase(line);
            if (c == null) rowShape = false;
            else {
                if (!ids.add(c.id)) rowShape = false;
                cases.add(c);
            }
        }
        check("ROW_BOUNDS", cases.size() == rawRows && rowShape, "rows=" + rawRows);

        boolean capOk = dims != null
            && dims.get("seed").equals(String.valueOf(SEED))
            && dims.get("case_count").equals(String.valueOf(CASE_COUNT))
            && dims.get("family_count").equals(String.valueOf(FAMILIES.length))
            && dims.get("cases_per_family").equals(String.valueOf(CASES_PER_FAMILY))
            && dims.get("max_line_chars").equals(String.valueOf(MAX_LINE_CHARS))
            && dims.get("max_segments").equals(String.valueOf(MAX_SEGMENTS))
            && dims.get("window_max_bytes").equals(String.valueOf(WINDOW_MAX_BYTES))
            && dims.get("cycles").equals(String.valueOf(CYCLES))
            && dims.get("max_heap_bytes").equals(String.valueOf(MAX_HEAP_BYTES))
            && dims.get("max_thread_drift").equals("0")
            && cases.size() == CASE_COUNT;
        check("CAP_BINDING", capOk, "case-count=" + cases.size());

        Map<String, Integer> perFamily = new HashMap<String, Integer>();
        for (ACase c : cases) {
            Integer v = perFamily.get(c.family);
            perFamily.put(c.family, v == null ? 1 : v + 1);
        }
        boolean familiesOk = perFamily.size() == FAMILIES.length;
        for (String family : FAMILIES) {
            Integer v = perFamily.get(family);
            if (v == null || v != CASES_PER_FAMILY) familiesOk = false;
        }
        check("FAMILY_COVERAGE", familiesOk, "families=" + perFamily.size());

        boolean corruptOk = true;
        int corruptCount = 0;
        if (dims != null) {
            String[] lines = corruptText.split("\n", -1);
            for (String line : lines) {
                if (line.isEmpty()) continue;
                corruptCount++;
                String[] f = line.split("\t", -1);
                if (f.length != 2 || decodeHex(f[1]) == null) { corruptOk = false; continue; }
                String row = new String(decodeHex(f[1]), StandardCharsets.UTF_8);
                if (parseCase(row) != null) corruptOk = false;
            }
        }
        check("CORRUPT_FAIL_CLOSED", corruptOk && corruptCount == CORRUPT_NAMES.length, "variants=" + corruptCount);

        check("PRIVACY_FIXTURE", privacyFree(dimsText) && privacyFree(casesText)
            && privacyFree(corruptText) && privacyFree(manifestText), "fixture-bytes");

        boolean datesOk = true;
        int dateFails = 0;
        for (ACase c : cases) {
            try {
                String actualDate = TimeshiftWindowKt.programDate(c.epoch);
                if (!c.expected.equals(actualDate)) { datesOk = false; dateFails++; }
            } catch (Throwable t) {
                datesOk = false;
                dateFails++;
            }
        }
        check("DATE_EXACT", datesOk, "cases=" + cases.size() + " mismatches=" + dateFails);

        boolean tagOk = true;
        for (int i = 0; i < cases.size(); i += 8) {
            ACase c = cases.get(i);
            TimeshiftWindow w = new TimeshiftWindow(MAX_SEGMENTS, WINDOW_MAX_BYTES);
            w.add(new TimeshiftWindow.Segment(1L, 2.0, c.bytes, "seg-001.ts", false, Long.valueOf(c.epoch)));
            String playlist = w.playlist();
            String wanted = "#EXT-X-PROGRAM-DATE-TIME:" + c.expected + "\n#EXTINF:2.000,\nseg-001.ts\n";
            if (!playlist.contains(wanted)) tagOk = false;
        }
        check("PLAYLIST_TAG", tagOk, "single-segment-tag-order");

        TimeshiftWindow noDate = new TimeshiftWindow(MAX_SEGMENTS, WINDOW_MAX_BYTES);
        noDate.add(new TimeshiftWindow.Segment(1L, 2.0, 100L, "plain.ts", false, null));
        check("NULL_OMITS", !noDate.playlist().contains("#EXT-X-PROGRAM-DATE-TIME"), "no-tag-without-date");

        TimeshiftWindow ordered = new TimeshiftWindow(MAX_SEGMENTS, WINDOW_MAX_BYTES);
        List<String> wantedTags = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            ACase c = cases.get(i * 8);
            ordered.add(new TimeshiftWindow.Segment(i + 1L, 2.0, 100L, "seg-" + i + ".ts", false, Long.valueOf(c.epoch)));
            wantedTags.add("#EXT-X-PROGRAM-DATE-TIME:" + c.expected);
        }
        List<String> seenTags = new ArrayList<String>();
        for (String line : ordered.playlist().split("\n", -1)) {
            if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) seenTags.add(line);
        }
        check("ORDER_TAG", seenTags.equals(wantedTags), "tags=" + seenTags.size());

        int threadsBefore = Thread.activeCount();
        for (int cycle = 0; cycle < CYCLES; cycle++) {
            for (ACase c : cases) {
                TimeshiftWindowKt.programDate(c.epoch);
            }
            TimeshiftWindow w = new TimeshiftWindow(MAX_SEGMENTS, WINDOW_MAX_BYTES);
            ACase c = cases.get(cycle);
            w.add(new TimeshiftWindow.Segment(cycle + 1L, 2.0, c.bytes, "cycle.ts", false, Long.valueOf(c.epoch)));
            w.playlist();
        }
        int threadsAfter = Thread.activeCount();
        check("THREAD_STABLE", threadsAfter - threadsBefore == 0,
            "before=" + threadsBefore + " after=" + threadsAfter);

        System.gc();
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        check("MEM_STABLE", used < MAX_HEAP_BYTES, "used=" + used + " max=" + MAX_HEAP_BYTES);

        if (LEAK_PROBE) emit("LEAK\t" + derivedToken());
        StringBuilder all = new StringBuilder();
        for (String line : report) all.append(line).append('\n');
        check("PRIVACY_REPORT", privacyFree(all.toString()), "report-lines=" + report.size());

        emit("SUMMARY\tchecks=" + (passes + failures) + "\tpass=" + passes + "\tfail=" + failures);
        emit("FIRST\t" + (first == null ? "NONE" : first));
        emit("VERDICT\t" + (failures == 0 ? "PASS" : "FAIL"));
        System.out.flush();
        if (failures != 0) System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("USAGE\tqa67 <generate|verify> <fixture-dir> [mode]");
            System.exit(2);
        }
        Path dir = Paths.get(args[1]);
        if (args[0].equals("generate")) generate(dir);
        else if (args[0].equals("verify")) verify(dir, args.length > 2 ? args[2] : "full");
        else {
            System.out.println("USAGE\tqa67 <generate|verify> <fixture-dir> [mode]");
            System.exit(2);
        }
    }
}
