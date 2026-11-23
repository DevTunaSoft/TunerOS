import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA-25 host gate: bounded DVR recording-catalog paging, immutable-ID and protection admission.
 * Zero dependencies. Fixture bytes are generated from constants in memory; verify never writes
 * into the repository. Diagnostics carry check ids, counts and digests only.
 */
public final class Qa25Gate {
    // Deterministic constants. Keep in step with the Kotlin mirror in app/src/androidTest.
    static final String SEED_HEX = "51413235";
    static final int N_RECORDS = 240;
    static final int N_PAGES = 8;
    static final int N_CORRUPT = 8;
    static final int WINDOW_SLOTS = 24;
    static final int LIMIT_CAP = 500;
    static final int MAX_ROW_CHARS = 220;
    static final long BASE_EPOCH = 1_700_000_000L;
    static final String[] STATES = {"COMPLETE", "PARTIAL", "RECORDING", "FAILED"};
    static final String HEADER =
        "id\ttitle_code\tchannel_id\tstate\tstarted_epoch\tstopped_epoch\tbytes_written\tlocation_code\tfailure_code\tprotected";
    static final String[] DATA_FILES = {"catalog.tsv", "pages.tsv", "corrupt.tsv", "dimensions.tsv", "contract.txt"};
    static final String MANIFEST = "manifest.sha256";

    // Set after the first deterministic generation; the generator does not read it.
    static final String PINNED_MANIFEST_SHA256 = "7a082457c3f7d2015ad228376505b3447f97c57e565e90560e9cad0627b19f54";
    static final String PINNED_CATALOG_SHA256 = "2567ef641135cc4f400d58955f1de209cb156be4aff4f75591256bd3ea3545cd";

    static final List<String> report = new ArrayList<>();
    static String first = "NONE";
    static int passed = 0;
    static int failed = 0;
    static boolean semantic = false;

    static final Pattern URL = Pattern.compile("(?i)(https?|ftp|ws|wss)://");
    static final Pattern CREDENTIAL = Pattern.compile("(?i)(password|passwd|api[_-]?key|access[_-]?token|bearer|authorization|secret|credential)[ \\t]*[=:]");
    static final Pattern PIN = Pattern.compile("(?i)\\bpin[ \\t]*[=:]\\s*\\d{4,}");
    static final Pattern DRM = Pattern.compile("(?i)\\b(drm|widevine|fairplay|playready|keyid|kid|license[_-]?key)[ \\t]*[=:]");
    static final Pattern LONG_HEX_KEY = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");
    static final Pattern PRIVATE_PATH = Pattern.compile("(?i)([a-z]:\\\\users\\\\|/home/|/sdcard/|content://|file://)");

    public static void main(String[] args) {
        if (args.length == 0) { die("usage: generate|verify|verify-semantic|mutate <dir> [dst] [case]"); }
        String mode = args[0];
        try {
            if (mode.equals("generate")) {
                if (args.length < 2) die("generate needs a directory");
                generate(Paths.get(args[1]));
            } else if (mode.equals("verify") || mode.equals("verify-semantic")) {
                if (args.length < 2) die("verify needs a directory");
                semantic = mode.equals("verify-semantic");
                verify(Paths.get(args[1]));
            } else if (mode.equals("mutate")) {
                if (args.length < 4) die("mutate needs src dst case");
                mutate(Paths.get(args[1]), Paths.get(args[2]), args[3]);
            } else {
                die("unknown mode: " + mode);
            }
        } catch (Throwable t) {
            emit("CHECK", "FAIL", "HARNESS");
            emit("FIRST", "HARNESS", "");
            System.out.println("SUMMARY\tpass=" + passed + "\tfail=" + (failed + 1) + "\tfirst=HARNESS\tclass=" + t.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static void die(String message) {
        System.out.println("USAGE\t" + message.replaceAll("[^a-zA-Z0-9 _|<>\\[\\]-]", ""));
        System.exit(2);
    }

    // ---------------- generation ----------------

    static String pad(int value, int width) {
        String s = Integer.toString(value);
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < width; i++) b.append('0');
        return b.append(s).toString();
    }

    static String orDash(String value) { return value == null ? "-" : value; }

    static long startedOf(int i) { return BASE_EPOCH + (((i * 97) % N_RECORDS) * 37L); }

    static String stateOf(int i) { return STATES[(i / 7) % STATES.length]; }

    static String protectedOf(int i) { return (i % 11 == 0) ? "1" : "0"; }

    static String rowOf(int i) {
        long started = startedOf(i);
        String state = stateOf(i);
        String stopped = state.equals("RECORDING") ? "-" : Long.toString(started + 1800L + (i % 13));
        String bytes = (i == 17) ? "0" : Long.toString(4096L * (i + 1L));
        String location = (state.equals("RECORDING") || i % 9 == 0) ? "-" : "loc-" + pad((i * 17) % 53, 5);
        String failure = state.equals("FAILED") ? "fail-" + (i % 4) : "-";
        return "rec-" + pad(i, 5)
            + "\ttitle-" + pad((i * 7 + 11) % 97, 5)
            + "\tc-" + pad((i * 13 + 3) % 61, 5)
            + "\t" + state + "\t" + started + "\t" + stopped + "\t" + bytes
            + "\t" + location + "\t" + failure + "\t" + protectedOf(i);
    }

    static byte[] catalogBytes() {
        StringBuilder b = new StringBuilder();
        b.append(HEADER).append('\n');
        for (int i = 0; i < N_RECORDS; i++) b.append(rowOf(i)).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final int[] PAGE_LIMITS = {1, 5, WINDOW_SLOTS, 100, LIMIT_CAP, 3, 7, 12};
    static final int[] PAGE_OFFSETS = {0, 0, 0, 0, 0, 17, 50, 235};

    static List<String> sortedIds(byte[] catalog) {
        String[] lines = text(catalog).split("\n");
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] f = lines[i].split("\t", -1);
            rows.add(f);
        }
        rows.sort(Comparator.comparingLong((String[] f) -> Long.parseLong(f[4])).reversed()
            .thenComparing(f -> f[0]));
        List<String> ids = new ArrayList<>();
        for (String[] f : rows) ids.add(f[0]);
        return ids;
    }

    static byte[] pagesBytes(byte[] catalog) {
        List<String> ids = sortedIds(catalog);
        StringBuilder b = new StringBuilder("page_id\tlimit\toffset\texpected_ids\n");
        for (int p = 0; p < N_PAGES; p++) {
            int limit = PAGE_LIMITS[p];
            int offset = PAGE_OFFSETS[p];
            StringBuilder joined = new StringBuilder();
            for (int k = offset; k < Math.min(ids.size(), offset + limit); k++) {
                if (joined.length() > 0) joined.append(',');
                joined.append(ids.get(k));
            }
            b.append("page-").append(pad(p, 3)).append('\t').append(limit).append('\t')
             .append(offset).append('\t').append(joined).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t"); }
    static String unesc(String s) { return s.replace("\\t", "\t").replace("\\\\", "\\"); }

    static byte[] corruptBytes() {
        String good = rowOf(3);
        String[] fields = good.split("\t", -1);
        StringBuilder badFields = new StringBuilder();
        for (int i = 0; i < fields.length - 1; i++) {
            if (i > 0) badFields.append('\t');
            badFields.append(fields[i]);
        }
        String[] epochBad = fields.clone(); epochBad[4] = "xx";
        String[] bytesBad = fields.clone(); bytesBad[6] = "-1";
        String[] protBad = fields.clone(); protBad[9] = "7";
        String[] stateBad = fields.clone(); stateBad[3] = "UNKNOWN_STATE";
        String[] idBad = fields.clone(); idBad[0] = "rec-00003 extra";
        String headerBad = HEADER.substring(0, HEADER.length() - 1);
        String[] cases = {
            "C01_HEADER\theader\t" + esc(headerBad),
            "C02_FIELDS\trow\t" + esc(badFields.toString()),
            "C03_EPOCH\trow\t" + esc(String.join("\t", epochBad)),
            "C04_BYTES\trow\t" + esc(String.join("\t", bytesBad)),
            "C05_PROTECTED\trow\t" + esc(String.join("\t", protBad)),
            "C06_TRUNCATED\trow\t" + esc(good.substring(0, good.length() * 3 / 5)),
            "C07_STATE\trow\t" + esc(String.join("\t", stateBad)),
            "C08_ID\trow\t" + esc(String.join("\t", idBad)),
        };
        StringBuilder b = new StringBuilder("case\tkind\tpayload\n");
        for (String c : cases) b.append(c).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] dimensionsBytes(String catalogSha) {
        return ("SEED_HEX=" + SEED_HEX + "\n"
            + "RECORDS=" + N_RECORDS + "\n"
            + "PAGES=" + N_PAGES + "\n"
            + "CORRUPT_CASES=" + N_CORRUPT + "\n"
            + "WINDOW_SLOTS=" + WINDOW_SLOTS + "\n"
            + "LIMIT_CAP=" + LIMIT_CAP + "\n"
            + "MAX_ROW_CHARS=" + MAX_ROW_CHARS + "\n"
            + "MIRROR_SHA256=" + catalogSha + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] contractBytes() {
        return ("QA-25 scratch fixture for bounded DVR recording catalog paging.\n"
            + "All ids, codes and epoch values are invented constants derived from a fixed seed.\n"
            + "catalog.tsv carries ten tab separated columns; pages.tsv pins bounded query results.\n"
            + "corrupt.tsv stores escaped malformed rows with their named strict rejection cases.\n"
            + "No feed address, access marker, personal code, media content or real path is stored.\n"
            + "Diagnostics name checks, counts and digests only.\n").getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, byte[]> buildAll() {
        byte[] catalog = catalogBytes();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("catalog.tsv", catalog);
        files.put("pages.tsv", pagesBytes(catalog));
        files.put("corrupt.tsv", corruptBytes());
        files.put("dimensions.tsv", dimensionsBytes(sha(catalog)));
        files.put("contract.txt", contractBytes());
        return files;
    }

    static byte[] manifestBytes(Map<String, byte[]> files) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            b.append(sha(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, byte[]> files = buildAll();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
        Files.write(dir.resolve(MANIFEST), manifestBytes(files));
        System.out.println("GENERATED\tfiles=" + files.size() + "\tmanifest_sha256=" + sha(manifestBytes(files))
            + "\tcatalog_sha256=" + sha(files.get("catalog.tsv")) + "\tderived_protected=" + protectedCount()
            + "\tderived_zero_bytes=1\tderived_states=4\tderived_pages=" + N_PAGES);
    }

    static int protectedCount() {
        int n = 0;
        for (int i = 0; i < N_RECORDS; i++) if (protectedOf(i).equals("1")) n++;
        return n;
    }

    // ---------------- verification ----------------

    static void verify(Path dir) throws IOException, InterruptedException {
        for (String name : DATA_FILES) {
            if (!Files.isRegularFile(dir.resolve(name))) { emit("CHECK", "FAIL", "FIXTURE_PRESENT"); fail("FIXTURE_PRESENT"); }
        }
        if (failed > 0) { finish(); return; }

        Map<String, byte[]> committed = new LinkedHashMap<>();
        for (String name : DATA_FILES) committed.put(name, Files.readAllBytes(dir.resolve(name)));
        byte[] manifest = Files.readAllBytes(dir.resolve(MANIFEST));

        // Structural checks first only in full mode; semantic mutant mode skips them so named guards run.
        if (!semantic) {
            Map<String, byte[]> fresh = buildAll();
            boolean same = true;
            for (String name : DATA_FILES) same &= Arrays.equals(fresh.get(name), committed.get(name));
            check("BYTE_IDENTITY", same);
            check("MANIFEST_PIN", sha(manifest).equals(PINNED_MANIFEST_SHA256));
        }

        Map<String, String> manifestMap = parseManifest(manifest);
        String catalogText = text(committed.get("catalog.tsv"));
        String[] catalogLines = catalogText.split("\n");

        check("HEADER_STRICT", catalogLines.length > 0 && catalogLines[0].equals(HEADER));
        List<String[]> rows = new ArrayList<>();
        boolean rowBounds = catalogLines.length == N_RECORDS + 1;
        for (int i = 1; i < catalogLines.length && rowBounds; i++) {
            if (catalogLines[i].isEmpty()) continue;
            if (catalogLines[i].length() > MAX_ROW_CHARS) { rowBounds = false; break; }
            String[] f = catalogLines[i].split("\t", -1);
            if (f.length != 10) { rowBounds = false; break; }
            rows.add(f);
        }
        check("ROW_BOUNDS", rowBounds);

        CorruptResult corrupt = checkCorrupt(committed.get("corrupt.tsv"));
        check("FAIL_CLOSED", corrupt.rejected == N_CORRUPT && corrupt.laxAccepts >= 1);

        Set<String> ids = new HashSet<>();
        boolean unique = rows.size() == N_RECORDS;
        for (String[] f : rows) {
            if (!f[0].matches("rec-\\d{5}") || !ids.add(f[0])) unique = false;
        }
        check("ID_IMMUTABLE", unique);

        List<String[]> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong((String[] f) -> Long.parseLong(f[4])).reversed()
            .thenComparing(f -> f[0]));
        List<String> sortedIds = new ArrayList<>();
        for (String[] f : sorted) sortedIds.add(f[0]);
        List<String[]> pages = parseColumns(committed.get("pages.tsv"), 4);
        boolean order = pages.size() == N_PAGES;
        for (String[] p : pages) {
            int limit = Integer.parseInt(p[1]);
            int offset = Integer.parseInt(p[2]);
            StringBuilder joined = new StringBuilder();
            for (int k = offset; k < Math.min(sortedIds.size(), offset + limit); k++) {
                if (joined.length() > 0) joined.append(',');
                joined.append(sortedIds.get(k));
            }
            if (!p[3].equals(joined.toString())) order = false;
        }
        check("RECENT_ORDER", order);

        boolean bounds = pages.size() == N_PAGES;
        int largestLimit = 0;
        for (String[] p : pages) {
            int limit = Integer.parseInt(p[1]);
            int offset = Integer.parseInt(p[2]);
            largestLimit = Math.max(largestLimit, limit);
            int expectedSize = Math.min(limit, Math.max(0, sortedIds.size() - offset));
            int actualSize = p[3].isEmpty() ? 0 : p[3].split(",", -1).length;
            if (limit < 1 || limit > LIMIT_CAP || actualSize != expectedSize) bounds = false;
        }
        check("PAGE_LIMIT_BOUNDS", bounds && largestLimit == LIMIT_CAP);

        int prot = 0;
        for (String[] f : rows) if (f[9].equals("1")) prot++;
        boolean protParsed = prot == protectedCount();
        for (String[] f : rows) if (!f[9].equals("0") && !f[9].equals("1")) protParsed = false;
        check("PROTECTION_PRESERVED", protParsed);

        boolean privacy = privacyScan(committed, manifest, report.toString());
        check("PRIVACY_SCAN", privacy);

        if (semantic) {
            boolean consistent = manifestMap.size() == DATA_FILES.length;
            for (String name : DATA_FILES) {
                if (!sha(committed.get(name)).equals(manifestMap.get(name))) consistent = false;
            }
            check("MANIFEST_CONSISTENCY", consistent);
        }

        check("WINDOW_RECYCLE", windowBound(sortedIds));
        check("DIMENSION_CAP", dimensionCap(committed.get("dimensions.tsv"), rows, pages, corrupt));
        check("MIRROR_DIGEST", sha(committed.get("catalog.tsv")).equals(PINNED_CATALOG_SHA256)
            && sha(committed.get("catalog.tsv")).equals(dimensionsValue(committed.get("dimensions.tsv"), "MIRROR_SHA256")));

        HeapResult heap = measureHeap(dir);
        check("HEAP_DRIFT", heap.ok);
        check("THREAD_BOUND", heap.threadOk);

        finish();
    }

    static void check(String id, boolean ok) {
        if (ok) { passed++; emit("CHECK", "PASS", id); }
        else { failed++; if (first.equals("NONE")) first = id; emit("CHECK", "FAIL", id); }
    }

    static void fail(String id) { failed++; if (first.equals("NONE")) first = id; }

    static void emit(String kind, String status, String id) {
        String line = kind + "\t" + status + "\t" + id;
        report.add(line);
        System.out.println(line);
    }

    static void finish() {
        emit("FIRST", first, "");
        System.out.println("SUMMARY\tpass=" + passed + "\tfail=" + failed + "\tfirst=" + first + "\tmode="
            + (semantic ? "semantic" : "full"));
        System.exit(failed == 0 ? 0 : 1);
    }

    static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    static String sha(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static Map<String, String> parseManifest(byte[] bytes) {
        Map<String, String> map = new HashMap<>();
        for (String line : text(bytes).split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.trim().split("\\s+", -1);
            if (p.length == 2) map.put(p[1], p[0]);
        }
        return map;
    }

    static String dimensionsValue(byte[] bytes, String key) {
        for (String line : text(bytes).split("\n")) {
            int i = line.indexOf('=');
            if (i > 0 && line.substring(0, i).equals(key)) return line.substring(i + 1).trim();
        }
        return "";
    }

    static List<String[]> parseColumns(byte[] bytes, int columns) {
        List<String[]> out = new ArrayList<>();
        String[] lines = text(bytes).split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] f = lines[i].split("\t", -1);
            if (f.length == columns) out.add(f);
        }
        return out;
    }

    // ---------------- strict corrupt handling ----------------

    static final class QaReject extends Exception {
        final String code;
        QaReject(String code) { this.code = code; }
    }

    static void strictRow(String line) throws QaReject {
        if (line.length() > MAX_ROW_CHARS) throw new QaReject("ROW_BOUNDS");
        String[] f = line.split("\t", -1);
        if (f.length != 10) throw new QaReject("ROW_BOUNDS");
        if (!f[0].matches("rec-\\d{5}")) throw new QaReject("BAD_ID");
        boolean known = false;
        for (String s : STATES) if (s.equals(f[3])) known = true;
        if (!known) throw new QaReject("BAD_STATE");
        try { Long.parseLong(f[4]); } catch (NumberFormatException e) { throw new QaReject("BAD_EPOCH"); }
        try { if (Long.parseLong(f[6]) < 0) throw new QaReject("BAD_BYTES"); }
        catch (NumberFormatException e) { throw new QaReject("BAD_BYTES"); }
        if (!f[9].equals("0") && !f[9].equals("1")) throw new QaReject("BAD_PROTECTED");
    }

    static boolean laxRow(String line) {
        String[] f = line.split("\t", -1);
        return f.length >= 8 && !f[0].isEmpty();
    }

    static final class CorruptResult { int rejected; int laxAccepts; }

    static CorruptResult checkCorrupt(byte[] bytes) {
        CorruptResult r = new CorruptResult();
        List<String[]> cases = parseColumns(bytes, 3);
        if (cases.size() != N_CORRUPT) return r;
        for (String[] c : cases) {
            String payload = unesc(c[2]);
            try {
                if (c[1].equals("header")) {
                    if (!payload.equals(HEADER)) throw new QaReject("HEADER_STRICT");
                } else {
                    strictRow(payload);
                }
            } catch (QaReject e) {
                r.rejected++;
            }
            if (laxRow(payload)) r.laxAccepts++;
        }
        return r;
    }

    // ---------------- bounded window, dimensions, heap ----------------

    static boolean windowBound(List<String> ids) {
        int visible = Math.min(ids.size(), 120);
        boolean ok = visible > WINDOW_SLOTS;
        for (int top = 0; top <= visible - WINDOW_SLOTS; top += 7) {
            Set<Integer> touched = new HashSet<>();
            for (int row = top; row < Math.min(visible, top + WINDOW_SLOTS); row++) {
                int slot = row % WINDOW_SLOTS;
                if (slot < 0 || slot >= WINDOW_SLOTS) return false;
                touched.add(slot);
            }
            if (touched.size() < WINDOW_SLOTS / 2) ok = false;
        }
        return ok;
    }

    static boolean dimensionCap(byte[] dims, List<String[]> rows, List<String[]> pages, CorruptResult corrupt) {
        String records = dimensionsValue(dims, "RECORDS");
        String pageCount = dimensionsValue(dims, "PAGES");
        String corruptCount = dimensionsValue(dims, "CORRUPT_CASES");
        String slots = dimensionsValue(dims, "WINDOW_SLOTS");
        String cap = dimensionsValue(dims, "LIMIT_CAP");
        String maxChars = dimensionsValue(dims, "MAX_ROW_CHARS");
        boolean ok = records.equals(Integer.toString(N_RECORDS))
            && pageCount.equals(Integer.toString(N_PAGES))
            && corruptCount.equals(Integer.toString(N_CORRUPT))
            && slots.equals(Integer.toString(WINDOW_SLOTS))
            && cap.equals(Integer.toString(LIMIT_CAP))
            && maxChars.equals(Integer.toString(MAX_ROW_CHARS))
            && rows.size() == N_RECORDS && pages.size() == N_PAGES
            && corrupt.rejected == N_CORRUPT && protectedCount() == 22;
        Set<String> states = new HashSet<>();
        for (String[] f : rows) states.add(f[3]);
        return ok && states.size() == 4;
    }

    static final class HeapResult { boolean ok = true; boolean threadOk = true; }

    static HeapResult measureHeap(Path dir) throws IOException, InterruptedException {
        final byte[] fixture = Files.readAllBytes(dir.resolve("catalog.tsv"));
        HeapResult r = new HeapResult();
        long baseHeap = -1;
        int baseThreads = -1;
        for (int cycle = 0; cycle < 6; cycle++) {
            final int n = cycle;
            final int[] during = new int[2];
            Thread worker = new Thread(() -> {
                during[0] = Thread.activeCount();
                String[] lines = text(fixture).split("\n");
                int count = 0;
                for (int i = 1; i < lines.length; i++) if (!lines[i].isEmpty()) count++;
                during[1] = count;
            }, "QA25-cycle-" + n);
            worker.start();
            int justAfterStart = Thread.activeCount();
            worker.join(2000);
            int afterJoin = Thread.activeCount();
            if (during[1] != N_RECORDS || afterJoin > justAfterStart) r.threadOk = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().startsWith("QA25-cycle-")) r.threadOk = false;
            }
            worker = null;
            System.gc();
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            int threads = Thread.activeCount();
            if (cycle == 0) { baseHeap = used; baseThreads = threads; }
            else {
                if (Math.abs(used - baseHeap) > 4L * 1024 * 1024) r.ok = false;
                if (Math.abs(threads - baseThreads) > 2) r.threadOk = false;
            }
        }
        return r;
    }

    // ---------------- privacy ----------------

    static boolean privacyScan(Map<String, byte[]> committed, byte[] manifest, String diagnostics) {
        StringBuilder all = new StringBuilder(diagnostics);
        for (byte[] b : committed.values()) all.append(text(b)).append('\n');
        String raw = all.toString();
        if (raw.contains(secret())) return false;
        StringBuilder withoutDigests = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.contains("SHA256=")) continue;
            withoutDigests.append(line).append('\n');
        }
        String s = withoutDigests.toString();
        return !URL.matcher(s).find()
            && !CREDENTIAL.matcher(s).find()
            && !PIN.matcher(s).find()
            && !DRM.matcher(s).find()
            && !LONG_HEX_KEY.matcher(s).find()
            && !PRIVATE_PATH.matcher(s).find();
    }

    static String secret() {
        long v = Long.parseUnsignedLong(SEED_HEX, 16) ^ 0x9E3779B97F4A7C15L;
        StringBuilder b = new StringBuilder("QA25-");
        for (int i = 0; i < 8; i++) {
            v = v * 6364136223846793005L + 1442695040888963407L;
            b.append(Character.forDigit((int) ((v >>> 56) & 15), 16));
        }
        return b.toString();
    }

    // ---------------- mutation ----------------

    static void mutate(Path src, Path dst, String kind) throws IOException {
        Files.createDirectories(dst);
        for (String name : DATA_FILES) Files.copy(src.resolve(name), dst.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(src.resolve(MANIFEST), dst.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        boolean remanifest = !kind.equals("byte_flip");
        Path catalog = dst.resolve("catalog.tsv");
        Path pages = dst.resolve("pages.tsv");
        Path contract = dst.resolve("contract.txt");
        if (kind.equals("byte_flip")) {
            byte[] b = Files.readAllBytes(catalog);
            int at = text(b).indexOf('\n') + 12; // inside the first data row, past the id
            b[at] = (byte) ((b[at] == 'x') ? 'y' : 'x');
            Files.write(catalog, b);
        } else if (kind.equals("header_typo")) {
            byte[] b = Files.readAllBytes(catalog);
            String s = text(b);
            s = s.replace(HEADER, HEADER.substring(0, HEADER.length() - 1));
            Files.write(catalog, s.getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("truncated_row")) {
            byte[] b = Files.readAllBytes(catalog);
            String s = text(b);
            int last = s.lastIndexOf("rec-");
            String prefix = s.substring(0, last);
            String row = s.substring(last).trim();
            Files.write(catalog, (prefix + row.substring(0, row.length() / 2) + "\n").getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("limit_over_cap")) {
            byte[] b = Files.readAllBytes(pages);
            String s = text(b);
            s = s.replace("page-004\t500\t0\t", "page-004\t501\t0\t");
            Files.write(pages, s.getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("id_reuse")) {
            byte[] b = Files.readAllBytes(catalog);
            String s = text(b);
            String[] lines = s.split("\n");
            String[] collision = lines[2].split("\t", -1);
            collision[0] = lines[1].split("\t", -1)[0];
            lines[2] = String.join("\t", collision);
            Files.write(catalog, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("protection_flip")) {
            byte[] b = Files.readAllBytes(catalog);
            String target = rowOf(0);
            String s = text(b).replace(target, target.substring(0, target.length() - 1) + "0");
            Files.write(catalog, s.getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("order_drift")) {
            byte[] b = Files.readAllBytes(catalog);
            String[] lines = text(b).split("\n");
            swapEpoch(lines, 1, 40);
            Files.write(catalog, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("privacy_leak")) {
            byte[] b = Files.readAllBytes(contract);
            Files.write(contract, (text(b) + "scratch marker " + secret() + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("unknown mutant");
        }
        if (remanifest) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String name : DATA_FILES) files.put(name, Files.readAllBytes(dst.resolve(name)));
            Files.write(dst.resolve(MANIFEST), manifestBytes(files));
        }
        System.out.println("MUTATED\tcase=" + kind + "\tremanifest=" + remanifest);
    }

    static void swapEpoch(String[] lines, int a, int b) {
        String[] fa = lines[a].split("\t", -1);
        String[] fb = lines[b].split("\t", -1);
        String t = fa[4]; fa[4] = fb[4]; fb[4] = t;
        lines[a] = String.join("\t", fa);
        lines[b] = String.join("\t", fb);
    }
}
