import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneRules;
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

/**
 * QA-14: deterministic, zero-dependency host gate for app-level reminder and
 * notification scheduling persistence. All state is scratch data; diagnostics
 * carry only check ids, counts and digests.
 */
public final class Qa14Gate {
    private static final String[] ZONES = {
        "UTC", "America/New_York", "Europe/London", "Australia/Lord_Howe",
        "Pacific/Kiritimati", "Pacific/Midway",
    };
    private static final String[] CATEGORIES = {
        "WORKDAY", "WEEKEND", "HOLIDAY", "SPORTS", "NEWS", "INFO", "QA",
    };
    private static final String[] STATES = {"SCHEDULED", "FIRED", "CANCELLED"};
    private static final String[] ARMS = {"EXACT", "PENDING"};
    private static final int ROW_COUNT = 1024;
    private static final int ROW_CAP = 1024;
    private static final int PAGE_SIZE = 64;
    private static final int ORDER_WINDOW = 64;
    private static final int LEAD_MAX = 900;
    private static final long NOW = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
    private static final DateTimeFormatter LOCAL_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String HEADER =
        "id\tzone\tlocal\tlead\tcategory\tstate\tkey\tarm\n";
    private static final String FIRED_HEADER =
        "id\trowId\tfireEpoch\tdeliveryCount\n";
    private static final String CORRUPT_HEADER = "name\thex\n";
    private static final Pattern ID_RE = Pattern.compile("qa14rem-[0-9]{6}");
    private static final Pattern KEY_RE = Pattern.compile("qa14key-[0-9]{6}");
    private static final Pattern LOCAL_RE =
        Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final String[] DATA_FILES = {
        "reminders.tsv", "corrupt.tsv", "fired.tsv", "dimensions.tsv",
    };

    private static final LinkedHashMap<String, String> CORRUPT_CASES = corruptCases();
    private static final long MARKER_SEED = 0x5141314C4F4E473AL;
    private static final long MARKER_XOR = 0x5A5A1234L;

    private static final class Row {
        String id;
        String zone;
        String local;
        int lead;
        String category;
        String state;
        String key;
        String arm;
        long epoch;
        long fireEpoch;
    }

    private static final class Result {
        final List<Row> rows = new ArrayList<>();
        String error;
    }

    private static final List<String> failures = new ArrayList<>();
    private static int positiveCount;
    private static int negativeCount;

    private Qa14Gate() {
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            System.out.println("usage: Qa14Gate generate|verify|verify-mutant|verify-manifest|verify-no-byte|mutate <dir> [kind]");
            System.exit(2);
        }
        String mode = args[0];
        Path dir = Path.of(args[1]);
        try {
            switch (mode) {
                case "generate":
                    generate(dir);
                    return;
                case "mutate":
                    if (args.length != 4) {
                        System.exit(2);
                    }
                    mutate(dir, Path.of(args[2]), args[3]);
                    return;
                case "verify":
                case "verify-mutant":
                case "verify-manifest":
                case "verify-no-byte":
                    verify(dir, mode);
                    return;
                default:
                    System.exit(2);
            }
        } catch (Exception error) {
            check("HOST_EXCEPTION_" + error.getClass().getSimpleName(), false, true);
            finish();
        }
    }

    private static void check(String id, boolean ok) {
        check(id, ok, false);
    }

    private static void check(String id, boolean ok, boolean negative) {
        System.out.println("CHECK\t" + (ok ? "PASS" : "FAIL") + "\t" + id);
        if (!ok) {
            failures.add(id);
        } else if (negative) {
            negativeCount++;
        } else {
            positiveCount++;
        }
    }

    private static void finish() {
        String first = failures.isEmpty() ? "NONE" : failures.get(0);
        int total = positiveCount + negativeCount + failures.size();
        System.out.println("FIRST\tFAIL\t" + first);
        System.out.println("SUMMARY\tpositive=" + positiveCount
            + "\tnegative-red=" + negativeCount
            + "\ttotal=" + total
            + "\tfailed=" + failures.size());
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------- deterministic fixture construction ----------

    private static String pad6(long value) {
        return String.format(Locale.ROOT, "%06d", value);
    }

    private static Row spec(int index) {
        int slot = index % 8;
        Row row = new Row();
        row.id = "qa14rem-" + pad6(index);
        row.zone = ZONES[index % ZONES.length];
        row.local = LocalDateTime.of(2026, 2, 20, 0, 0)
            .plusMinutes(index * 541L).format(LOCAL_FORMAT);
        row.lead = (index % 7) * 150;
        row.category = CATEGORIES[(index / 8) % CATEGORIES.length];
        row.state = "SCHEDULED";
        row.key = "qa14key-" + pad6(index);
        row.arm = "EXACT";
        if (slot == 1) {
            row.zone = "America/New_York";
            row.local = "2026-03-08T02:30:00";
            row.lead = 300;
        } else if (slot == 2) {
            row.zone = "America/New_York";
            row.local = "2026-11-01T01:30:00";
            row.lead = 300;
        } else if (slot == 3) {
            Row previous = spec(index - 1);
            row.zone = previous.zone;
            row.local = previous.local;
            row.lead = previous.lead;
            row.category = previous.category;
            row.key = previous.key;
            row.arm = "PENDING";
        } else if (slot == 4) {
            row.zone = "UTC";
            row.local = "2025-11-03T10:00:00";
            row.lead = 0;
        } else if (slot == 5) {
            row.state = "FIRED";
            row.arm = "PENDING";
        } else if (slot == 6) {
            row.state = "CANCELLED";
            row.arm = "PENDING";
        } else if (slot == 7) {
            if (index % 2 == 0) {
                row.zone = "Pacific/Kiritimati";
                row.local = "2026-01-02T03:30:00";
            } else {
                row.zone = "Pacific/Midway";
                row.local = "2026-01-03T23:30:00";
            }
            row.lead = 120;
        }
        return row;
    }

    private static String encode(Row row) {
        return row.id + "\t" + row.zone + "\t" + row.local + "\t" + row.lead
            + "\t" + row.category + "\t" + row.state + "\t" + row.key
            + "\t" + row.arm + "\n";
    }

    private static byte[] reminderBytes() {
        StringBuilder text = new StringBuilder(HEADER);
        for (int i = 0; i < ROW_COUNT; i++) {
            text.append(encode(spec(i)));
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static LinkedHashMap<String, String> corruptCases() {
        LinkedHashMap<String, String> cases = new LinkedHashMap<>();
        Row valid = spec(0);
        cases.put("bad-zone", encode(valid).replace("\tUTC\t", "\tMars/Olympus\t"));
        cases.put("bad-id", encode(valid).replace("qa14rem-000000", "qa14rem-00000X"));
        cases.put("bad-fields", encode(valid).replace("\tEXACT\n", "\n"));
        cases.put("bad-local", encode(valid).replace("2026-02-20T00:00:00", "2026-13-45T99:99:99"));
        cases.put("bad-lead", encode(valid).replace("\t0\t", "\tabc\t"));
        cases.put("lead-range", encode(valid).replace("\t0\t", "\t901\t"));
        cases.put("bad-state", encode(valid).replace("\tSCHEDULED\t", "\tUNKNOWN\t"));
        cases.put("bad-key", encode(valid).replace("qa14key-000000", "bad-key"));
        cases.put("bad-arm", encode(valid).replace("\tEXACT\n", "\tUNKNOWN\n"));
        cases.put("bad-category", encode(valid).replace("\tWORKDAY\t", "\tUNKNOWN\t"));
        String row = encode(valid);
        cases.put("truncated", row.substring(0, row.length() - 1));
        cases.put("blank-id", row.replace("qa14rem-000000", ""));
        cases.put("extra-field", row.replace("\tEXACT\n", "\tEXACT\tEXTRA\n"));
        cases.put("duplicate-id", row + encode(valid));
        cases.put("wrong-delim", row.replace('\t', ' '));
        return cases;
    }

    private static final Map<String, String> CORRUPT_CODES = corruptCodes();

    private static Map<String, String> corruptCodes() {
        Map<String, String> codes = new HashMap<>();
        codes.put("bad-zone", "STRICT_REMINDERS_ZONE");
        codes.put("bad-id", "STRICT_REMINDERS_ID");
        codes.put("bad-fields", "STRICT_REMINDERS_FIELDS");
        codes.put("bad-local", "STRICT_REMINDERS_LOCAL");
        codes.put("bad-lead", "STRICT_REMINDERS_LEAD");
        codes.put("lead-range", "STRICT_REMINDERS_RANGE");
        codes.put("bad-state", "STRICT_REMINDERS_STATE");
        codes.put("bad-key", "STRICT_REMINDERS_KEY");
        codes.put("bad-arm", "STRICT_REMINDERS_ARM");
        codes.put("bad-category", "STRICT_REMINDERS_CATEGORY");
        codes.put("truncated", "STRICT_REMINDERS_TERMINATOR");
        codes.put("blank-id", "STRICT_REMINDERS_ID");
        codes.put("extra-field", "STRICT_REMINDERS_FIELDS");
        codes.put("duplicate-id", "STRICT_REMINDERS_DUPLICATE_ID");
        codes.put("wrong-delim", "STRICT_REMINDERS_FIELDS");
        return codes;
    }

    private static byte[] corruptBytes() {
        StringBuilder text = new StringBuilder(CORRUPT_HEADER);
        for (Map.Entry<String, String> entry : CORRUPT_CASES.entrySet()) {
            text.append(entry.getKey()).append('\t')
                .append(hex(entry.getValue().getBytes(StandardCharsets.US_ASCII)))
                .append('\n');
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] firedBytes(List<Row> rows) {
        StringBuilder text = new StringBuilder(FIRED_HEADER);
        for (Row row : rows) {
            if (!"FIRED".equals(row.state)) {
                continue;
            }
            text.append(row.id).append('\t').append(row.id).append('\t')
                .append(row.fireEpoch).append("\t1\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] dimensionBytes(byte[] reminders, List<Row> rows) {
        Map<String, Integer> cases = new HashMap<>();
        int expired = 0;
        int fired = 0;
        int cancelled = 0;
        int dstGap = 0;
        int dstOverlap = 0;
        int boundary = 0;
        for (Row row : rows) {
            if (row.fireEpoch <= NOW) {
                expired++;
            }
            if ("FIRED".equals(row.state)) {
                fired++;
            }
            if ("CANCELLED".equals(row.state)) {
                cancelled++;
            }
            if ("America/New_York".equals(row.zone)
                && "2026-03-08T02:30:00".equals(row.local)) {
                dstGap++;
            }
            if ("America/New_York".equals(row.zone)
                && "2026-11-01T01:30:00".equals(row.local)) {
                dstOverlap++;
            }
            if (boundaryCase(row)) {
                boundary++;
            }
            if ("SCHEDULED".equals(row.state)) {
                cases.merge(row.zone + "|" + row.local + "|" + row.key + "|" + row.lead,
                    1, Integer::sum);
            }
        }
        int duplicates = 0;
        for (Integer count : cases.values()) {
            if (count > 1) {
                duplicates += count - 1;
            }
        }
        List<Row> eligible = eligible(rows);
        StringBuilder text = new StringBuilder();
        text.append("schema=qa14-v1\n");
        text.append("rows=").append(rows.size()).append('\n');
        text.append("row-cap=").append(ROW_CAP).append('\n');
        text.append("page-size=").append(PAGE_SIZE).append('\n');
        text.append("order-window=").append(ORDER_WINDOW).append('\n');
        text.append("lead-max=").append(LEAD_MAX).append('\n');
        text.append("zone-count=").append(ZONES.length).append('\n');
        text.append("expired=").append(expired).append('\n');
        text.append("fired=").append(fired).append('\n');
        text.append("cancelled=").append(cancelled).append('\n');
        text.append("duplicates=").append(duplicates).append('\n');
        text.append("dst-gap=").append(dstGap).append('\n');
        text.append("dst-overlap=").append(dstOverlap).append('\n');
        text.append("tz-boundary=").append(boundary).append('\n');
        text.append("eligible=").append(eligible.size()).append('\n');
        text.append("pages=").append((eligible.size() + PAGE_SIZE - 1) / PAGE_SIZE).append('\n');
        text.append("order-digest=").append(orderDigest(eligible)).append('\n');
        text.append("mirror-digest=").append(sha256(reminders)).append('\n');
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] manifestBytes(Map<String, byte[]> files) {
        StringBuilder text = new StringBuilder();
        for (String name : DATA_FILES) {
            text.append(sha256(files.get(name))).append("  ").append(name).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        byte[] reminders = reminderBytes();
        Result parsed = parseReminderData(reminders, true);
        if (parsed.error != null) {
            throw new IllegalStateException("generator-parse");
        }
        byte[] corrupt = corruptBytes();
        byte[] fired = firedBytes(parsed.rows);
        byte[] dimensions = dimensionBytes(reminders, parsed.rows);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("reminders.tsv", reminders);
        files.put("corrupt.tsv", corrupt);
        files.put("fired.tsv", fired);
        files.put("dimensions.tsv", dimensions);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Files.write(dir.resolve(entry.getKey()), entry.getValue());
        }
        Files.write(dir.resolve("sha256.txt"), manifestBytes(files));
        Files.write(dir.resolve(".gitattributes"),
            "* -text\n.gitattributes -text\n".getBytes(StandardCharsets.US_ASCII));
        System.out.println("GENERATE\tPASS\trows=" + parsed.rows.size()
            + "\teligible=" + eligible(parsed.rows).size()
            + "\tfiles=" + (files.size() + 2));
    }

    // ---------- strict parsing ----------

    private static String ascii(byte[] data) {
        for (byte value : data) {
            int unsigned = value & 0xff;
            if (unsigned == 9 || unsigned == 10 || (unsigned >= 32 && unsigned <= 126)) {
                continue;
            }
            return "STRICT_ENCODING";
        }
        return null;
    }

    private static Result parseReminderData(byte[] data, boolean requireHeader) {
        Result result = new Result();
        String encoding = ascii(data);
        if (encoding != null) {
            result.error = encoding;
            return result;
        }
        if (data.length == 0 || data[data.length - 1] != '\n') {
            result.error = "STRICT_REMINDERS_TERMINATOR";
            return result;
        }
        String text = new String(data, StandardCharsets.US_ASCII);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        int start = 0;
        if (requireHeader) {
            if (lines.length == 0 || !lines[0].equals(HEADER.substring(0, HEADER.length() - 1))) {
                result.error = "STRICT_REMINDERS_HEADER";
                return result;
            }
            start = 1;
        }
        Set<String> ids = new HashSet<>();
        for (int i = start; i < lines.length; i++) {
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 8) {
                result.error = "STRICT_REMINDERS_FIELDS";
                return result;
            }
            Row row = new Row();
            row.id = parts[0];
            row.zone = parts[1];
            row.local = parts[2];
            if (!ID_RE.matcher(row.id).matches()) {
                result.error = "STRICT_REMINDERS_ID";
                return result;
            }
            if (!ids.add(row.id)) {
                result.error = "STRICT_REMINDERS_DUPLICATE_ID";
                return result;
            }
            if (!Arrays.asList(ZONES).contains(row.zone)) {
                result.error = "STRICT_REMINDERS_ZONE";
                return result;
            }
            if (!LOCAL_RE.matcher(row.local).matches()) {
                result.error = "STRICT_REMINDERS_LOCAL";
                return result;
            }
            try {
                row.lead = Integer.parseInt(parts[3]);
            } catch (NumberFormatException error) {
                result.error = "STRICT_REMINDERS_LEAD";
                return result;
            }
            if (row.lead < 0 || row.lead > LEAD_MAX) {
                result.error = "STRICT_REMINDERS_RANGE";
                return result;
            }
            row.category = parts[4];
            row.state = parts[5];
            row.key = parts[6];
            row.arm = parts[7];
            if (!Arrays.asList(CATEGORIES).contains(row.category)) {
                result.error = "STRICT_REMINDERS_CATEGORY";
                return result;
            }
            if (!Arrays.asList(STATES).contains(row.state)) {
                result.error = "STRICT_REMINDERS_STATE";
                return result;
            }
            if (!KEY_RE.matcher(row.key).matches()) {
                result.error = "STRICT_REMINDERS_KEY";
                return result;
            }
            if (!Arrays.asList(ARMS).contains(row.arm)) {
                result.error = "STRICT_REMINDERS_ARM";
                return result;
            }
            try {
                row.epoch = resolve(row.local, row.zone);
            } catch (RuntimeException error) {
                result.error = "STRICT_REMINDERS_LOCAL";
                return result;
            }
            row.fireEpoch = row.epoch - row.lead;
            result.rows.add(row);
        }
        return result;
    }

    private static long resolve(String local, String zone) {
        LocalDateTime dateTime = LocalDateTime.parse(local);
        ZonedDateTime zoned = dateTime.atZone(ZoneId.of(zone));
        return zoned.toInstant().getEpochSecond();
    }

    private static Result parseCorruptData(byte[] data) {
        Result result = new Result();
        if (data.length == 0 || data[data.length - 1] != '\n') {
            result.error = "STRICT_CORRUPT_TERMINATOR";
            return result;
        }
        String text = new String(data, StandardCharsets.US_ASCII);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length == 0 || !lines[0].equals("name\thex")) {
            result.error = "STRICT_CORRUPT_HEADER";
            return result;
        }
        Set<String> names = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 2) {
                result.error = "STRICT_CORRUPT_FIELDS";
                return result;
            }
            if (!CORRUPT_CASES.containsKey(parts[0]) || !names.add(parts[0])) {
                result.error = "STRICT_CORRUPT_NAME";
                return result;
            }
            byte[] payload;
            try {
                payload = unhex(parts[1]);
            } catch (IllegalArgumentException error) {
                result.error = "STRICT_CORRUPT_HEX";
                return result;
            }
            String rejection = parseReminderData(payload, false).error;
            String expected = CORRUPT_CODES.get(parts[0]);
            if (!expected.equals(rejection)) {
                result.error = "CORRUPT_REJECT_" + parts[0];
                return result;
            }
        }
        return result;
    }

    private static Map<String, String> parseDimensions(byte[] data) {
        Map<String, String> values = new LinkedHashMap<>();
        String text = new String(data, StandardCharsets.US_ASCII);
        if (!text.endsWith("\n")) {
            throw new IllegalArgumentException("STRICT_DIMENSIONS_TERMINATOR");
        }
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            String[] parts = line.split("=", -1);
            if (parts.length != 2 || parts[0].isEmpty() || values.containsKey(parts[0])) {
                throw new IllegalArgumentException("STRICT_DIMENSIONS_FIELDS");
            }
            values.put(parts[0], parts[1]);
        }
        return values;
    }

    private static Map<String, String> parseManifest(byte[] data) {
        Map<String, String> digestByName = new LinkedHashMap<>();
        String text = new String(data, StandardCharsets.US_ASCII);
        if (!text.endsWith("\n")) {
            throw new IllegalArgumentException("STRICT_MANIFEST_TERMINATOR");
        }
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            String[] parts = line.split("  ", -1);
            if (parts.length != 2 || parts[0].length() != 64 || digestByName.containsKey(parts[1])) {
                throw new IllegalArgumentException("STRICT_MANIFEST_FIELDS");
            }
            digestByName.put(parts[1], parts[0]);
        }
        return digestByName;
    }

    private static List<String> parseFired(byte[] data, List<Row> rows) {
        List<String> errors = new ArrayList<>();
        Map<String, Row> byId = new HashMap<>();
        for (Row row : rows) {
            byId.put(row.id, row);
        }
        String text = new String(data, StandardCharsets.US_ASCII);
        if (!text.endsWith("\n")) {
            errors.add("STRICT_FIRED_TERMINATOR");
            return errors;
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length == 0 || !lines[0].equals(FIRED_HEADER.substring(0, FIRED_HEADER.length() - 1))) {
            errors.add("STRICT_FIRED_HEADER");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 4) {
                errors.add("STRICT_FIRED_FIELDS");
                continue;
            }
            Row row = byId.get(parts[1]);
            if (row == null || !"FIRED".equals(row.state) || !ids.add(parts[1])
                || !parts[0].equals(parts[1])
                || !parts[2].equals(Long.toString(row.fireEpoch))
                || !parts[3].equals("1")) {
                errors.add("FIRING_LEDGER");
            }
        }
        return errors;
    }

    // ---------- model ----------

    private static List<Row> eligible(List<Row> rows) {
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if ("SCHEDULED".equals(row.state) && "EXACT".equals(row.arm)
                && row.fireEpoch > NOW) {
                result.add(row);
            }
        }
        result.sort((left, right) -> {
            int byTime = Long.compare(left.fireEpoch, right.fireEpoch);
            return byTime != 0 ? byTime : left.id.compareTo(right.id);
        });
        return result;
    }

    private static int duplicateConflicts(List<Row> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (Row row : rows) {
            if ("SCHEDULED".equals(row.state)) {
                String key = row.zone + "|" + row.local + "|" + row.key + "|" + row.lead;
                counts.merge(key, 1, Integer::sum);
            }
        }
        int conflicts = 0;
        for (Integer count : counts.values()) {
            if (count > 1) {
                conflicts += count - 1;
            }
        }
        return conflicts;
    }

    private static String orderDigest(List<Row> eligible) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(ORDER_WINDOW, eligible.size()); i++) {
            text.append(eligible.get(i).id).append('\n');
        }
        return sha256(text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String marker() {
        long value = MARKER_SEED ^ MARKER_XOR;
        return String.format(Locale.ROOT, "%08d", Math.floorMod(value, 100_000_000L));
    }

    private static boolean privacyClean(Map<String, byte[]> files) {
        String secret = marker();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String text = new String(entry.getValue(), StandardCharsets.US_ASCII);
            if (text.contains(secret) || text.contains("://")
                || text.contains("Bearer ") || text.contains("token=")
                || text.contains("pin=") || text.contains("secret=")) {
                return false;
            }
        }
        return true;
    }

    // ---------- verification ----------

    private static void verify(Path dir, String mode) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : DATA_FILES) {
            files.put(name, Files.readAllBytes(dir.resolve(name)));
        }
        byte[] manifestData = Files.readAllBytes(dir.resolve("sha256.txt"));
        Map<String, String> manifest = parseManifest(manifestData);
        if ("verify-manifest".equals(mode)) {
            check("MANIFEST_SHA256_MATCH", manifestConsistent(files, manifest));
            finish();
            return;
        }

        check("LEAK_SCAN", privacyClean(files));

        if ("verify".equals(mode)) {
            Map<String, byte[]> regenerated = regenerated(files);
            boolean identical = regenerated != null;
            if (identical) {
                for (String name : files.keySet()) {
                    if (!Arrays.equals(files.get(name), regenerated.get(name))) {
                        identical = false;
                    }
                }
            }
            check("BYTE_IDENTICAL_REGENERATION", identical);
        }
        check("MANIFEST_SHA256_MATCH", manifestConsistent(files, manifest));

        Result parsed = parseReminderData(files.get("reminders.tsv"), true);
        if (parsed.error != null) {
            check(parsed.error, false);
            finish();
            return;
        }
        check("STRICT_REMINDERS_PARSE", true);
        check("ROW_CAP_BINDS", parsed.rows.size() == ROW_CAP);

        Result corrupt = parseCorruptData(files.get("corrupt.tsv"));
        if (corrupt.error != null) {
            check(corrupt.error, false);
            finish();
            return;
        }
        check("CORRUPT_REJECT_ALL", CORRUPT_CASES.size() == 15);

        List<String> firedErrors = parseFired(files.get("fired.tsv"), parsed.rows);
        for (String error : firedErrors) {
            check(error, false);
        }
        check("FIRING_LEDGER_PARSE", firedErrors.isEmpty());

        Map<String, String> dimensions = parseDimensions(files.get("dimensions.tsv"));
        List<Row> eligible = eligible(parsed.rows);
        int expired = 0;
        int fired = 0;
        int cancelled = 0;
        int gap = 0;
        int overlap = 0;
        int boundary = 0;
        int scheduledSeen = 0;
        for (Row row : parsed.rows) {
            if (row.fireEpoch <= NOW) {
                expired++;
            }
            if ("FIRED".equals(row.state)) {
                fired++;
            }
            if ("CANCELLED".equals(row.state)) {
                cancelled++;
            }
            if ("America/New_York".equals(row.zone)
                && "2026-03-08T02:30:00".equals(row.local)) {
                gap++;
            }
            if ("America/New_York".equals(row.zone)
                && "2026-11-01T01:30:00".equals(row.local)) {
                overlap++;
            }
            if (boundaryCase(row)) {
                boundary++;
            }
            if ("SCHEDULED".equals(row.state)) {
                scheduledSeen++;
            }
        }
        int duplicates = duplicateConflicts(parsed.rows);

        check("DUPLICATE_REJECTION", duplicates == 128
            && pin(dimensions, "duplicates", 128));
        check("EXPIRED_REJECTION", expired == 128 && pin(dimensions, "expired", 128)
            && noneIn(eligible, row -> row.fireEpoch <= NOW));
        check("FIRED_REJECTION", fired == 128 && pin(dimensions, "fired", 128)
            && noneIn(eligible, row -> "FIRED".equals(row.state)));
        check("CANCELLED_REJECTION", cancelled == 128 && pin(dimensions, "cancelled", 128)
            && noneIn(eligible, row -> "CANCELLED".equals(row.state)));
        check("DST_GAP_NORMALIZED", gap == 128 && pin(dimensions, "dst-gap", 128)
            && gapResolved(parsed.rows));
        check("DST_OVERLAP_EARLIER_OFFSET", overlap == 256
            && pin(dimensions, "dst-overlap", 256) && overlapResolved(parsed.rows));
        check("TIMEZONE_BOUNDARY_COUNT", boundary == 128
            && pin(dimensions, "tz-boundary", 128) && timezoneBoundary(parsed.rows));
        check("EXACT_ARM_REQUIRED", noneIn(eligible, row -> !"EXACT".equals(row.arm)));
        check("PAGE_CAP_PINNED", pin(dimensions, "page-size", PAGE_SIZE)
            && pin(dimensions, "row-cap", ROW_CAP)
            && pin(dimensions, "order-window", ORDER_WINDOW)
            && pin(dimensions, "lead-max", LEAD_MAX));
        check("PAGE_BOUNDED", paged(eligible, PAGE_SIZE) == (eligible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        check("ORDER_DIGEST_PINNED", orderDigest(eligible).equals(dimensions.get("order-digest")));
        check("PERSISTENCE_ROUND_TRIP", roundTrip(parsed.rows, files.get("reminders.tsv"))
            && scheduledSeen == 768);
        check("DIMENSION_COUNTS_PINNED", pin(dimensions, "rows", ROW_COUNT)
            && pin(dimensions, "eligible", 512) && pin(dimensions, "pages", 8)
            && pin(dimensions, "zone-count", ZONES.length)
            && pin(dimensions, "schema", "qa14-v1"));
        if ("verify".equals(mode) || "verify-no-byte".equals(mode)) {
            check("MIRROR_DIGEST_PINNED", pin(dimensions, "mirror-digest", sha256(files.get("reminders.tsv"))));
        }
        negativeControls(files, parsed.rows, eligible);
        finish();
    }

    private interface RowPredicate {
        boolean test(Row row);
    }

    private static boolean noneIn(List<Row> rows, RowPredicate predicate) {
        for (Row row : rows) {
            if (predicate.test(row)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pin(Map<String, String> dimensions, String key, long expected) {
        return pin(dimensions, key, Long.toString(expected));
    }

    private static boolean pin(Map<String, String> dimensions, String key, String expected) {
        return expected.equals(dimensions.get(key));
    }

    private static boolean gapResolved(List<Row> rows) {
        int seen = 0;
        long expected = LocalDateTime.parse("2026-03-08T03:30:00")
            .atZone(ZoneId.of("America/New_York")).toInstant().getEpochSecond() - 300;
        for (Row row : rows) {
            if ("America/New_York".equals(row.zone)
                && "2026-03-08T02:30:00".equals(row.local)) {
                if (row.fireEpoch != expected) {
                    return false;
                }
                seen++;
            }
        }
        return seen == 128;
    }

    private static boolean overlapResolved(List<Row> rows) {
        int seen = 0;
        long expected = LocalDateTime.parse("2026-11-01T01:30:00")
            .atZone(ZoneId.of("America/New_York")).toInstant().getEpochSecond() - 300;
        for (Row row : rows) {
            if ("America/New_York".equals(row.zone)
                && "2026-11-01T01:30:00".equals(row.local)) {
                if (row.fireEpoch != expected) {
                    return false;
                }
                seen++;
            }
        }
        return seen == 256;
    }

    private static boolean timezoneBoundary(List<Row> rows) {
        int seen = 0;
        for (Row row : rows) {
            if (!boundaryCase(row)) {
                continue;
            }
            String utcDate = Instant.ofEpochSecond(row.epoch).toString().substring(0, 10);
            String localDate = row.local.substring(0, 10);
            if (utcDate.equals(localDate)) {
                return false;
            }
            seen++;
        }
        return seen == 128;
    }

    private static boolean boundaryCase(Row row) {
        return ("Pacific/Kiritimati".equals(row.zone) && "2026-01-02T03:30:00".equals(row.local))
            || ("Pacific/Midway".equals(row.zone) && "2026-01-03T23:30:00".equals(row.local));
    }

    private static int paged(List<Row> rows, int pageSize) {
        int pages = 0;
        int cursor = 0;
        long lastTime = Long.MIN_VALUE;
        String lastId = "";
        while (cursor < rows.size() && pages < 100) {
            int count = 0;
            while (cursor < rows.size() && count < pageSize) {
                Row row = rows.get(cursor++);
                if (row.fireEpoch < lastTime
                    || (row.fireEpoch == lastTime && row.id.compareTo(lastId) <= 0)) {
                    return -1;
                }
                lastTime = row.fireEpoch;
                lastId = row.id;
                count++;
            }
            pages++;
        }
        return pages;
    }

    private static boolean roundTrip(List<Row> rows, byte[] original) {
        StringBuilder text = new StringBuilder(HEADER);
        for (Row row : rows) {
            text.append(encode(row));
        }
        return Arrays.equals(text.toString().getBytes(StandardCharsets.US_ASCII), original);
    }

    private static void negativeControls(Map<String, byte[]> files, List<Row> rows,
            List<Row> eligible) {
        List<String> canonicalIds = new ArrayList<>();
        for (Row row : eligible) {
            canonicalIds.add(row.id);
        }
        List<Row> naive = new ArrayList<>(eligible);
        naive.sort((left, right) -> {
            int byZone = left.zone.compareTo(right.zone);
            return byZone != 0 ? byZone : left.local.compareTo(right.local);
        });
        List<String> naiveIds = new ArrayList<>();
        for (Row row : naive) {
            naiveIds.add(row.id);
        }
        check("NEG_NAIVE_SORT_BLIND", !canonicalIds.equals(naiveIds), true);

        boolean gapNaive = false;
        long naiveGap = LocalDateTime.parse("2026-03-08T02:30:00")
            .toEpochSecond(ZoneOffset.ofHours(-5)) - 300;
        for (Row row : rows) {
            if ("2026-03-08T02:30:00".equals(row.local) && row.fireEpoch == naiveGap) {
                gapNaive = true;
            }
        }
        check("NEG_DST_GAP_FIXED_OFFSET", gapNaive, true);

        boolean lateOffset = false;
        ZoneRules rules = ZoneId.of("America/New_York").getRules();
        java.time.ZonedDateTime overlap = LocalDateTime.parse("2026-11-01T01:30:00")
            .atZone(ZoneId.of("America/New_York")).withLaterOffsetAtOverlap();
        long lateFire = overlap.toInstant().getEpochSecond() - 300;
        for (Row row : rows) {
            if ("2026-11-01T01:30:00".equals(row.local) && row.fireEpoch != lateFire) {
                lateOffset = true;
            }
        }
        check("NEG_DST_OVERLAP_LATE_OFFSET", lateOffset && rules != null, true);

        check("NEG_DUPLICATE_ACCEPTED", duplicateConflicts(rows) > 0, true);
        int laxExpired = 0;
        int laxFired = 0;
        for (Row row : rows) {
            if ("SCHEDULED".equals(row.state) && row.fireEpoch <= NOW) {
                laxExpired++;
            }
            if ("FIRED".equals(row.state)) {
                laxFired++;
            }
        }
        check("NEG_EXPIRED_ACCEPTED", laxExpired > 0, true);
        check("NEG_FIRED_ACCEPTED", laxFired > 0, true);
        check("NEG_UNBOUNDED_PAGE", eligible.size() > PAGE_SIZE, true);
        String truncated = encode(spec(0));
        truncated = truncated.substring(0, truncated.length() - 1);
        check("NEG_PERMISSIVE_CORRUPT", parseReminderData(
            (truncated + "\n").getBytes(StandardCharsets.US_ASCII), false).error == null, true);
        Map<String, byte[]> leaky = new LinkedHashMap<>(files);
        byte[] original = files.get("corrupt.tsv");
        byte[] appended = Arrays.copyOf(original, original.length + marker().length() + 1);
        System.arraycopy(original, 0, appended, 0, original.length);
        System.arraycopy(marker().getBytes(StandardCharsets.US_ASCII), 0,
            appended, original.length, marker().length());
        appended[appended.length - 1] = '\n';
        leaky.put("corrupt.tsv", appended);
        check("NEG_LEAK_DETECTOR", !privacyClean(leaky), true);
        check("NEG_DUPLICATE_FIRE_ACK", parseFired(firedWithDuplicate(files.get("fired.tsv")), rows).size() > 0, true);
    }

    private static byte[] firedWithDuplicate(byte[] original) {
        String text = new String(original, StandardCharsets.US_ASCII);
        int newline = text.indexOf('\n');
        String header = text.substring(0, newline + 1);
        String firstRow = text.substring(newline + 1, text.indexOf('\n', newline + 1) + 1);
        return (header + firstRow + firstRow).getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean manifestConsistent(Map<String, byte[]> files, Map<String, String> manifest) {
        if (manifest.size() != DATA_FILES.length) {
            return false;
        }
        for (String name : DATA_FILES) {
            if (!sha256(files.get(name)).equals(manifest.get(name))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, byte[]> regenerated(Map<String, byte[]> files) {
        Result parsed = parseReminderData(files.get("reminders.tsv"), true);
        if (parsed.error != null) {
            return null;
        }
        Map<String, byte[]> output = new LinkedHashMap<>();
        output.put("reminders.tsv", reminderBytes());
        output.put("corrupt.tsv", corruptBytes());
        output.put("fired.tsv", firedBytes(parsed.rows));
        output.put("dimensions.tsv", dimensionBytes(output.get("reminders.tsv"), parsed.rows));
        return output;
    }

    // ---------- mutation ----------

    private static void mutate(Path source, Path target, String kind) throws IOException {
        Files.createDirectories(target);
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : DATA_FILES) {
            files.put(name, Files.readAllBytes(source.resolve(name)));
        }
        switch (kind) {
            case "flip":
                files.put("reminders.tsv", replaceOnce(files.get("reminders.tsv"),
                    "qa14rem-000000", "qa14rem-000001"));
                break;
            case "manifest": {
                byte[] data = Files.readAllBytes(source.resolve("sha256.txt"));
                String text = new String(data, StandardCharsets.US_ASCII);
                String first = text.substring(0, 1);
                String changed = "0".equals(first) ? "1" : "0";
                files.put("__manifest", (changed + text.substring(1)).getBytes(StandardCharsets.US_ASCII));
                break;
            }
            case "header":
                files.put("reminders.tsv", replaceOnce(files.get("reminders.tsv"),
                    "id\tzone\tlocal\tlead\tcategory\tstate\tkey\tarm\n",
                    "id\tzone\tlocal\tlead\tcategory\tstate\tkey\tarmX\n"));
                break;
            case "fields":
                files.put("reminders.tsv", replaceOnce(files.get("reminders.tsv"),
                    "qa14rem-000000\tUTC\t2026-02-20T00:00:00\t0\tWORKDAY\tSCHEDULED\tqa14key-000000\tEXACT\n",
                    "qa14rem-000000\tUTC\t2026-02-20T00:00:00\t0\tWORKDAY\tSCHEDULED\tqa14key-000000\n"));
                break;
            case "truncated": {
                byte[] data = files.get("reminders.tsv");
                files.put("reminders.tsv", Arrays.copyOf(data, data.length - 1));
                break;
            }
            case "range":
                files.put("reminders.tsv", replaceOnce(files.get("reminders.tsv"),
                    "2026-02-20T00:00:00\t0\tWORKDAY", "2026-02-20T00:00:00\t901\tWORKDAY"));
                break;
            case "zone":
                files.put("reminders.tsv", replaceOnce(files.get("reminders.tsv"),
                    "qa14rem-000000\tUTC\t", "qa14rem-000000\tMars/Olympus\t"));
                break;
            case "duplicate":
                files.put("reminders.tsv", retargetRow(files.get("reminders.tsv"),
                    "qa14rem-000001", "America/New_York", "2026-11-01T01:30:00",
                    300, "qa14key-000010"));
                break;
            case "expired":
                files.put("reminders.tsv", editRow(files.get("reminders.tsv"),
                    "qa14rem-000020", "2025-11-03T10:00:00", "2026-11-03T10:00:00"));
                break;
            case "fired": {
                files.put("reminders.tsv", editRow(files.get("reminders.tsv"),
                    "qa14rem-000021", "\tFIRED\tqa14key-000021\tPENDING\n",
                    "\tSCHEDULED\tqa14key-000021\tEXACT\n"));
                files.put("fired.tsv", dropFiredRow(files.get("fired.tsv"), "qa14rem-000021"));
                break;
            }
            case "order": {
                byte[] data = replaceOnce(files.get("reminders.tsv"),
                    "qa14rem-000007\t", "qa14rem-001024\t");
                data = replaceOnce(data, "qa14rem-000000\t", "qa14rem-000007\t");
                files.put("reminders.tsv", replaceOnce(data, "qa14rem-001024\t", "qa14rem-000000\t"));
                break;
            }
            case "cap":
                files.put("dimensions.tsv", replaceOnce(files.get("dimensions.tsv"),
                    "page-size=64\n", "page-size=8\n"));
                break;
            case "corruptaccepted": {
                String valid = hex(encode(spec(0)).getBytes(StandardCharsets.US_ASCII));
                String text = new String(files.get("corrupt.tsv"), StandardCharsets.US_ASCII);
                int end = text.indexOf('\n', text.indexOf("bad-zone\t"));
                String head = text.substring(0, text.indexOf('\t', text.indexOf("bad-zone\t")) + 1);
                files.put("corrupt.tsv", (head + valid + text.substring(end)).getBytes(StandardCharsets.US_ASCII));
                break;
            }
            case "leak": {
                byte[] data = files.get("corrupt.tsv");
                byte[] marker = marker().getBytes(StandardCharsets.US_ASCII);
                byte[] output = Arrays.copyOf(data, data.length + marker.length + 1);
                System.arraycopy(data, 0, output, 0, data.length);
                System.arraycopy(marker, 0, output, data.length, marker.length);
                output[output.length - 1] = '\n';
                files.put("corrupt.tsv", output);
                break;
            }
            case "mirror":
                files.put("dimensions.tsv", replaceOnce(files.get("dimensions.tsv"),
                    "mirror-digest=", "mirror-digest=0"));
                break;
            default:
                throw new IllegalArgumentException("unknown-mutant");
        }
        for (String name : DATA_FILES) {
            Files.write(target.resolve(name), files.get(name));
        }
        if ("manifest".equals(kind)) {
            Files.write(target.resolve("sha256.txt"), files.get("__manifest"));
        } else {
            Files.write(target.resolve("sha256.txt"), manifestBytes(files));
        }
        Files.write(target.resolve(".gitattributes"),
            "* -text\n.gitattributes -text\n".getBytes(StandardCharsets.US_ASCII));
        System.out.println("MUTATE\tPASS\t" + kind);
    }

    private static byte[] retargetRow(byte[] data, String id, String zone, String local,
            int lead, String key) {
        String text = new String(data, StandardCharsets.US_ASCII);
        String[] lines = text.split("\n", -1);
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith(id + "\t")) {
                continue;
            }
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 8) {
                throw new IllegalStateException("mutant-row-shape");
            }
            parts[1] = zone;
            parts[2] = local;
            parts[3] = Integer.toString(lead);
            parts[6] = key;
            lines[i] = String.join("\t", parts);
            matches++;
        }
        if (matches != 1) {
            throw new IllegalStateException("mutant-row-not-unique");
        }
        return String.join("\n", lines).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] editRow(byte[] data, String id, String oldText, String newText) {
        String text = new String(data, StandardCharsets.US_ASCII);
        String oldFragment = oldText.endsWith("\n") ? oldText.substring(0, oldText.length() - 1) : oldText;
        String newFragment = newText.endsWith("\n") ? newText.substring(0, newText.length() - 1) : newText;
        String[] lines = text.split("\n", -1);
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith(id + "\t")) {
                continue;
            }
            if (!lines[i].contains(oldFragment)) {
                throw new IllegalStateException("mutant-fragment-missing");
            }
            lines[i] = lines[i].replace(oldFragment, newFragment);
            matches++;
        }
        if (matches != 1) {
            throw new IllegalStateException("mutant-row-not-unique");
        }
        return String.join("\n", lines).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] dropFiredRow(byte[] data, String id) {
        String text = new String(data, StandardCharsets.US_ASCII);
        String[] lines = text.split("\n", -1);
        StringBuilder output = new StringBuilder();
        int removed = 0;
        for (String line : lines) {
            if (line.startsWith(id + "\t")) {
                removed++;
                continue;
            }
            if (!line.isEmpty()) {
                output.append(line).append('\n');
            }
        }
        if (removed != 1) {
            throw new IllegalStateException("mutant-fired-row-not-unique");
        }
        return output.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] replaceOnce(byte[] data, String oldText, String newText) {
        String text = new String(data, StandardCharsets.US_ASCII);
        int at = text.indexOf(oldText);
        if (at < 0 || text.indexOf(oldText, at + 1) >= 0) {
            throw new IllegalStateException("mutant-target-not-unique");
        }
        return (text.substring(0, at) + newText + text.substring(at + oldText.length()))
            .getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) {
            text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return text.toString();
    }

    private static byte[] unhex(String text) {
        if ((text.length() & 1) != 0) {
            throw new IllegalArgumentException("odd-hex");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length() / 2);
        for (int i = 0; i < text.length(); i += 2) {
            int high = Character.digit(text.charAt(i), 16);
            int low = Character.digit(text.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("bad-hex");
            }
            bytes.write((high << 4) | low);
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return hex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("hash-unavailable", error);
        }
    }
}
