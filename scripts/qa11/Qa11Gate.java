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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA-11 host-side gate: deterministic, zero-dependency guide-state backup/restore QA.
 *
 * Modes:
 *   generate <dir>                  write the pinned scratch fixtures into <dir>
 *   verify <dir>                    regenerate in memory, compare bytes, run checks, exit 0 only when every
 *                                   positive check passes and every negative mutation control is RED
 *   mutate <src> <dst> <kind>       copy fixtures to scratch and apply one named semantic/byte mutation
 *
 * No Android SDK, Gradle, network or third-party library is used. The scratch PIN and the scratch recording-payload
 * marker are derived at run time; their literal values never appear in this source, the fixtures or the report.
 * Diagnostics carry check ids, counts and hashes only: no PIN, URL, credential or payload text.
 */
public final class Qa11Gate {
    private static final String HEADER = "TUNEROS-BACKUP 1";
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_RECORDS = 5_000;
    private static final int MAX_VALUE_CHARS = 256;
    private static final int MAX_GROUPS = 100;

    private static final int CHANNEL_COUNT = 25_000;
    private static final int FAVORITE_COUNT = 2_400;
    private static final int MAPPING_COUNT = 1_600;
    private static final int SERIES_COUNT = 600;
    private static final int GROUP_COUNT = 100;
    private static final int RESTRICTED_CHANNEL_COUNT = 300;
    private static final int SCHEDULED_GUIDE_COUNT = 1_800;
    private static final int ROW_CAP = 24;

    private static final String MIRROR_BACKUP_DIGEST = "842c4183f4beaf36058bf8e739ba28227f667979b6d196987203349d9492847d";

    private static final String[] FIXTURE_FILES = {
        "channels.tsv", "guide.tsv", "dimensions.tsv", "backup.txt", "corrupt.txt", "sha256.txt"
    };
    private static final String[] DATA_FILES = {
        "channels.tsv", "guide.tsv", "dimensions.tsv", "backup.txt", "corrupt.txt"
    };

    private static final long SEED = 20_261_123L;
    private static final Pattern CREDENTIAL_LIKE =
        Pattern.compile("(?i)(pass(word|wd)?|token|secret|api[_-]?key|authorization)\\s*[=:]");

    private Qa11Gate() { }

    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("generate")) {
                generate(Paths.get(args[1]));
            } else if ((args.length == 2 && args[0].equals("verify")) ||
                       (args.length == 2 && args[0].equals("verify-mutant"))) {
                verify(Paths.get(args[1]), args[0].equals("verify-mutant"));
            } else if (args.length == 4 && args[0].equals("mutate")) {
                mutate(Paths.get(args[1]), Paths.get(args[2]), args[3]);
            } else {
                System.out.println("usage: Qa11Gate generate|verify <dir> | mutate <src> <dst> <kind>");
                System.exit(2);
            }
        } catch (Exception error) {
            System.out.println("FIRST\tharness-exception");
            System.out.println("FAIL\tharness-exception\t" + error.getClass().getSimpleName());
            System.out.println("SUMMARY\tpositive=0/0\tnegative-red=0/0\ttotal=0\tresult=FAIL");
            System.exit(3);
        }
    }

    // ------------------------------------------------------------------ fixture generation

    private static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, byte[]> files = buildFixtures();
        for (String name : FIXTURE_FILES) {
            Files.write(dir.resolve(name), files.get(name));
        }
        System.out.println("GENERATED\tfiles=" + FIXTURE_FILES.length +
            "\tbytes=" + totalBytes(files) + "\trecords=" + recordCount(text(files.get("backup.txt"))));
        for (String name : DATA_FILES) {
            System.out.println("SHA256\t" + name + "\t" + sha256Hex(files.get(name)));
        }
    }

    private static Map<String, byte[]> buildFixtures() {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("channels.tsv", buildChannels());

        ByteArrayOutputStream guide = new ByteArrayOutputStream();
        for (int i = 0; i < SCHEDULED_GUIDE_COUNT; i++) {
            guide.writeBytes(line(String.format(Locale.ROOT, "gd%05d", i)));
        }
        files.put("guide.tsv", guide.toByteArray());

        StringBuilder dimensions = new StringBuilder();
        dimension(dimensions, "channel-count", CHANNEL_COUNT);
        dimension(dimensions, "favorite-count", FAVORITE_COUNT);
        dimension(dimensions, "mapping-count", MAPPING_COUNT);
        dimension(dimensions, "series-count", SERIES_COUNT);
        dimension(dimensions, "group-count", GROUP_COUNT);
        dimension(dimensions, "restricted-channel-count", RESTRICTED_CHANNEL_COUNT);
        dimension(dimensions, "scheduled-guide-count", SCHEDULED_GUIDE_COUNT);
        dimension(dimensions, "row-cap", ROW_CAP);
        files.put("dimensions.tsv", dimensions.toString().getBytes(StandardCharsets.UTF_8));

        byte[] backup = encode(buildState()).getBytes(StandardCharsets.UTF_8);
        files.put("backup.txt", backup);
        files.put("corrupt.txt", buildCorruptCases().getBytes(StandardCharsets.UTF_8));

        StringBuilder manifest = new StringBuilder();
        for (String name : DATA_FILES) {
            manifest.append(sha256Hex(files.get(name))).append("  ").append(name).append('\n');
        }
        files.put("sha256.txt", manifest.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static byte[] buildChannels() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            String row = String.format(Locale.ROOT, "ch%05d\tQA Channel %05d\tgd%05d\tgrp%03d\n",
                i, i, i % 20_000, i % 200);
            out.writeBytes(row.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** The exact state the fixture backup encodes. Kept identical to the Kotlin mirror in app/src/androidTest. */
    private static State buildState() {
        State state = new State();
        for (int i = 0; i < FAVORITE_COUNT * 2; i += 2) {
            state.favorites.add(String.format(Locale.ROOT, "ch%05d", i));
        }
        for (int k = 0; k < MAPPING_COUNT; k++) {
            String guideId = k % 3 == 0
                ? String.format(Locale.ROOT, "gd%05d", k)
                : String.format(Locale.ROOT, "gd%05d", 20_000 + k);
            state.mappings.put(String.format(Locale.ROOT, "ch%05d", k * 7), guideId);
        }
        for (int s = 0; s < SERIES_COUNT; s++) {
            String title = String.format(Locale.ROOT, "Show %04d", s);
            String channel = s % 2 == 0 ? String.format(Locale.ROOT, "ch%05d", s * 11) : "";
            state.series.add(new String[]{title, channel});
        }
        for (int g = 0; g < GROUP_COUNT; g++) {
            state.groups.add(g % 2 == 0
                ? String.format(Locale.ROOT, "  GRP%03d ", g)
                : String.format(Locale.ROOT, "grp%03d", g));
        }
        for (int r = 0; r < RESTRICTED_CHANNEL_COUNT; r++) {
            String name = String.format(Locale.ROOT, "QA Channel %05d", r * 37);
            state.restrictedChannels.add(r % 2 == 0 ? "  " + name.toUpperCase(Locale.ROOT) + " " : name);
        }
        return state;
    }

    private static String buildCorruptCases() {
        StringBuilder out = new StringBuilder();
        corrupt(out, "bad-header", "TUNEROS-BACKUP 0\nfavorite\tch00000\n");
        corrupt(out, "unknown-kind", HEADER + "\nlegacy\tch00000\n");
        corrupt(out, "url-value", HEADER + "\nfavorite\thttp://scratch.invalid/list.m3u8\n");
        corrupt(out, "credential-value", HEADER + "\nmapping\tch00000\ttoken=SCRATCH\n");
        corrupt(out, "bad-escape", HEADER + "\nfavorite\tch0000\\q\n");
        corrupt(out, "empty-value", HEADER + "\nfavorite\t\n");
        corrupt(out, "too-many-fields", HEADER + "\nfavorite\tch00000\textra\n");
        corrupt(out, "oversized-value", HEADER + "\nfavorite\t" + repeat('x', MAX_VALUE_CHARS + 1) + "\n");
        corrupt(out, "empty-group", HEADER + "\nrestricted\t   \n");
        return out.toString();
    }

    private static void corrupt(StringBuilder out, String name, String text) {
        out.append("# name=").append(name).append('\n').append(text);
    }

    // ------------------------------------------------------------------ verification

    private static void verify(Path dir, boolean mutantMode) throws IOException {
        Map<String, byte[]> expected = buildFixtures();
        Map<String, byte[]> actual = new LinkedHashMap<>();
        for (String name : FIXTURE_FILES) {
            Path file = dir.resolve(name);
            actual.put(name, Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0]);
        }

        List<Check> positive = new ArrayList<>();
        List<Check> negative = new ArrayList<>();

        if (!mutantMode) {
            boolean identical = true;
            for (String name : FIXTURE_FILES) identical &= Arrays.equals(expected.get(name), actual.get(name));
            positive.add(check("fixture-regeneration-byte-identical", identical,
                "files=" + FIXTURE_FILES.length + " regenerated bytes equal committed bytes"));
        }

        boolean manifestPinned = true;
        String manifest = text(actual.get("sha256.txt"));
        List<String> manifestLines = nonEmptyLines(manifest);
        if (manifestLines.size() != DATA_FILES.length) manifestPinned = false;
        for (String line : manifestLines) {
            String[] parts = line.split("  ", -1);
            if (parts.length != 2 || !Arrays.asList(DATA_FILES).contains(parts[1])) {
                manifestPinned = false;
                continue;
            }
            manifestPinned &= parts[0].equals(sha256Hex(expected.get(parts[1])));
        }
        if (mutantMode) {
            boolean consistent = true;
            for (String line : manifestLines) {
                String[] parts = line.split("  ", -1);
                if (parts.length != 2) { consistent = false; continue; }
                consistent &= parts[0].equals(sha256Hex(actual.get(parts[1])));
            }
            positive.add(check("mutant-manifest-consistent", consistent,
                "lines=" + manifestLines.size() + " rewritten hashes match mutant bytes"));
        } else {
            positive.add(check("fixture-sha256-pinned", manifestPinned,
                "lines=" + manifestLines.size() + " expected=" + DATA_FILES.length));
        }

        Map<String, String> dimensions = parseDimensions(text(actual.get("dimensions.tsv")));
        boolean dimensionsPinned = dimensions.size() == 8 &&
            intValue(dimensions, "channel-count") == CHANNEL_COUNT &&
            intValue(dimensions, "favorite-count") == FAVORITE_COUNT &&
            intValue(dimensions, "mapping-count") == MAPPING_COUNT &&
            intValue(dimensions, "series-count") == SERIES_COUNT &&
            intValue(dimensions, "group-count") == GROUP_COUNT &&
            intValue(dimensions, "restricted-channel-count") == RESTRICTED_CHANNEL_COUNT &&
            intValue(dimensions, "scheduled-guide-count") == SCHEDULED_GUIDE_COUNT &&
            intValue(dimensions, "row-cap") == ROW_CAP;
        positive.add(check("dimensions-pinned", dimensionsPinned, "fields=" + dimensions.size()));

        String backup = text(actual.get("backup.txt"));
        int records = recordCount(backup);
        positive.add(check("backup-header-valid", firstLine(backup).equals(HEADER), "version=1"));
        positive.add(check("backup-record-cap-binds", records == MAX_RECORDS && records <= MAX_RECORDS,
            "records=" + records + " cap=" + MAX_RECORDS));
        positive.add(check("backup-raw-url-free", !backup.contains("://"), "url-markers=0"));
        positive.add(check("backup-raw-credential-free", !CREDENTIAL_LIKE.matcher(backup).find(),
            "credential-markers=0"));
        positive.add(check("backup-raw-escapes-valid", rawEscapesValid(backup), "escape-markers=0"));
        positive.add(check("backup-bytes-bounded", backup.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
            "bytes=" + backup.getBytes(StandardCharsets.UTF_8).length + " cap=" + MAX_BYTES));
        positive.add(check("backup-no-recording-payload", !backup.contains(payloadMarker()), "payload-markers=0"));

        State state = null;
        String decodeCode = "accepted";
        try {
            state = decode(backup);
        } catch (Reject error) {
            decodeCode = error.code;
        }
        positive.add(check("backup-decode-all-valid", state != null, "decision=" + decodeCode));

        if (state == null) {
            state = new State();
        }

        String reencoded = encode(state);
        State canonical = buildState();
        canonical.groups = normalized(canonical.groups);
        canonical.restrictedChannels = normalized(canonical.restrictedChannels);
        String canonicalText = encode(canonical);
        positive.add(check("backup-roundtrip-canonical-identity",
            reencoded.equals(canonicalText) && !backup.equals(canonicalText),
            "canonical-bytes=" + canonicalText.getBytes(StandardCharsets.UTF_8).length +
            " raw-bytes=" + backup.getBytes(StandardCharsets.UTF_8).length));
        if (!mutantMode) {
            positive.add(check("mirror-backup-digest-pinned", sha256Hex(backup.getBytes(StandardCharsets.UTF_8))
                .equals(MIRROR_BACKUP_DIGEST), "mirror=androidTest digest"));
        }

        List<String[]> channels = parseChannels(text(actual.get("channels.tsv")));
        Set<String> scheduled = parseGuide(text(actual.get("guide.tsv")));
        positive.add(check("channel-catalog-bounded", channels.size() == CHANNEL_COUNT &&
            scheduled.size() == SCHEDULED_GUIDE_COUNT,
            "channels=" + channels.size() + " scheduled-guides=" + scheduled.size()));

        boolean favoritesRestored = state.favorites.size() == FAVORITE_COUNT;
        for (int i = 0; i < FAVORITE_COUNT && favoritesRestored; i++) {
            favoritesRestored = state.favorites.contains(String.format(Locale.ROOT, "ch%05d", i * 2));
        }
        positive.add(check("favorites-restored", favoritesRestored,
            "favorites=" + state.favorites.size() + " expected=" + FAVORITE_COUNT));

        List<String> visible = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (String[] channel : channels) {
            String mapped = state.mappings.get(channel[0]);
            String guideId = mapped == null ? channel[2] : mapped;
            if (scheduled.contains(guideId)) visible.add(channel[0]);
            else hidden.add(channel[0]);
        }
        positive.add(check("hidden-epg-visibility-restored",
            !visible.isEmpty() && !hidden.isEmpty() && visible.size() + hidden.size() == channels.size(),
            "visible=" + visible.size() + " hidden=" + hidden.size()));

        int favoriteVisible = 0;
        for (String id : visible) if (state.favorites.contains(id)) favoriteVisible++;
        positive.add(check("favorite-visible-subset", favoriteVisible > 0 && favoriteVisible < FAVORITE_COUNT,
            "favorite-visible=" + favoriteVisible + " favorites=" + FAVORITE_COUNT));

        positive.add(check("recycled-row-window-bounded",
            recycledWindow(visible.size(), ROW_CAP), "rowCap=" + ROW_CAP + " visible=" + visible.size()));

        boolean groupsRestored = state.groups.size() == GROUP_COUNT;
        for (int g = 0; g < GROUP_COUNT && groupsRestored; g++) {
            groupsRestored = state.groups.contains(String.format(Locale.ROOT, "grp%03d", g));
        }
        positive.add(check("restricted-groups-normalized", groupsRestored,
            "groups=" + state.groups.size() + " cap=" + MAX_GROUPS));

        boolean policyRestored = requiresPin("grp000", state, false) &&
            !requiresPin("grp000", state, true) &&
            !requiresPin("grp200", state, false) &&
            requiresPinForChannel("QA Channel 00000", "grp200", state, false) &&
            !requiresPinForChannel("QA Channel 00000", "grp200", state, true);
        positive.add(check("parental-policy-after-restore", policyRestored,
            "locked=true unlocked=false channel-rule=true"));

        Map<String, String> corrupt = parseCorruptCases(text(actual.get("corrupt.txt")));
        Map<String, String> corruptCodes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : corrupt.entrySet()) {
            try {
                decode(entry.getValue());
                corruptCodes.put(entry.getKey(), "accepted");
            } catch (Reject error) {
                corruptCodes.put(entry.getKey(), error.code);
            }
        }
        boolean closed = corrupt.size() == 9;
        for (String code : corruptCodes.values()) closed &= !code.equals("accepted");
        positive.add(check("corrupt-input-fail-closed", closed,
            "cases=" + corrupt.size() + " rejected=" + (corrupt.size() - countAccepted(corruptCodes))));

        Map<String, String> expectedCodes = new LinkedHashMap<>();
        expectedCodes.put("bad-header", "rejected-header");
        expectedCodes.put("unknown-kind", "rejected-kind");
        expectedCodes.put("url-value", "rejected-url");
        expectedCodes.put("credential-value", "rejected-credential");
        expectedCodes.put("bad-escape", "rejected-escape");
        expectedCodes.put("empty-value", "rejected-empty");
        expectedCodes.put("too-many-fields", "rejected-fields");
        expectedCodes.put("oversized-value", "rejected-length");
        expectedCodes.put("empty-group", "rejected-group-empty");
        boolean reasonsPinned = corruptCodes.equals(expectedCodes);
        positive.add(check("corrupt-reasons-pinned", reasonsPinned,
            "cases=" + corruptCodes.size() + " pinned=" + expectedCodes.size()));

        String pin = pin();
        String marker = payloadMarker();
        boolean fixturesClean = true;
        for (byte[] bytes : expected.values()) {
            String value = new String(bytes, StandardCharsets.UTF_8);
            fixturesClean &= !value.contains(pin) && !value.contains(marker);
        }
        positive.add(check("fixtures-secret-free", fixturesClean, "pin-and-payload-markers=0"));

        // Negative controls: each boolean must be false, otherwise a detector is broken.
        byte[] flipped = expected.get("backup.txt").clone();
        flipped[0] ^= 0x01;
        negative.add(check("red-byte-mutation", Arrays.equals(flipped, actual.get("backup.txt")),
            "one-byte mutation must break byte identity"));
        negative.add(check("red-sha-mutation",
            sha256Hex(flipped).equals(sha256Hex(expected.get("backup.txt"))),
            "one-byte mutation must break the pinned digest"));
        State mutatedState = new State();
        mutatedState.favorites.addAll(state.favorites);
        mutatedState.favorites.remove(String.format(Locale.ROOT, "ch%05d", 0));
        negative.add(check("red-roundtrip-mutation",
            encode(mutatedState).equals(canonicalText), "mutation must break re-encode identity"));
        negative.add(check("red-hidden-count-mutation",
            visible.size() == hidden.size(), "visible/hidden counts must differ"));
        negative.add(check("red-group-cap-mutation",
            state.groups.size() > MAX_GROUPS, "group count must not exceed the cap"));
        negative.add(check("red-permissive-decoder-accepts-corrupt",
            !permissiveAccepts(corrupt.get("bad-header")),
            "lax decoder must accept a case the strict decoder rejects"));
        String leakBase = render(positive, negative);
        negative.add(check("red-pin-leak-injection", secretFree(leakBase + "x=" + pin, pin, marker),
            "injected PIN must be detected"));
        negative.add(check("red-url-leak-injection", secretFree(leakBase + "x=http://x.invalid/a", pin, marker),
            "injected URL must be detected"));
        negative.add(check("red-credential-leak-injection",
            secretFree(leakBase + "x=token=SCRATCH", pin, marker),
            "injected credential must be detected"));
        negative.add(check("red-payload-leak-injection", secretFree(leakBase + "x=" + marker, pin, marker),
            "injected payload marker must be detected"));

        List<String> ids = new ArrayList<>();
        for (Check c : positive) ids.add(c.id);
        for (Check c : negative) ids.add(c.id);
        Set<String> unique = new LinkedHashSet<>(ids);
        positive.add(check("check-ids-unique", unique.size() == ids.size(),
            "ids=" + ids.size() + " unique=" + unique.size()));

        String report = render(positive, negative);
        positive.add(check("diagnostics-secret-free", secretFree(report, pin, marker),
            "pin=0 url=0 credential=0 payload=0"));
        report = render(positive, negative);

        int passed = 0, red = 0;
        for (Check c : positive) if (c.pass) passed++;
        for (Check c : negative) if (!c.pass) red++;
        boolean ok = passed == positive.size() && red == negative.size();
        String firstFailure = firstFailure(positive, negative);
        if (firstFailure != null) System.out.println("FIRST\t" + firstFailure);
        System.out.print(report);
        System.out.println("SUMMARY\tpositive=" + passed + "/" + positive.size() +
            "\tnegative-red=" + red + "/" + negative.size() +
            "\ttotal=" + (positive.size() + negative.size()) +
            "\tresult=" + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }

    private static String firstFailure(List<Check> positive, List<Check> negative) {
        for (Check c : positive) if (!c.pass) return c.id;
        for (Check c : negative) if (c.pass) return c.id;
        return null;
    }

    private static String render(List<Check> positive, List<Check> negative) {
        StringBuilder out = new StringBuilder();
        for (Check c : positive) out.append(c.pass ? "PASS" : "FAIL").append('\t').append(c.id)
            .append('\t').append(c.detail).append('\n');
        for (Check c : negative) out.append(c.pass ? "UNEXPECTED-PASS" : "FAIL").append('\t').append(c.id)
            .append('\t').append(c.detail).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ on-disk mutation for RED controls

    private static void mutate(Path src, Path dst, String kind) throws IOException {
        Files.createDirectories(dst);
        for (String name : FIXTURE_FILES) {
            Files.copy(src.resolve(name), dst.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        String target = "backup.txt";
        byte[] backup = Files.readAllBytes(dst.resolve(target));
        boolean rewriteManifest = !kind.equals("flip");
        switch (kind) {
            case "flip":
                backup[0] ^= 0x01;
                break;
            case "header":
                backup = replace(backup, HEADER + "\n", "TUNEROS-BACKUP 0\n");
                break;
            case "url":
                backup = replace(backup, "favorite\tch00000\n",
                    "favorite\thttp://scratch.invalid/list.m3u8\n");
                break;
            case "credential":
                backup = replace(backup, "mapping\tch00000\tgd00000\n",
                    "mapping\tch00000\ttoken=SCRATCH\n");
                break;
            case "escape":
                backup = replace(backup, "favorite\tch00000\n", "favorite\tch0000\\q\n");
                break;
            case "cap":
                backup = concat(backup, "favorite\tch99999\n".getBytes(StandardCharsets.UTF_8));
                break;
            default:
                throw new IllegalArgumentException("unknown-mutant");
        }
        Files.write(dst.resolve(target), backup);
        if (rewriteManifest) {
            StringBuilder manifest = new StringBuilder();
            for (String name : DATA_FILES) {
                manifest.append(sha256Hex(Files.readAllBytes(dst.resolve(name))))
                    .append("  ").append(name).append('\n');
            }
            Files.write(dst.resolve("sha256.txt"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("MUTATED\t" + kind + "\t" + target + "\tbytes=" + backup.length);
    }

    private static byte[] replace(byte[] source, String from, String to) {
        String text = new String(source, StandardCharsets.UTF_8);
        if (!text.contains(from)) throw new IllegalArgumentException("mutant-target-missing");
        return text.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    // ------------------------------------------------------------------ strict backup codec (mirrors TunerBackup)

    private static String encode(State state) {
        List<String> lines = new ArrayList<>();
        List<String> favorites = new ArrayList<>(state.favorites);
        Collections.sort(favorites);
        for (String value : favorites) lines.add("favorite\t" + field(value, false));

        List<Map.Entry<String, String>> mappings = new ArrayList<>(state.mappings.entrySet());
        mappings.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, String> entry : mappings) {
            lines.add("mapping\t" + field(entry.getKey(), false) + "\t" + field(entry.getValue(), false));
        }

        List<String[]> series = new ArrayList<>(state.series);
        series.sort(Comparator.comparing((String[] row) -> row[0]).thenComparing(row -> row[1]));
        for (String[] row : series) lines.add("series\t" + field(row[0], false) + "\t" + field(row[1], true));

        List<String> groups = new ArrayList<>(state.groups);
        Collections.sort(groups);
        for (String value : groups) lines.add("restricted\t" + field(value, false));

        List<String> channels = new ArrayList<>(state.restrictedChannels);
        Collections.sort(channels);
        for (String value : channels) lines.add("restricted-channel\t" + field(value, false));

        if (lines.size() > MAX_RECORDS) throw new Reject("rejected-cap");
        String text = HEADER + "\n";
        for (String line : lines) text += line + "\n";
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new Reject("rejected-size");
        return text;
    }

    private static State decode(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new Reject("rejected-size");
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].equals(HEADER)) throw new Reject("rejected-header");
        State state = new State();
        int records = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            records++;
            if (records > MAX_RECORDS) throw new Reject("rejected-cap");
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "favorite":
                    requireShape(fields, 2);
                    state.favorites.add(value(fields[1], false));
                    break;
                case "mapping":
                    requireShape(fields, 3);
                    state.mappings.put(value(fields[1], false), value(fields[2], false));
                    break;
                case "series": {
                    requireShape(fields, 3);
                    String channel = value(fields[2], true);
                    state.series.add(new String[]{value(fields[1], false), channel});
                    break;
                }
                case "restricted":
                    requireShape(fields, 2);
                    state.groups.add(value(fields[1], false));
                    break;
                case "restricted-channel":
                    requireShape(fields, 2);
                    state.restrictedChannels.add(value(fields[1], false));
                    break;
                default:
                    throw new Reject("rejected-kind");
            }
        }
        Set<String> groups = new LinkedHashSet<>();
        for (String group : state.groups) {
            String key = normalize(group);
            if (key.isEmpty()) throw new Reject("rejected-group-empty");
            groups.add(key);
        }
        if (groups.size() > MAX_GROUPS) throw new Reject("rejected-group-cap");
        Set<String> channels = new LinkedHashSet<>();
        for (String channel : state.restrictedChannels) {
            String key = normalize(channel);
            if (key.isEmpty()) throw new Reject("rejected-group-empty");
            channels.add(key);
        }
        state.groups = groups;
        state.restrictedChannels = channels;
        return state;
    }

    private static void requireShape(String[] fields, int size) {
        if (fields.length != size) throw new Reject("rejected-fields");
    }

    private static String field(String value, boolean allowEmpty) {
        checkValue(value, allowEmpty);
        return value.replace("\\", "\\\\").replace("\t", "\\t")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String value(String raw, boolean allowEmpty) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (current != '\\') {
                out.append(current);
                continue;
            }
            if (i + 1 >= raw.length()) throw new Reject("rejected-escape");
            char next = raw.charAt(++i);
            switch (next) {
                case '\\': out.append('\\'); break;
                case 't': out.append('\t'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                default: throw new Reject("rejected-escape");
            }
        }
        String result = out.toString();
        checkValue(result, allowEmpty);
        return result;
    }

    private static void checkValue(String value, boolean allowEmpty) {
        if (!allowEmpty && value.isEmpty()) throw new Reject("rejected-empty");
        if (value.length() > MAX_VALUE_CHARS) throw new Reject("rejected-length");
        if (value.contains("://")) throw new Reject("rejected-url");
        if (CREDENTIAL_LIKE.matcher(value).find()) throw new Reject("rejected-credential");
    }

    private static boolean rawEscapesValid(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\\') continue;
            if (i + 1 >= text.length()) return false;
            char next = text.charAt(i + 1);
            if (next != '\\' && next != 't' && next != 'n' && next != 'r') return false;
            i++;
        }
        return true;
    }

    private static boolean permissiveAccepts(String text) {
        if (text == null) return false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("favorite\t") && line.length() > "favorite\t".length()) return true;
        }
        return false;
    }

    private static boolean requiresPin(String group, State state, boolean unlocked) {
        if (unlocked || group == null) return false;
        String key = normalize(group);
        for (String restricted : state.groups) if (normalize(restricted).equals(key)) return true;
        return false;
    }

    private static boolean requiresPinForChannel(String name, String group, State state, boolean unlocked) {
        if (unlocked) return false;
        if (requiresPin(group, state, false)) return true;
        String key = normalize(name);
        for (String restricted : state.restrictedChannels) if (normalize(restricted).equals(key)) return true;
        return false;
    }

    private static Set<String> normalized(Set<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) out.add(normalize(value));
        return out;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ fixture parsers

    private static List<String[]> parseChannels(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length == 4) rows.add(fields);
        }
        return rows;
    }

    private static Set<String> parseGuide(String text) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : text.split("\n", -1)) if (!line.isEmpty()) ids.add(line);
        return ids;
    }

    private static Map<String, String> parseDimensions(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int split = line.indexOf('=');
            if (split <= 0) continue;
            out.put(line.substring(0, split), line.substring(split + 1));
        }
        return out;
    }

    private static Map<String, String> parseCorruptCases(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String name = null;
        StringBuilder block = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("# name=")) {
                if (name != null) out.put(name, block.toString());
                name = line.substring("# name=".length());
                block = new StringBuilder();
            } else if (name != null) {
                block.append(line).append('\n');
            }
        }
        if (name != null) out.put(name, block.toString());
        return out;
    }

    // ------------------------------------------------------------------ primitives

    private static boolean recycledWindow(int visibleSize, int rowCap) {
        if (visibleSize <= 0 || rowCap <= 0 || Math.min(rowCap, visibleSize) < rowCap) return false;
        int[] slots = new int[rowCap];
        int[] offsets = {0, 1, 7, 100, 1_000, visibleSize / 2, Math.max(0, visibleSize - 1)};
        for (int offset : offsets) {
            int first = Math.max(0, Math.min(offset, visibleSize - 1));
            int rows = Math.min(rowCap, visibleSize - first);
            if (rows > slots.length) return false;
            for (int row = 0; row < rows; row++) {
                int index = first + row;
                if (index < 0 || index >= visibleSize) return false;
                slots[row] = index;
            }
        }
        return true;
    }

    private static boolean secretFree(String report, String pin, String marker) {
        return report != null && !report.contains(pin) && !report.contains("://") &&
            !report.contains(marker) && !CREDENTIAL_LIKE.matcher(report).find();
    }

    private static String pin() {
        long value = Math.floorMod(SEED * 31L + 7L, 100_000_000L);
        return String.format(Locale.ROOT, "%08d", value);
    }

    private static String payloadMarker() {
        return "qarec-" + Math.floorMod(SEED, 1_000_000L);
    }

    private static int countAccepted(Map<String, String> codes) {
        int count = 0;
        for (String code : codes.values()) if (code.equals("accepted")) count++;
        return count;
    }

    private static int recordCount(String text) {
        int count = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 1; i < lines.length; i++) if (!lines[i].isEmpty()) count++;
        return count;
    }

    private static String firstLine(String text) {
        String[] lines = text.split("\n", -1);
        return lines.length == 0 ? "" : lines[0];
    }

    private static List<String> nonEmptyLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) if (!line.isEmpty()) out.add(line);
        return out;
    }

    private static int intValue(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static byte[] line(String value) { return (value + "\n").getBytes(StandardCharsets.UTF_8); }

    private static void dimension(StringBuilder out, String key, int value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error.getClass().getSimpleName());
        }
    }

    private static long totalBytes(Map<String, byte[]> files) {
        long total = 0;
        for (byte[] bytes : files.values()) total += bytes.length;
        return total;
    }

    private static Check check(String id, boolean pass, String detail) { return new Check(id, pass, detail); }

    private static final class Check {
        final String id;
        final boolean pass;
        final String detail;
        Check(String id, boolean pass, String detail) {
            this.id = id;
            this.pass = pass;
            this.detail = detail;
        }
    }

    private static final class State {
        final Set<String> favorites = new LinkedHashSet<>();
        final Map<String, String> mappings = new LinkedHashMap<>();
        final List<String[]> series = new ArrayList<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> restrictedChannels = new LinkedHashSet<>();
    }

    private static final class Reject extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        Reject(String code) { super(code); this.code = code; }
    }
}
