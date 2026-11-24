import com.devtunasoft.tuneros.core.Channel;
import com.devtunasoft.tuneros.core.ChannelVisibility;
import com.devtunasoft.tuneros.core.GuideProgram;
import com.devtunasoft.tuneros.core.GuideSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QA-66 host gate. Zero dependencies beyond the JVM, the Kotlin stdlib and the
 * real compiled core ChannelVisibility/Models classes.
 *
 * Slice: hide-without-guide channel admission by ChannelVisibility.filter,
 * composed with trimmed case-insensitive name search and GuideSnapshot.hasSchedule.
 * No sibling QA gate calls this seam: QA-02 uses ChannelVisibility for import
 * counts only, QA-05 covers guide search, QA-63 lists hideWithoutGuide as a
 * zero-hit keyword.
 *
 * Modes: generate <dir> | verify <dir>
 */
public final class Qa66Gate {
    private static final String SCHEMA = "qa66-hide-guide-v1";
    private static final long SEED = 20261124L;
    private static final int CASE_CAP = 12;
    private static final int CHANNEL_CAP = 4;
    private static final int MAX_QUERY_LEN = 32;

    /* Filled from the first deterministic generation; byte identity also covers sha256.txt. */
    private static final String PINNED_CASES_SHA256 = "c0d9838e8490ae16bc4830bdda848b90fa7a3f94d837b216a8ad499a6ac97cea";
    private static final String PINNED_DIMS_SHA256 = "b1defe8d78b0bd5017de5e22eec710a5daa8313c15f679d4709e44fdd5317cf0";

    /* id, family, query, hide, guide mode, expected comma-separated channel ids */
    private static final String[][] ROWS = {
        {"c0001", "passthrough", "", "0", "NONE", "c1,c2,c3,c4"},
        {"c0002", "passthrough", "", "0", "PRESENT", "c1,c2,c3,c4"},
        {"c0003", "case", "NEWS", "0", "NONE", "c1"},
        {"c0004", "case", "KiDs", "0", "NONE", "c4"},
        {"c0005", "query", "sports", "0", "NONE", "c2"},
        {"c0006", "trim", "  kid  ", "0", "NONE", "c4"},
        {"c0007", "null_guide", "", "1", "NONE", ""},
        {"c0008", "null_guide", "news", "1", "NONE", ""},
        {"c0009", "schedule", "", "1", "PRESENT", "c1,c4"},
        {"c0010", "composed", "  MOVIES ", "1", "PRESENT", ""},
        {"c0011", "composed", "KIDS", "1", "PRESENT", "c4"},
        {"c0012", "nomatch", "none", "0", "NONE", ""},
    };

    private static String first;
    private static int checks, passCount, failCount;
    private static int startThreads;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.print("USAGE\tgenerate|verify\t<dir>\n");
            System.exit(2);
        }
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
            if (!"verify".equals(mode)) {
                System.out.print("USAGE\tunknown-mode\n");
                System.exit(2);
            }
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
        b.append("channel_cap\t").append(CHANNEL_CAP).append('\n');
        b.append("case_cap\t").append(CASE_CAP).append('\n');
        b.append("max_query_len\t").append(MAX_QUERY_LEN).append('\n');
        b.append("case_n\t").append(ROWS.length).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] cases() {
        StringBuilder b = new StringBuilder();
        b.append("id\tfamily\tquery\thide\tguide\texpected\n");
        for (String[] row : ROWS) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) b.append('\t');
                b.append(row[i]);
            }
            b.append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] manifest(byte[] dims, byte[] cases) {
        StringBuilder b = new StringBuilder();
        b.append("cases.tsv\t").append(sha256(cases)).append('\n');
        b.append("dimensions.tsv\t").append(sha256(dims)).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder(64);
            for (byte value : digest) b.append(String.format("%02x", Integer.valueOf(value & 0xff)));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /* ---------- verification ---------- */

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            passCount++;
        } else {
            failCount++;
            if (first == null) first = name;
        }
        System.out.print("CHECK\t" + name + "\t" + (ok ? "PASS" : "FAIL") + "\n");
    }

    private static String code(String family) {
        if ("passthrough".equals(family)) return "PASSTHROUGH_IDENTITY";
        if ("case".equals(family)) return "QUERY_CASE";
        if ("query".equals(family)) return "QUERY_MATCH";
        if ("trim".equals(family)) return "QUERY_TRIM";
        if ("null_guide".equals(family)) return "NULL_GUIDE_FAIL_CLOSED";
        if ("schedule".equals(family)) return "SCHEDULE_ADMISSION";
        if ("composed".equals(family)) return "COMPOSED_FILTER";
        if ("nomatch".equals(family)) return "NO_MATCH";
        return "UNKNOWN_FAMILY";
    }

    private static List<Channel> channels() {
        List<Channel> list = new ArrayList<Channel>();
        list.add(new Channel("c1", "News", "scratch-stream", "g1", null, null, null, null, ""));
        list.add(new Channel("c2", "Sports", "scratch-stream", "g2", null, null, null, null, ""));
        list.add(new Channel("c3", "Movies", "scratch-stream", null, null, null, null, null, ""));
        list.add(new Channel("c4", "Kids", "scratch-stream", "g4", null, null, null, null, ""));
        return list;
    }

    private static GuideSnapshot guide() {
        List<GuideProgram> programs = new ArrayList<GuideProgram>();
        programs.add(new GuideProgram("g1", "scratch-news", 1000L, 2000L, null));
        programs.add(new GuideProgram("g4", "scratch-kids", 1000L, 2000L, null));
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("g1", "News");
        names.put("g4", "Kids");
        return new GuideSnapshot(programs, names, new ArrayList<String>());
    }

    private static String ids(List<Channel> list) {
        StringBuilder b = new StringBuilder();
        for (Channel channel : list) {
            if (b.length() > 0) b.append(',');
            b.append(channel.getId());
        }
        return b.toString();
    }

    private static void verify(Path dir) throws IOException {
        startThreads = Thread.activeCount();
        byte[] dims = dimensions();
        byte[] expectedCases = cases();
        byte[] expectedManifest = manifest(dims, expectedCases);
        byte[] actualDims = Files.readAllBytes(dir.resolve("dimensions.tsv"));
        byte[] actualCases = Files.readAllBytes(dir.resolve("cases.tsv"));
        byte[] actualManifest = Files.readAllBytes(dir.resolve("sha256.txt"));

        check("BYTE_IDENTITY", Arrays.equals(actualDims, dims) &&
            Arrays.equals(actualCases, expectedCases) && Arrays.equals(actualManifest, expectedManifest));

        String caseSha = sha256(actualCases);
        String dimsSha = sha256(actualDims);
        String manifestText = new String(actualManifest, StandardCharsets.UTF_8);
        check("PINNED_SHA", manifestText.equals("cases.tsv\t" + caseSha + "\ndimensions.tsv\t" + dimsSha + "\n") &&
            PINNED_CASES_SHA256.equals(caseSha) && PINNED_DIMS_SHA256.equals(dimsSha));

        List<String[]> rows = new ArrayList<String[]>();
        String[] lines = new String(actualCases, StandardCharsets.UTF_8).split("\n", -1);
        int maxQueryLen = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] row = lines[i].split("\t", -1);
            rows.add(row);
            maxQueryLen = Math.max(maxQueryLen, row[2].length());
        }
        check("BOUNDS", rows.size() <= CASE_CAP && rows.size() == ROWS.length &&
            maxQueryLen <= MAX_QUERY_LEN);

        List<Channel> input = channels();
        String before = ids(input);
        GuideSnapshot present = guide();
        for (String[] row : rows) {
            GuideSnapshot snapshot = "PRESENT".equals(row[4]) ? present : null;
            boolean hide = "1".equals(row[3]);
            List<Channel> output = ChannelVisibility.INSTANCE.filter(input, snapshot, row[2], hide);
            String actual = ids(output);
            boolean ok = actual.equals(row[5]);
            System.out.print("CASE\t" + row[0] + "\t" + row[1] + "\t" + (ok ? "PASS" : "FAIL") + "\n");
            check(code(row[1]), ok);
        }
        check("INPUT_IMMUTABLE", before.equals(ids(input)));

        long maxHeap = Runtime.getRuntime().maxMemory();
        System.out.print("HEAP\t" + maxHeap + "\n");
        check("HEAP_BOUND", maxHeap <= 96L * 1024L * 1024L);
        check("THREAD_DRIFT", Thread.activeCount() == startThreads);

        System.out.print("FIRST\t" + (first == null ? "NONE" : first) + "\n");
        System.out.print("SUMMARY\tchecks=" + checks + "\tpass=" + passCount +
            "\tfail=" + failCount + "\tfirst=" + (first == null ? "NONE" : first) + "\n");
        if (failCount > 0) System.exit(1);
    }
}
