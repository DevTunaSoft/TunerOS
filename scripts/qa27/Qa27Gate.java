import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * QA-27: deterministic, zero-dependency host gate for the bounded EPG colour/theme
 * token registry. One source with generate | verify | mutate modes.
 *
 * Diagnostics carry check ids, counts and digests only. No fixture prose, URL,
 * credential, PIN, DRM key or recording payload is ever printed.
 */
public final class Qa27Gate {
    private static final long SEED = 271827L;
    private static final String HEADER = "QA27-EPG-TOKENS\tv1";
    private static final String COLHEAD = "role\targb\tnote";
    private static final int TOKEN_COUNT = 7;
    private static final int FIELD_COUNT = 3;
    private static final int MAX_FIELD_BYTES = 32;
    private static final int MAX_FIXTURE_BYTES = 2048;
    private static final int CONTRAST_MIN_MILLI = 4500;
    private static final int DISTINCTION_MIN = 12;
    private static final int MARKER_MIN = 24;
    private static final int BORDER_MIN = 16;
    private static final int CORRUPT_CASES = 8;
    private static final int PARSE_ROUNDS = 6;
    private static final int MAX_THREAD_DRIFT = 2;

    private static final String ROLE_BACKGROUND = "TIMELINE_BACKGROUND";
    private static final String ROLE_BORDER = "TIMELINE_BORDER";
    private static final String ROLE_TEXT = "TIMELINE_TEXT";
    private static final String ROLE_MARKER = "TIMELINE_NOW_MARKER";
    private static final String ROLE_NORMAL = "TIMELINE_CELL_NORMAL";
    private static final String ROLE_EMPTY = "TIMELINE_CELL_EMPTY";
    private static final String ROLE_FOCUSED = "TIMELINE_CELL_FOCUSED";

    /* Canonical token values are derived from the pinned production EPG surface. */
    private static final String[][] TOKENS = {
        {ROLE_BACKGROUND, "00000000", "scratch-transparent"},
        {ROLE_BORDER, "FF344963", "scratch-border"},
        {ROLE_TEXT, "FFFFFFFF", "scratch-text"},
        {ROLE_MARKER, "FFFF7440", "scratch-marker"},
        {ROLE_NORMAL, "FF1C3048", "scratch-normal"},
        {ROLE_EMPTY, "FF142030", "scratch-empty"},
        {ROLE_FOCUSED, "FF1F67BE", "scratch-focused"},
    };

    private static final String[][] CORRUPT = {
        {"corrupt-header.tsv", "HEADER_STRICT"},
        {"corrupt-fields.tsv", "ROW_BOUNDS"},
        {"corrupt-argb.tsv", "TOKEN_ARGB"},
        {"corrupt-role.tsv", "ROLE_SET"},
        {"corrupt-alpha.tsv", "ALPHA_SEMANTICS"},
        {"corrupt-contrast.tsv", "CONTRAST_BOUND"},
        {"corrupt-truncated.tsv", "TRUNCATED_INPUT"},
        {"corrupt-marker.tsv", "MARKER_DISTINCT"},
    };

    private static final Pattern ARGB = Pattern.compile("[0-9A-F]{8}");
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*:\\/\\/", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_WORD = Pattern.compile(
        "(?i)\\b(password|passwd|secret|credential|api[_-]?key)\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)\\b(pin|key)\\s*[:=]");
    private static final Pattern DRM = Pattern.compile("(?i)\\bdrm\\b");
    private static final Pattern FEED = Pattern.compile("(?i)\\b(m3u|xmltv)\\b");
    private static final Pattern RECORDING = Pattern.compile("(?i)\\brec(ord)?[_-]?[0-9]{3,}\\b");
    private static final Pattern[] PRIVACY_PATTERNS = {
        SCHEME, SECRET_WORD, ASSIGNMENT, DRM, FEED, RECORDING
    };

    private Qa27Gate() {}

    private static final class QaFail extends Exception {
        final String code;
        QaFail(String code) { super(code); this.code = code; }
    }

    private static final class Reporter {
        int pass;
        void ok(String id) {
            System.out.println("CHECK\t" + id + "\tPASS");
            pass++;
        }
        void fail(String id) throws QaFail {
            System.out.println("CHECK\t" + id + "\tFAIL");
            throw new QaFail(id);
        }
    }

    private static final class Token {
        final String role;
        final String argb;
        Token(String role, String argb) { this.role = role; this.argb = argb; }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE\tgenerate|verify|mutate\t<dir>\t[mode|mutant]");
            System.exit(2);
        }
        String cmd = args[0];
        Path dir = Paths.get(args[1]);
        try {
            if ("generate".equals(cmd)) {
                generate(dir);
                System.exit(0);
            } else if ("verify".equals(cmd)) {
                String mode = args.length >= 3 ? args[2] : "full";
                System.exit(verify(dir, mode));
            } else if ("mutate".equals(cmd)) {
                if (args.length < 3) { System.out.println("MUTANT\tNAME_REQUIRED"); System.exit(2); }
                mutate(dir, args[2]);
                System.exit(0);
            } else if ("corrupt".equals(cmd)) {
                System.exit(corruptCases(dir));
            } else {
                System.out.println("USAGE\tunknown-command");
                System.exit(2);
            }
        } catch (QaFail f) {
            System.out.println("FIRST\t" + f.code);
            System.out.println("ERROR\t" + f.getClass().getSimpleName());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("FIRST\tIO");
            System.out.println("ERROR\t" + e.getClass().getSimpleName());
            System.exit(1);
        }
    }

    /* ---------------------------- generation ---------------------------- */

    private static String tokensText() {
        StringBuilder b = new StringBuilder();
        b.append(HEADER).append('\n');
        b.append(COLHEAD).append('\n');
        for (String[] t : TOKENS) {
            b.append(t[0]).append('\t').append(t[1]).append('\t').append(t[2]).append('\n');
        }
        return b.toString();
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String replaceOnce(String text, String from, String to) {
        int first = text.indexOf(from);
        if (first < 0 || text.indexOf(from, first + from.length()) >= 0) {
            throw new IllegalStateException("needle-not-unique");
        }
        return text.substring(0, first) + to + text.substring(first + from.length());
    }

    private static String dimensionsText(byte[] canonical, int corruptCount) {
        StringBuilder b = new StringBuilder();
        b.append("qa27-fixture-v1\n");
        b.append("token-count=").append(TOKEN_COUNT).append('\n');
        b.append("field-count=").append(FIELD_COUNT).append('\n');
        b.append("max-field-bytes=").append(MAX_FIELD_BYTES).append('\n');
        b.append("max-fixture-bytes=").append(MAX_FIXTURE_BYTES).append('\n');
        b.append("contrast-min-milli=").append(CONTRAST_MIN_MILLI).append('\n');
        b.append("distinction-min=").append(DISTINCTION_MIN).append('\n');
        b.append("marker-min=").append(MARKER_MIN).append('\n');
        b.append("border-min=").append(BORDER_MIN).append('\n');
        b.append("corrupt-cases=").append(corruptCount).append('\n');
        b.append("seed=").append(SEED).append('\n');
        b.append("canonical-bytes=").append(canonical.length).append('\n');
        return b.toString();
    }

    private static String contractText() {
        return "QA-27 bounded EPG colour/theme token registry.\n"
            + "All roles, values and notes are invented scratch constants.\n"
            + "Seven roles: surface, outline, glyph, live marker and three cell states.\n"
            + "Values are eight upper-case hexadecimal digits in alpha-red-green-blue order.\n"
            + "The surface role carries zero alpha; every other role is opaque.\n"
            + "Glyph contrast, cell separation, marker separation and outline visibility\n"
            + "are bounded by the pinned dimensions. Corrupted variants are fail-closed.\n"
            + "No external address, access marker, personal code, protection material\n"
            + "or captured media appears in this fixture or in any diagnostic.\n";
    }

    private static Map<String, byte[]> fixtureFiles() {
        String canonical = tokensText();
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("tokens.tsv", bytes(canonical));
        out.put("dimensions.tsv", bytes(dimensionsText(bytes(canonical), CORRUPT.length)));
        out.put("contract.txt", bytes(contractText()));
        out.put("corrupt/corrupt-header.tsv",
            bytes(replaceOnce(canonical, HEADER, "QA27-EPG-TOKENS\tv2")));
        out.put("corrupt/corrupt-fields.tsv",
            bytes(replaceOnce(canonical, "scratch-transparent", "scratch-transparent\textra")));
        out.put("corrupt/corrupt-argb.tsv",
            bytes(replaceOnce(canonical, "FF1C3048", "FG1C3048")));
        out.put("corrupt/corrupt-role.tsv",
            bytes(replaceOnce(canonical, ROLE_EMPTY, ROLE_NORMAL)));
        out.put("corrupt/corrupt-alpha.tsv",
            bytes(replaceOnce(canonical, "00000000\tscratch-transparent",
                "FF000000\tscratch-transparent")));
        out.put("corrupt/corrupt-contrast.tsv",
            bytes(replaceOnce(canonical, "FF1F67BE", "FFE6E8EA")));
        out.put("corrupt/corrupt-truncated.tsv",
            bytes(canonical.substring(0, canonical.length() - 1)));
        out.put("corrupt/corrupt-marker.tsv",
            bytes(replaceOnce(canonical, "FFFF7440", "FFFFFFF0")));
        return out;
    }

    private static void generate(Path dir) throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        StringBuilder manifest = new StringBuilder();
        for (String name : new TreeMap<>(files).keySet()) {
            manifest.append(sha256(files.get(name))).append("  ").append(name).append('\n');
        }
        Files.write(dir.resolve("sha256.txt"), bytes(manifest.toString()));
        System.out.println("GENERATE\tfiles=" + (files.size() + 1)
            + "\tcanonical-bytes=" + files.get("tokens.tsv").length
            + "\tsha256=" + sha256(files.get("tokens.tsv")));
    }

    /* ---------------------------- parsing ------------------------------- */

    private static QaFail bad(Reporter r, String code) throws QaFail {
        if (r != null) {
            System.out.println("CHECK\t" + code + "\tFAIL");
        }
        return new QaFail(code);
    }

    private static List<Token> parse(byte[] raw, Reporter r) throws QaFail {
        String text = new String(raw, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) throw bad(r, "HEADER_STRICT");
        if (lines.length < 2 || !"".equals(lines[lines.length - 1])) throw bad(r, "TRUNCATED_INPUT");
        if (lines.length != TOKEN_COUNT + 3) throw bad(r, "ROW_BOUNDS");
        if (!COLHEAD.equals(lines[1])) throw bad(r, "HEADER_STRICT");
        if (r != null) r.ok("HEADER_STRICT");

        Map<String, Token> byRole = new LinkedHashMap<>();
        for (int i = 2; i < lines.length - 1; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length < FIELD_COUNT) throw bad(r, "TRUNCATED_INPUT");
            if (f.length != FIELD_COUNT) throw bad(r, "ROW_BOUNDS");
            for (String field : f) {
                if (bytes(field).length > MAX_FIELD_BYTES) throw bad(r, "ROW_BOUNDS");
            }
            if (!ARGB.matcher(f[1]).matches()) throw bad(r, "TOKEN_ARGB");
            if (!isKnownRole(f[0]) || byRole.containsKey(f[0])) throw bad(r, "ROLE_SET");
            byRole.put(f[0], new Token(f[0], f[1]));
        }
        if (r != null) r.ok("ROW_BOUNDS");
        if (r != null) r.ok("TOKEN_ARGB");
        if (r != null) r.ok("ROLE_SET");
        if (byRole.size() != TOKEN_COUNT) throw bad(r, "ROLE_SET");

        Token background = byRole.get(ROLE_BACKGROUND);
        if (background == null || !"00000000".equals(background.argb)) throw bad(r, "ALPHA_SEMANTICS");
        for (Token t : byRole.values()) {
            if (t == background) continue;
            if (!t.argb.startsWith("FF")) throw bad(r, "ALPHA_SEMANTICS");
        }
        if (r != null) r.ok("ALPHA_SEMANTICS");

        Token glyph = byRole.get(ROLE_TEXT);
        String[] fills = {ROLE_NORMAL, ROLE_EMPTY, ROLE_FOCUSED};
        for (String role : fills) {
            if (contrastMilli(glyph.argb, byRole.get(role).argb) < CONTRAST_MIN_MILLI) {
                throw bad(r, "CONTRAST_BOUND");
            }
        }
        if (r != null) r.ok("CONTRAST_BOUND");

        for (int a = 0; a < fills.length; a++) {
            for (int b = a + 1; b < fills.length; b++) {
                if (distance(byRole.get(fills[a]).argb, byRole.get(fills[b]).argb) < DISTINCTION_MIN) {
                    throw bad(r, "DISTINCTION_BOUND");
                }
            }
        }
        if (r != null) r.ok("DISTINCTION_BOUND");

        Token marker = byRole.get(ROLE_MARKER);
        for (String role : fills) {
            if (distance(marker.argb, byRole.get(role).argb) < MARKER_MIN) throw bad(r, "MARKER_DISTINCT");
        }
        if (distance(marker.argb, glyph.argb) < MARKER_MIN) throw bad(r, "MARKER_DISTINCT");
        if (r != null) r.ok("MARKER_DISTINCT");

        Token border = byRole.get(ROLE_BORDER);
        if (distance(border.argb, "00000000") < BORDER_MIN) throw bad(r, "BORDER_VISIBLE");
        for (String role : fills) {
            if (distance(border.argb, byRole.get(role).argb) < BORDER_MIN) throw bad(r, "BORDER_VISIBLE");
        }
        if (r != null) r.ok("BORDER_VISIBLE");
        return new ArrayList<>(byRole.values());
    }

    private static boolean isKnownRole(String role) {
        for (String[] t : TOKENS) if (t[0].equals(role)) return true;
        return false;
    }

    private static int channel(String argb, int shift) {
        return Integer.parseInt(argb.substring(shift, shift + 2), 16);
    }

    private static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double luminance(String argb) {
        return 0.2126 * lin(channel(argb, 2)) + 0.7152 * lin(channel(argb, 4)) + 0.0722 * lin(channel(argb, 6));
    }

    private static int contrastMilli(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (int) Math.floor(1000.0 * (hi + 0.05) / (lo + 0.05));
    }

    private static int distance(String a, String b) {
        int dr = channel(a, 2) - channel(b, 2);
        int dg = channel(a, 4) - channel(b, 4);
        int db = channel(a, 6) - channel(b, 6);
        return (int) Math.round(Math.sqrt(dr * dr + dg * dg + db * db));
    }

    /* ---------------------------- verification -------------------------- */

    private static int verify(Path dir, String mode) {
        Reporter r = new Reporter();
        String first = null;
        try {
            if ("full".equals(mode)) {
                byte[] canonical = bytes(tokensText());
                byte[] onDisk = Files.readAllBytes(dir.resolve("tokens.tsv"));
                if (!Arrays.equals(canonical, onDisk)) throw bad(r, "BYTE_IDENTITY");
                r.ok("BYTE_IDENTITY");
                checkManifest(dir, r);
            } else if (!"semantic".equals(mode)) {
                throw new QaFail("MODE");
            }
            byte[] raw = Files.readAllBytes(dir.resolve("tokens.tsv"));
            parse(raw, r);
            checkFailClosed(dir, r);
            checkPrivacy(dir, r);
            checkCaps(dir, r);
            checkThreads(raw, r);
            System.out.println("SUMMARY\tPASS=" + r.pass + "\tFAILED=0");
            return 0;
        } catch (QaFail f) {
            first = f.code;
            System.out.println("FIRST\t" + first);
            System.out.println("ERROR\t" + f.getClass().getSimpleName());
            System.out.println("SUMMARY\tPASS=" + r.pass + "\tFAILED=1");
            return 1;
        } catch (Exception e) {
            System.out.println("FIRST\tIO");
            System.out.println("ERROR\t" + e.getClass().getSimpleName());
            System.out.println("SUMMARY\tPASS=" + r.pass + "\tFAILED=1");
            return 1;
        }
    }

    private static void checkManifest(Path dir, Reporter r) throws Exception {
        byte[] manifest = Files.readAllBytes(dir.resolve("sha256.txt"));
        String[] lines = new String(manifest, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length < 2 || !"".equals(lines[lines.length - 1])) throw bad(r, "MANIFEST_SHA");
        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < lines.length - 1; i++) {
            String[] parts = lines[i].split("  ", 2);
            if (parts.length != 2) throw bad(r, "MANIFEST_SHA");
            expected.put(parts[1], parts[0]);
        }
        Map<String, byte[]> files = fixtureFiles();
        if (expected.size() != files.size()) throw bad(r, "MANIFEST_SHA");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!sha256(e.getValue()).equals(expected.get(e.getKey()))) throw bad(r, "MANIFEST_SHA");
        }
        r.ok("MANIFEST_SHA");
    }

    private static void checkFailClosed(Path dir, Reporter r) throws Exception {
        int matched = 0;
        for (String[] c : CORRUPT) {
            byte[] data = Files.readAllBytes(dir.resolve("corrupt").resolve(c[0]));
            String code = silentCode(data);
            if (c[1].equals(code)) matched++;
        }
        if (matched != CORRUPT_CASES) throw bad(r, "FAIL_CLOSED");
        r.ok("FAIL_CLOSED");
    }

    private static String silentCode(byte[] data) {
        try {
            parse(data, null);
            return null;
        } catch (QaFail f) {
            return f.code;
        }
    }

    private static int corruptCases(Path dir) {
        int ok = 0;
        for (String[] c : CORRUPT) {
            String actual = null;
            try {
                actual = silentCode(Files.readAllBytes(dir.resolve("corrupt").resolve(c[0])));
            } catch (Exception e) {
                actual = "IO";
            }
            boolean pass = c[1].equals(actual);
            System.out.println("CORRUPT\t" + c[0] + "\t" + c[1] + "\t" + actual
                + "\t" + (pass ? "PASS" : "FAIL"));
            if (pass) ok++;
        }
        System.out.println("SUMMARY\tCASES=" + CORRUPT.length + "\tPASS=" + ok);
        return ok == CORRUPT.length ? 0 : 1;
    }

    private static String deriveMarker() {
        long x = SEED ^ 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return "QA27M" + String.format(Locale.ROOT, "%08X", (int) (x & 0xFFFFFFFFL));
    }

    private static void checkPrivacy(Path dir, Reporter r) throws Exception {
        String marker = deriveMarker();
        List<Path> files = new ArrayList<>();
        try {
            Files.walk(dir).filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            throw bad(r, "PRIVACY_SCAN");
        }
        files.sort(Comparator.comparing(Path::toString));
        for (Path p : files) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (text.contains(marker)) throw bad(r, "PRIVACY_SCAN");
            for (Pattern pattern : PRIVACY_PATTERNS) {
                if (pattern.matcher(text).find()) throw bad(r, "PRIVACY_SCAN");
            }
        }
        r.ok("PRIVACY_SCAN");
    }

    private static void checkCaps(Path dir, Reporter r) throws Exception {
        byte[] dim = Files.readAllBytes(dir.resolve("dimensions.tsv"));
        String[] lines = new String(dim, StandardCharsets.UTF_8).split("\n");
        Map<String, String> kv = new HashMap<>();
        for (String line : lines) {
            int i = line.indexOf('=');
            if (i > 0) kv.put(line.substring(0, i), line.substring(i + 1));
        }
        boolean ok = kv.size() == 11
            && Integer.parseInt(kv.get("token-count")) == TOKEN_COUNT
            && Integer.parseInt(kv.get("field-count")) == FIELD_COUNT
            && Integer.parseInt(kv.get("corrupt-cases")) == CORRUPT_CASES
            && Integer.parseInt(kv.get("canonical-bytes")) == bytes(tokensText()).length
            && Integer.parseInt(kv.get("max-fixture-bytes")) >= bytes(tokensText()).length
            && Long.parseLong(kv.get("seed")) == SEED;
        if (!ok) throw bad(r, "CAPS_BIND");
        r.ok("CAPS_BIND");
    }

    private static void checkThreads(byte[] raw, Reporter r) throws QaFail {
        int before = Thread.activeCount();
        for (int i = 0; i < PARSE_ROUNDS; i++) {
            try {
                parse(raw, null);
            } catch (QaFail f) {
                throw bad(r, "THREAD_BOUND");
            }
        }
        int after = Thread.activeCount();
        if (after - before > MAX_THREAD_DRIFT) throw bad(r, "THREAD_BOUND");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("QA27-")) throw bad(r, "THREAD_BOUND");
        }
        r.ok("THREAD_BOUND");
    }

    /* ---------------------------- mutation ------------------------------ */

    private static void mutate(Path dir, String name) throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        String canonical = new String(files.get("tokens.tsv"), StandardCharsets.UTF_8);
        String changed = canonical;
        boolean remanifest = true;
        switch (name) {
            case "byteflip":
                changed = replaceOnce(canonical, "scratch-transparent", "scratch-transparenT");
                remanifest = false;
                break;
            case "header":
                changed = replaceOnce(canonical, HEADER, "QA27-EPG-TOKENS\tv2");
                break;
            case "rowbounds":
                changed = replaceOnce(canonical, "scratch-transparent", "scratch-transparent\textra");
                break;
            case "argb":
                changed = replaceOnce(canonical, "FF1C3048", "FG1C3048");
                break;
            case "roleset":
                changed = replaceOnce(canonical, ROLE_EMPTY, ROLE_NORMAL);
                break;
            case "alpha":
                changed = replaceOnce(canonical, "00000000\tscratch-transparent",
                    "FF000000\tscratch-transparent");
                break;
            case "contrast":
                changed = replaceOnce(canonical, "FF1F67BE", "FFE6E8EA");
                break;
            case "truncated":
                changed = canonical.substring(0, canonical.length() - 1);
                break;
            case "marker":
                changed = replaceOnce(canonical, "FFFF7440", "FFFFFFF0");
                break;
            case "leak":
                changed = replaceOnce(canonical, "scratch-border", "scratch-border-" + deriveMarker());
                break;
            default:
                System.out.println("MUTANT\tUNKNOWN");
                System.exit(2);
        }
        files.put("tokens.tsv", bytes(changed));
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        if (remanifest) {
            StringBuilder m = new StringBuilder();
            for (String key : new TreeMap<>(files).keySet()) {
                m.append(sha256(files.get(key))).append("  ").append(key).append('\n');
            }
            Files.write(dir.resolve("sha256.txt"), bytes(m.toString()));
        }
        System.out.println("MUTANT\t" + name + "\tOK");
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder b = new StringBuilder();
        for (byte value : digest) b.append(String.format("%02x", value & 0xFF));
        return b.toString();
    }
}
