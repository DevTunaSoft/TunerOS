import com.devtunasoft.tuneros.core.SeriesRule;
import com.devtunasoft.tuneros.core.SeriesRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QA-65 host gate. Zero dependencies beyond the JVM, the Kotlin stdlib and the
 * real compiled core SeriesRules/Models classes.
 *
 * Slice: canonical series-rule title normalisation and rule-id admission over the
 * pure-Kotlin production contract. Sibling QA gates own DVR series expansion
 * (QA-08), series-rule replacement (QA-37) and backup rule refusal (QA-38);
 * none calls titleKeyOf / ruleFor / titleMatches / SeriesRules.plan.
 *
 * Modes: generate <dir> | verify <dir>
 */
public final class Qa65Gate {
    private static final String SCHEMA = "qa65-canon-v1";
    private static final long SEED = 20261124L;
    private static final int TITLE_CAP = 48;
    private static final int RULE_CAP = 16;
    private static final int MAX_TITLE_LEN = 128;
    private static final int MARKER_LEN = 8;

    private static final int NULL_N = 8, LEAD_N = 10, SPACE_N = 12, CASE_N = 12, IDEM_N = 6;

    /* Filled after the first deterministic generation; byte identity covers sha256.txt. */
    private static final String PINNED_CASES_SHA256 = "539c244b1f89f99823a414438fe96942f4dafa426d1a09e649ab56a852cee9c3";

    private static final String[] NULL_TITLES = {"!!!", "---", "...", "   ", "!-!", "_._", ", ,", "..!"};
    private static final String[] LEAD_PREFIX = {"  ", "!!", "..", "--", "  ", "!!", "..", "--", "  ", "!!"};
    private static final String[] LEAD_BODY = {"ab12", "cd34", "ef56", "gh78", "ij90", "kl", "mn", "op", "qr", "st"};
    private static final String[] SPACE_TITLES = {
        "ab  cd", "ab--cd", "ab..cd", "ab!!cd", "ab__cd", "ab,,cd",
        "xy 12", "xy--34", "xy..56", "xy!!78", "xy__90", "xy,,ab"
    };
    private static final String[] SPACE_KEYS = {
        "ab cd", "ab cd", "ab cd", "ab cd", "ab cd", "ab cd",
        "xy 12", "xy 34", "xy 56", "xy 78", "xy 90", "xy ab"
    };
    private static final String[] CASE_TITLES = {
        "ABC", "XYZ123", "HELLO", "WORLD9", "MIXEDCase", "UPPERlower",
        "DIGIT42", "ALPHA7", "BETA8", "\u00c9COLE", "\u00c4PFEL", "OMEGA4"
    };
    private static final String[] CASE_KEYS = {
        "abc", "xyz123", "hello", "world9", "mixedcase", "upperlower",
        "digit42", "alpha7", "beta8", "\u00e9cole", "\u00e4pfel", "omega4"
    };
    private static final String[] IDEM_TITLES = {"ab cd", "xy 12", "the news", "one two three", "a1 b2", "z9 y8"};

    private static final List<String> lines = new ArrayList<String>();
    private static String first = null;
    private static int passCount = 0, failCount = 0;
    private static int startThreads = 0;

    public static void main(String[] args) {
        if (args.length < 2) { System.out.print("USAGE\tgenerate|verify\t<dir>\n"); System.exit(2); }
        String mode = args[0];
        Path dir = Paths.get(args[1]);
        try {
            if ("generate".equals(mode)) {
                byte[] dims = dimensions();
                byte[] cases = cases();
                byte[] manifest = manifest(dims, cases);
                Files.createDirectories(dir);
                Files.write(dir.resolve("dimensions.tsv"), dims);
                Files.write(dir.resolve("cases.tsv"), cases);
                Files.write(dir.resolve("sha256.txt"), manifest);
                System.out.print("GENERATED\t" + dims.length + "\t" + cases.length + "\t" + manifest.length + "\n");
                return;
            }
            if (!"verify".equals(mode)) { System.out.print("USAGE\tunknown-mode\n"); System.exit(2); }
            verify(dir);
        } catch (Throwable t) {
            System.out.print("CHECK\tHARNESS\tFAIL\t" + t.getClass().getSimpleName() + "\n");
            System.out.print("FIRST\tHARNESS\n");
            System.out.print("SUMMARY\tchecks=1\tpass=0\tfail=1\tfirst=HARNESS\n");
            System.exit(1);
        }
    }

    /* ---------- deterministic generation ---------- */

    private static byte[] dimensions() {
        StringBuilder b = new StringBuilder();
        b.append("schema\t").append(SCHEMA).append('\n');
        b.append("seed\t").append(SEED).append('\n');
        b.append("title_cap\t").append(TITLE_CAP).append('\n');
        b.append("rule_cap\t").append(RULE_CAP).append('\n');
        b.append("max_title_len\t").append(MAX_TITLE_LEN).append('\n');
        b.append("marker_len\t").append(MARKER_LEN).append('\n');
        b.append("null_n\t").append(NULL_N).append('\n');
        b.append("lead_n\t").append(LEAD_N).append('\n');
        b.append("space_n\t").append(SPACE_N).append('\n');
        b.append("case_n\t").append(CASE_N).append('\n');
        b.append("idem_n\t").append(IDEM_N).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] cases() {
        StringBuilder b = new StringBuilder();
        b.append("id\tfamily\ttitle\texpectedKey\tchannel\texpectedRuleId\texpectedValid\n");
        int n = 0;
        for (int i = 0; i < NULL_N; i++) {
            row(b, ++n, "null", NULL_TITLES[i], "<null>", "-", "-", true);
        }
        for (int i = 0; i < LEAD_N; i++) {
            row(b, ++n, "lead", LEAD_PREFIX[i] + LEAD_BODY[i], LEAD_BODY[i], "-", "-", true);
        }
        for (int i = 0; i < SPACE_N; i++) {
            row(b, ++n, "space", SPACE_TITLES[i], SPACE_KEYS[i], "-", "-", true);
        }
        for (int i = 0; i < CASE_N; i++) {
            row(b, ++n, "case", CASE_TITLES[i], CASE_KEYS[i], "-", "-", true);
        }
        for (int i = 0; i < IDEM_N; i++) {
            row(b, ++n, "idem", IDEM_TITLES[i], IDEM_TITLES[i], "-", "-", true);
        }
        for (int i = 0; i < RULE_CAP; i++) {
            String title = "show " + String.format("%03d", Integer.valueOf(i + 1));
            String channel = i < 8 ? "" : "ch" + String.format("%02d", Integer.valueOf(i + 8));
            String suffix = i < 8 ? "*" : channel;
            row(b, ++n, "rule", title, title, channel, title + "|" + suffix, true);
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder b, int id, String family, String title,
                            String expectedKey, String channel, String expectedRuleId, boolean valid) {
        b.append("c").append(String.format("%04d", Integer.valueOf(id)))
         .append('\t').append(family)
         .append('\t').append(title)
         .append('\t').append(expectedKey)
         .append('\t').append(channel)
         .append('\t').append(expectedRuleId)
         .append('\t').append(valid ? "1" : "0")
         .append('\n');
    }

    private static byte[] manifest(byte[] dims, byte[] cases) {
        StringBuilder b = new StringBuilder();
        b.append(sha256Hex(dims)).append("\tdimensions.tsv\n");
        b.append(sha256Hex(cases)).append("\tcases.tsv\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /* ---------- independent reference model (no production call) ---------- */

    private static String refKey(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pending = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (pending && out.length() > 0) out.append(' ');
                out.append(Character.toLowerCase(ch));
                pending = false;
            } else {
                pending = true;
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /* ---------- verification ---------- */

    private static final class Row {
        String id, family, title, expectedKey, channel, expectedRuleId, expectedValid;
    }

    private static List<Row> parseCases(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        String[] all = text.split("\n", -1);
        List<Row> out = new ArrayList<Row>();
        for (int i = 0; i < all.length; i++) {
            String line = all[i];
            if (line.length() == 0) continue;
            if (i == 0 && line.startsWith("id\tfamily\t")) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 7) { throw new IllegalArgumentException("FIELD_COUNT"); }
            Row r = new Row();
            r.id = f[0]; r.family = f[1]; r.title = f[2]; r.expectedKey = f[3];
            r.channel = f[4]; r.expectedRuleId = f[5]; r.expectedValid = f[6];
            out.add(r);
        }
        return out;
    }

    private static void check(String id, boolean ok, String detail) {
        if (ok) { passCount++; lines.add("CHECK\t" + id + "\tPASS\t" + detail); }
        else { failCount++; if (first == null) first = id; lines.add("CHECK\t" + id + "\tFAIL\t" + detail); }
    }

    private static void verify(Path dir) throws IOException {
        startThreads = Thread.activeCount();
        byte[] dims = Files.readAllBytes(dir.resolve("dimensions.tsv"));
        byte[] casesRaw = Files.readAllBytes(dir.resolve("cases.tsv"));
        byte[] manifest = Files.readAllBytes(dir.resolve("sha256.txt"));

        List<Row> rows;
        try {
            rows = parseCases(casesRaw);
        } catch (IllegalArgumentException e) {
            check("HEADER_STRICT", false, e.getClass().getSimpleName());
            finish();
            return;
        }

        int[] counts = new int[5];
        int ruleRows = 0;
        for (Row r : rows) {
            if ("null".equals(r.family)) counts[0]++;
            else if ("lead".equals(r.family)) counts[1]++;
            else if ("space".equals(r.family)) counts[2]++;
            else if ("case".equals(r.family)) counts[3]++;
            else if ("idem".equals(r.family)) counts[4]++;
            else if ("rule".equals(r.family)) ruleRows++;
        }

        int nNull = 0, nLead = 0, nSpace = 0, nCase = 0, nIdem = 0;
        int nRuleChannel = 0, nRuleId = 0;
        boolean referenceOk = true;

        for (Row r : rows) {
            boolean isNull = "<null>".equals(r.expectedKey);
            String expectedByModel = refKey(r.title);
            referenceOk &= isNull ? expectedByModel == null : r.expectedKey.equals(expectedByModel);
            String got = SeriesRules.INSTANCE.titleKeyOf(r.title);
            boolean ok = isNull ? got == null : r.expectedKey.equals(got);
            if ("null".equals(r.family)) { nNull++; check("CANON_NULL", ok, r.id); }
            else if ("lead".equals(r.family)) { nLead++; check("CANON_LEAD", ok, r.id); }
            else if ("space".equals(r.family)) { nSpace++; check("CANON_SPACE", ok, r.id); }
            else if ("case".equals(r.family)) { nCase++; check("CANON_CASE", ok, r.id); }
            else if ("idem".equals(r.family)) {
                nIdem++;
                boolean idem = ok && got != null && got.equals(SeriesRules.INSTANCE.titleKeyOf(got));
                check("CANON_IDEM", idem, r.id);
            }
        }

        for (Row r : rows) {
            if (!"rule".equals(r.family)) continue;
            SeriesRule rule = SeriesRules.INSTANCE.ruleFor(r.title, r.channel);
            if (rule == null) { check("RULE_CHANNEL", false, r.id); nRuleChannel++; continue; }
            if (r.channel.length() == 0) {
                nRuleChannel++;
                check("RULE_CHANNEL", rule.getId().endsWith("*"), r.id);
            }
            nRuleId++;
            check("RULE_ID", r.expectedRuleId.equals(rule.getId()), r.id);
        }

        check("REFERENCE_MODEL", referenceOk, "rows=" + rows.size());
        check("DIMENSION_CAP",
            counts[0] == NULL_N && counts[1] == LEAD_N && counts[2] == SPACE_N &&
            counts[3] == CASE_N && counts[4] == IDEM_N && ruleRows == RULE_CAP &&
            rows.size() == TITLE_CAP + RULE_CAP && nNull == NULL_N && nLead == LEAD_N &&
            nSpace == SPACE_N && nCase == CASE_N && nIdem == IDEM_N &&
            nRuleChannel == 8 && nRuleId == RULE_CAP && titlesBounded(rows),
            "titles=" + rows.size() + " rules=" + ruleRows);

        byte[] regenDims = dimensions();
        byte[] regenCases = cases();
        byte[] regenManifest = manifest(regenDims, regenCases);
        String marker = marker();

        String allBytes = new String(dims, StandardCharsets.UTF_8) +
            new String(casesRaw, StandardCharsets.UTF_8) +
            new String(manifest, StandardCharsets.UTF_8) + joinLines();
        check("PRIVACY_SCAN", privacyHits(allBytes, marker) == 0, "clean");

        check("BYTE_IDENTITY",
            Arrays.equals(dims, regenDims) && Arrays.equals(casesRaw, regenCases) &&
            Arrays.equals(manifest, regenManifest),
            "files=3");
        check("MANIFEST_CONSISTENT", manifestConsistent(manifest, dims, casesRaw), "files=2");
        check("PINNED_DIGEST", PINNED_CASES_SHA256.equals(sha256Hex(casesRaw)), "cases");

        int endThreads = Thread.activeCount();
        int qaThreads = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().toLowerCase(java.util.Locale.ROOT).contains("qa65")) qaThreads++;
        }
        check("THREAD_DRIFT", endThreads == startThreads && qaThreads == 0,
            "start=" + startThreads + " end=" + endThreads + " qa=" + qaThreads);
        System.out.print("HEAP\tmax=" + Runtime.getRuntime().maxMemory() + "\n");

        finish();
    }

    private static boolean titlesBounded(List<Row> rows) {
        for (Row r : rows) if (r.title.length() > MAX_TITLE_LEN) return false;
        return true;
    }

    private static boolean manifestConsistent(byte[] manifest, byte[] dims, byte[] casesRaw) {
        String[] lines = new String(manifest, StandardCharsets.UTF_8).split("\n", -1);
        int n = 0;
        for (String line : lines) {
            if (line.length() == 0) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 2) return false;
            byte[] actual = "dimensions.tsv".equals(f[1]) ? dims :
                            "cases.tsv".equals(f[1]) ? casesRaw : null;
            if (actual == null || !f[0].equals(sha256Hex(actual))) return false;
            n++;
        }
        return n == 2;
    }

    private static String marker() {
        byte[] digest = sha256(("qa65|" + SEED).getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < MARKER_LEN; i++) b.append(String.format("%02x", Byte.valueOf(digest[i])));
        return b.toString();
    }

    private static int privacyHits(String text, String marker) {
        String[] patterns = {
            "http://", "https://", "rtsp://", "m3u8", "xmltv",
            "password", "passwd", "pwd=", "user=", "token=", "secret",
            "apikey", "api_key", "authorization:", "bearer ", "pin=", "pin:",
            "recording", "payload"
        };
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int hits = 0;
        for (String p : patterns) if (lower.contains(p)) hits++;
        if (text.contains(marker)) hits++;
        return hits;
    }

    private static String joinLines() {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }

    private static void finish() {
        for (String l : lines) System.out.print(l + "\n");
        System.out.print("FIRST\t" + (first == null ? "NONE" : first) + "\n");
        System.out.print("SUMMARY\tchecks=" + (passCount + failCount) +
            "\tpass=" + passCount + "\tfail=" + failCount +
            "\tfirst=" + (first == null ? "NONE" : first) + "\n");
        if (failCount > 0) System.exit(1);
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("NO_SHA256");
        }
    }

    private static String sha256Hex(byte[] data) {
        byte[] digest = sha256(data);
        StringBuilder b = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) b.append(String.format("%02x", Byte.valueOf(digest[i])));
        return b.toString();
    }
}
