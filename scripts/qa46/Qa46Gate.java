import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA-46 zero-dependency host gate.
 *
 * Slice: bounded HTTP identity-length completion verdicts. The gate compiles the real
 * core BodyLength object and drives it from a byte-pinned scratch fixture. No fixture
 * field is an endpoint, credential or recording payload.
 */
public final class Qa46Gate {
    private static final String PINNED_DATA_DIGEST =
        "48434FEC709F743AAA89EF4804064126D666F42A4729E98CC305875BB0B3858C";
    private static final String[] DATA_FILES = {"dimensions.tsv", "cases.tsv", "corrupt.tsv", "notes.txt"};
    private static final String MANIFEST_FILE = "sha256.txt";
    private static final String MANIFEST_HEADER = "# qa46 pinned data digests";
    private static final long MAX_ROW_BYTES = 256L;
    private static final long HEAP_LIMIT_BYTES = 16L * 1024L * 1024L;
    private static final int THREAD_LIMIT = 2;

    private static final long[] DECLARED = {-1L, 0L, 42L, 100L, Long.MAX_VALUE, Long.MIN_VALUE};
    private static final String[] ENCODINGS = {
        "~null~", "", "identity", "IDENTITY", "IdEnTiTy", "gzip", "br", "none",
        " identity", "identity ", "identity; q=1"
    };
    private static final long[] COPIED = {0L, 1L, 42L, 100L, Long.MAX_VALUE, -1L, Long.MIN_VALUE};
    private static final int EXPECTED_CASES = DECLARED.length * ENCODINGS.length * COPIED.length;

    private static final String VERDICT_COMPLETE = "COMPLETE";
    private static final String VERDICT_UNVERIFIED = "UNVERIFIED";
    private static final String VERDICT_BAD = "TRUNCATED_OR_OVERRUN";
    private static final Set<String> VERDICTS = new HashSet<>(
        Arrays.asList(VERDICT_COMPLETE, VERDICT_UNVERIFIED, VERDICT_BAD)
    );

    private static final Pattern ID_SHAPE = Pattern.compile("[A-Z][0-9]{4}");
    private static final Pattern URL_SCHEME = Pattern.compile("(?i)(https?|ftp)://");
    private static final Pattern URL_FIELD = Pattern.compile("(?i)\\b(url|endpoint|uri)[ \\t]*[:=]");
    private static final Pattern USER_FIELD = Pattern.compile("(?i)\\b(user|username|login)[ \\t]*[:=]");
    private static final Pattern PASS_FIELD = Pattern.compile("(?i)\\b(pass|passwd|password)[ \\t]*[:=]");
    private static final Pattern PIN_FIELD = Pattern.compile("(?i)\\bpin[ \\t]*[:=][ \\t]*[0-9]");
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i)\\b(secret|credential)[ \\t]*[:=]");
    private static final Pattern PAYLOAD_MARK = Pattern.compile("(?i)recording-payload|R[0-9]{5}~");
    private static final String SYNTHETIC_MARKER =
        "QA46-" + Long.toHexString(0xC0FFEEL ^ 0x55L).toUpperCase(Locale.ROOT);

    private Qa46Gate() {}

    private static final class Report {
        final StringBuilder text = new StringBuilder();
        int checks;
        int passed;
        int failed;
        String first;

        void line(String code, boolean ok, String detail) {
            checks++;
            if (ok) {
                passed++;
            } else {
                failed++;
                if (first == null) first = code;
            }
            text.append("CHECK\t").append(ok ? "PASS" : "FAIL").append('\t')
                .append(code).append('\t').append(detail).append('\n');
        }
    }

    private static final class ParsedCase {
        String id;
        long declared;
        String encoding;
        long copied;
        long expectedLength;
        String verdict;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] unhex(String value) {
        if ((value.length() & 1) != 0) return null;
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return hex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }

    private static long expectedLength(long declared, String token) {
        if (token == null || token.equals("~null~") || token.equalsIgnoreCase("identity")) return declared;
        return -1L;
    }

    private static String verdict(long expected, long copied) {
        if (expected < 0L) return VERDICT_UNVERIFIED;
        if (expected == copied) return VERDICT_COMPLETE;
        return VERDICT_BAD;
    }

    private static byte[] validCaseBytes(String id, long declared, String token, long copied) {
        long length = expectedLength(declared, token);
        String row = id + "\t" + declared + "\t" + token + "\t" + copied + "\t"
            + length + "\t" + verdict(length, copied) + "\n";
        return row.getBytes(StandardCharsets.US_ASCII);
    }

    private static String dimensionsBytes(int cases, int corrupt, int cap, int cycles) {
        return "key\tvalue\n"
            + "cases\t" + cases + "\n"
            + "corrupt\t" + corrupt + "\n"
            + "cap\t" + cap + "\n"
            + "cycles\t" + cycles + "\n"
            + "version\t1\n";
    }

    private static String casesBytes() {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (long declared : DECLARED) {
            for (String token : ENCODINGS) {
                for (long copied : COPIED) {
                    index++;
                    String id = String.format(Locale.ROOT, "K%04d", index);
                    long length = expectedLength(declared, token);
                    out.append(id).append('\t').append(declared).append('\t').append(token)
                        .append('\t').append(copied).append('\t').append(length)
                        .append('\t').append(verdict(length, copied)).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static byte[] withByte(byte[] source, int offset, int value) {
        byte[] copy = source.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    private static String corruptBytes() {
        StringBuilder out = new StringBuilder();
        byte[][] rows = new byte[8][];
        String[] codes = {
            "TRUNCATED_ROW", "FIELD_COUNT", "BAD_LONG", "BAD_TOKEN",
            "BAD_VERDICT", "DUPLICATE_ID", "BAD_UTF8", "OVERLONG_ROW"
        };
        byte[] valid = validCaseBytes("C0009", 1L, "identity", 1L);
        rows[0] = Arrays.copyOf(valid, valid.length - 1);
        String text = new String(valid, StandardCharsets.US_ASCII);
        String[] fields = text.substring(0, text.length() - 1).split("\t", -1);
        rows[1] = (fields[0] + "\t" + fields[1] + "\t" + fields[2] + "\t" + fields[4]
            + "\t" + fields[5] + "\n").getBytes(StandardCharsets.US_ASCII);
        rows[2] = (fields[0] + "\tx\t" + fields[2] + "\t" + fields[3] + "\t"
            + fields[4] + "\t" + fields[5] + "\n").getBytes(StandardCharsets.US_ASCII);
        rows[3] = (fields[0] + "\t" + fields[1] + "\tidentity@x\t" + fields[3] + "\t"
            + fields[4] + "\t" + fields[5] + "\n").getBytes(StandardCharsets.US_ASCII);
        rows[4] = (fields[0] + "\t" + fields[1] + "\t" + fields[2] + "\t" + fields[3]
            + "\t" + fields[4] + "\tCOMPLETE-X\n").getBytes(StandardCharsets.US_ASCII);
        byte[] dup = validCaseBytes("K0001", 1L, "identity", 1L);
        rows[5] = dup;
        rows[6] = withByte(valid, 2, 0xFF);
        StringBuilder longToken = new StringBuilder();
        for (int i = 0; i < 300; i++) longToken.append('x');
        rows[7] = (fields[0] + "\t" + fields[1] + "\t" + longToken + "\t" + fields[3]
            + "\t" + fields[4] + "\t" + fields[5] + "\n").getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < rows.length; i++) {
            out.append(String.format(Locale.ROOT, "C%04d", i + 1)).append('\t')
                .append(codes[i]).append('\t').append(hex(rows[i])).append('\n');
        }
        return out.toString();
    }

    private static String notesBytes() {
        return "QA-46 scratch fixture: bounded identity-length completion verdict matrix.\n"
            + "Rows come from fixed constants and invented QA46 labels only.\n"
            + "No service address, access value or private media appears in this file.\n";
    }

    private static Map<String, byte[]> generatedData() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(DATA_FILES[0], dimensionsBytes(EXPECTED_CASES, 8, EXPECTED_CASES, 4)
            .getBytes(StandardCharsets.US_ASCII));
        files.put(DATA_FILES[1], casesBytes().getBytes(StandardCharsets.US_ASCII));
        files.put(DATA_FILES[2], corruptBytes().getBytes(StandardCharsets.US_ASCII));
        files.put(DATA_FILES[3], notesBytes().getBytes(StandardCharsets.US_ASCII));
        return files;
    }

    private static byte[] manifestBytes(Map<String, byte[]> data) {
        StringBuilder out = new StringBuilder(MANIFEST_HEADER).append('\n');
        for (String name : DATA_FILES) out.append(sha256(data.get(name))).append('\t').append(name).append('\n');
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, byte[]> data = generatedData();
        for (Map.Entry<String, byte[]> entry : data.entrySet()) {
            Files.write(dir.resolve(entry.getKey()), entry.getValue());
        }
        Files.write(dir.resolve(MANIFEST_FILE), manifestBytes(data));
        StringBuilder out = new StringBuilder();
        out.append("GENERATE\tfiles=5\tcases=").append(EXPECTED_CASES).append("\tcorrupt=8\n");
        out.append("DIGEST\taggregate=").append(sha256(manifestBytes(data))).append('\n');
        System.out.print(out);
    }

    private static String parseCaseRow(byte[] raw, Set<String> seenIds) {
        if (raw.length > MAX_ROW_BYTES) return "OVERLONG_ROW";
        int newlines = 0;
        for (byte b : raw) if (b == '\n') newlines++;
        if (newlines == 0 || raw.length == 0 || raw[raw.length - 1] != '\n') return "TRUNCATED_ROW";
        if (newlines != 1) return "EXTRA_LINE";
        for (int i = 0; i < raw.length - 1; i++) {
            if ((raw[i] & 0xFF) > 0x7F) return "BAD_UTF8";
        }
        String line = new String(raw, 0, raw.length - 1, StandardCharsets.US_ASCII);
        String[] fields = line.split("\t", -1);
        if (fields.length != 6) return "FIELD_COUNT";
        if (!ID_SHAPE.matcher(fields[0]).matches()) return "BAD_ID";
        if (seenIds.contains(fields[0])) return "DUPLICATE_ID";
        long declared;
        long copied;
        long expected;
        try {
            declared = Long.parseLong(fields[1]);
            copied = Long.parseLong(fields[3]);
            expected = Long.parseLong(fields[4]);
        } catch (NumberFormatException e) {
            return "BAD_LONG";
        }
        for (int i = 0; i < fields[2].length(); i++) {
            char c = fields[2].charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == ' ' || c == ';' || c == '='
                || c == '~' || c == '_' || c == '-';
            if (!allowed) return "BAD_TOKEN";
        }
        if (!VERDICTS.contains(fields[5])) return "BAD_VERDICT";
        seenIds.add(fields[0]);
        return null;
    }

    private static ParsedCase parseCaseFields(String line) {
        String[] fields = line.split("\t", -1);
        ParsedCase row = new ParsedCase();
        row.id = fields[0];
        row.declared = Long.parseLong(fields[1]);
        row.encoding = fields[2].equals("~null~") ? null : fields[2];
        row.copied = Long.parseLong(fields[3]);
        row.expectedLength = Long.parseLong(fields[4]);
        row.verdict = fields[5];
        return row;
    }

    private static Map<String, String> readDimensions(Path dir) throws IOException {
        String text = new String(Files.readAllBytes(dir.resolve(DATA_FILES[0])), StandardCharsets.US_ASCII);
        String[] lines = text.split("\n", -1);
        Map<String, String> values = new LinkedHashMap<>();
        if (lines.length < 2 || !lines[0].equals("key\tvalue")) throw new IOException("DIMENSION_HEADER");
        for (int i = 1; i < lines.length - 1; i++) {
            String[] pair = lines[i].split("\t", -1);
            if (pair.length != 2) throw new IOException("DIMENSION_FIELDS");
            values.put(pair[0], pair[1]);
        }
        if (!lines[lines.length - 1].isEmpty()) throw new IOException("DIMENSION_TAIL");
        return values;
    }

    private static Object invoke(Object target, Method method, Object... args) throws Exception {
        return method.invoke(target, args);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static boolean privacyClean(String report, Map<String, byte[]> fixtureBytes) {
        String all = report;
        for (Map.Entry<String, byte[]> entry : fixtureBytes.entrySet()) {
            all += new String(entry.getValue(), StandardCharsets.ISO_8859_1) + "\n";
        }
        Pattern[] patterns = {URL_SCHEME, URL_FIELD, USER_FIELD, PASS_FIELD, PIN_FIELD,
            SECRET_FIELD, PAYLOAD_MARK};
        for (Pattern pattern : patterns) {
            if (pattern.matcher(all).find()) return false;
        }
        return !all.contains(SYNTHETIC_MARKER);
    }

    private static int verify(Path dir) throws Exception {
        Report report = new Report();
        Map<String, byte[]> onDisk = new LinkedHashMap<>();
        for (String name : DATA_FILES) onDisk.put(name, Files.readAllBytes(dir.resolve(name)));
        byte[] manifest = Files.readAllBytes(dir.resolve(MANIFEST_FILE));

        String[] manifestLines = new String(manifest, StandardCharsets.US_ASCII).split("\n", -1);
        report.line("HEADER_STRICT", manifestLines.length == 6 && manifestLines[0].equals(MANIFEST_HEADER)
            && manifestLines[5].isEmpty(), "lines=" + (manifestLines.length - 1));
        report.line("PRIVACY_SCAN", privacyClean(report.text.toString(), onDisk), "patterns=8");
        if (report.failed > 0) {
            finish(report);
            return 1;
        }

        Map<String, String> dimensions = readDimensions(dir);
        Map<String, byte[]> expectedData = generatedData();
        List<ParsedCase> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        boolean casesParse = true;
        String casesRaw = new String(onDisk.get(DATA_FILES[1]), StandardCharsets.US_ASCII);
        for (String line : casesRaw.split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (parseCaseRow((line + "\n").getBytes(StandardCharsets.US_ASCII), caseIds) != null) {
                casesParse = false;
                break;
            }
            cases.add(parseCaseFields(line));
        }
        List<String[]> corrupt = new ArrayList<>();
        String corruptRaw = new String(onDisk.get(DATA_FILES[2]), StandardCharsets.US_ASCII);
        for (String line : corruptRaw.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            corrupt.add(parts);
        }
        boolean corruptOk = casesParse;
        if (corruptOk) {
            for (String[] parts : corrupt) {
                if (parts.length != 3 || !ID_SHAPE.matcher(parts[0]).matches()) {
                    corruptOk = false;
                    break;
                }
                byte[] raw = unhex(parts[2]);
                if (raw == null) {
                    corruptOk = false;
                    break;
                }
                String got = parseCaseRow(raw, caseIds);
                if (!parts[1].equals(got)) {
                    corruptOk = false;
                    break;
                }
            }
        }
        report.line("CORRUPT_REJECT", corruptOk && corrupt.size() == 8, "rows=" + corrupt.size());

        Class<?> bodyLength = Class.forName("com.devtunasoft.tuneros.core.BodyLength");
        Object instance = null;
        try {
            Field field = bodyLength.getField("INSTANCE");
            instance = field.get(null);
        } catch (NoSuchFieldException ignored) {
            instance = null;
        }
        Method lengthMethod = bodyLength.getMethod("expectedLength", long.class, String.class);
        Method verdictMethod = bodyLength.getMethod("verifyCleanEnd", long.class, long.class);

        boolean lengthsOk = true;
        for (ParsedCase row : cases) {
            Object actual = invoke(instance, lengthMethod, row.declared, row.encoding);
            if (!Long.valueOf(row.expectedLength).equals(actual)) {
                lengthsOk = false;
                break;
            }
        }
        report.line("EXPECT_LENGTH_TABLE", lengthsOk, "rows=" + cases.size());
        boolean verdictsOk = true;
        for (ParsedCase row : cases) {
            Object actual = invoke(instance, verdictMethod, row.expectedLength, row.copied);
            if (!row.verdict.equals(String.valueOf(actual))) {
                verdictsOk = false;
                break;
            }
        }
        report.line("VERDICT_TABLE", verdictsOk, "rows=" + cases.size());

        int cap = Integer.parseInt(dimensions.get("cap"));
        int cycles = Integer.parseInt(dimensions.get("cycles"));
        boolean capsOk = cases.size() == cap && cap == EXPECTED_CASES && corrupt.size() == 8
            && dimensions.get("cases").equals(String.valueOf(EXPECTED_CASES))
            && dimensions.get("version").equals("1") && cycles == 4;
        report.line("CAP_BOUNDS", capsOk, "cases=" + cases.size() + " cap=" + cap + " corrupt=" + corrupt.size());

        boolean byteIdentity = true;
        for (String name : DATA_FILES) {
            if (!Arrays.equals(expectedData.get(name), onDisk.get(name))) {
                byteIdentity = false;
                break;
            }
        }
        report.line("BYTE_IDENTITY", byteIdentity, "files=" + DATA_FILES.length);

        boolean manifestOk = manifestLines.length == 6 && manifestLines[5].isEmpty();
        for (int i = 1; i < 5 && manifestOk; i++) {
            String[] parts = manifestLines[i].split("\t", -1);
            if (parts.length != 2 || !sha256(onDisk.get(parts[1])).equals(parts[0])) manifestOk = false;
        }
        report.line("MANIFEST_MISMATCH", manifestOk, "files=" + (manifestLines.length - 2));

        for (int i = 0; i < cycles; i++) {
            for (ParsedCase row : cases) {
                invoke(instance, lengthMethod, row.declared, row.encoding);
                invoke(instance, verdictMethod, row.expectedLength, row.copied);
            }
        }
        long heapBefore = usedHeap();
        int threadsBefore = Thread.activeCount();
        for (int i = 0; i < cycles; i++) {
            for (ParsedCase row : cases) {
                invoke(instance, lengthMethod, row.declared, row.encoding);
                invoke(instance, verdictMethod, row.expectedLength, row.copied);
            }
        }
        long heapAfter = usedHeap();
        int threadsAfter = Thread.activeCount();
        long heapDrift = Math.max(0L, heapAfter - heapBefore);
        int threadDrift = Math.max(0, threadsAfter - threadsBefore);
        boolean bounded = heapDrift <= HEAP_LIMIT_BYTES && threadDrift <= THREAD_LIMIT;
        report.line("HEAP_THREAD", bounded, "heap_drift=" + heapDrift + " thread_drift=" + threadDrift);
        System.out.println("HEAP\tbefore=" + heapBefore + "\tafter=" + heapAfter
            + "\tdrift=" + heapDrift + "\tthreads_before=" + threadsBefore
            + "\tthreads_after=" + threadsAfter + "\tlimit=" + HEAP_LIMIT_BYTES);

        boolean pinOk = sha256(manifest).equals(PINNED_DATA_DIGEST);
        report.line("PINNED_DIGEST", pinOk, "pinned=" + (pinOk ? "match" : "mismatch"));
        finish(report);
        return report.failed == 0 ? 0 : 1;
    }

    private static void finish(Report report) {
        System.out.print(report.text);
        System.out.println("COUNTS\tchecks=" + report.checks + "\tpass=" + report.passed + "\tfail=" + report.failed);
        System.out.println("FIRST\t" + (report.first == null ? "NONE" : report.first));
        System.out.println("VERDICT\t" + (report.failed == 0 ? "PASS" : "FAIL"));
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.out.println("USAGE\tgenerate|verify <fixture-dir>");
                System.exit(2);
            }
            Path dir = Paths.get(args[1]);
            if (args[0].equals("generate")) {
                generate(dir);
                System.exit(0);
            }
            if (args[0].equals("verify")) {
                System.exit(verify(dir));
            }
            System.out.println("USAGE\tunknown-mode");
            System.exit(2);
        } catch (Exception e) {
            System.out.println("CHECK\tFAIL\tHARNESS\t" + e.getClass().getName());
            System.out.println("COUNTS\tchecks=1\tpass=0\tfail=1");
            System.out.println("FIRST\tHARNESS");
            System.out.println("VERDICT\tFAIL");
            System.exit(1);
        }
    }
}
