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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA-06: deterministic, zero-dependency JVM gate for a LARGE scratch Classic Cable EPG.
 *
 * The fixture is synthetic and bounded: 25,000 channels and 250,000 programmes in a fixed
 * five-hour grid, viewed through a three-hour guide window and a 24-row paged guide model.
 * Generation is byte-identical; verification covers ordering, counts, bounded page indices,
 * row counts and timeline cell indices, contiguous (unclipped) visible rows, and report hygiene.
 * Four in-memory mutations are negative controls and must be rejected by named checks.
 *
 * No network, device, credential, provider address, DRM key or media payload is used.
 * Modes:
 *   --generate FILE                       write the canonical scratch fixture
 *   --verify FILE                         verify a fixture (exit 0 only for PASS)
 *   --all FIXTURE MANIFEST REPORT         fixture match + manifest + positive + negatives
 */
public final class Qa06LargeEpgGate {
    private static final int CHANNEL_COUNT = 25_000;
    private static final int PROGRAMMES_PER_CHANNEL = 10;
    private static final int PAGE_ROWS = 24;
    private static final int MAX_CELLS = 8;
    private static final int SLOT_SECONDS = 1_800;
    private static final long BASE = 1_800_000_000L;
    private static final long WINDOW_FROM = BASE + 2L * SLOT_SECONDS;
    private static final long WINDOW_TO = WINDOW_FROM + 6L * SLOT_SECONDS;
    private static final int PAGE_COUNT = (CHANNEL_COUNT + PAGE_ROWS - 1) / PAGE_ROWS;
    private static final int TOTAL_PROGRAMMES = CHANNEL_COUNT * PROGRAMMES_PER_CHANNEL;
    private static final int MAX_FIXTURE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_REPORT_BYTES = 16 * 1024;
    private static final int SAMPLE_STEP = 97;
    private static final String MARKER = "QUA06-LARGE-EPG-V1";
    // URL-like and credential-like tokens. All fixture references use a non-URL loopback scheme.
    private static final Pattern FORBIDDEN = Pattern.compile(
        "(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|drm=|provider\\.)");

    private Qa06LargeEpgGate() {}

    private static final class Result {
        int checks;
        int failures;
        String firstCode = "";
        final List<String> details = new ArrayList<>();

        boolean pass() { return failures == 0; }
    }

    private static final class Entry {
        final long start;
        final long end;

        Entry(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static void check(Result result, boolean ok, String code, String detail) {
        result.checks++;
        if (ok) return;
        result.failures++;
        if (result.firstCode.isEmpty()) result.firstCode = code;
        if (result.details.size() < 8) result.details.add(code + " " + detail);
    }

    private static String id(int index) {
        return String.format(Locale.ROOT, "c-%05d", index);
    }

    private static String guide(int index) {
        return String.format(Locale.ROOT, "g-%05d", index);
    }

    private static String name(int index) {
        return String.format(Locale.ROOT, "QA06 Channel %05d", index);
    }

    private static String title(int index, int slot) {
        return String.format(Locale.ROOT, "QA06 Show %05d-%02d", index, slot);
    }

    /** The canonical fixture bytes. Always LF-only and newline-terminated. */
    static String generate() {
        StringBuilder text = new StringBuilder(16 * 1024 * 1024);
        text.append("# QA-06 deterministic synthetic large EPG scratch fixture; loopback-only; do not edit by hand.\n");
        text.append("MARKER ").append(MARKER).append('\n');
        text.append("VERSION 1\n");
        text.append("COUNTS ").append(CHANNEL_COUNT).append(' ').append(TOTAL_PROGRAMMES).append('\n');
        text.append("WINDOW ").append(WINDOW_FROM).append(' ').append(WINDOW_TO).append('\n');
        text.append("PAGE_ROWS ").append(PAGE_ROWS).append('\n');
        text.append("MAX_CELLS ").append(MAX_CELLS).append('\n');
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            text.append("CH ").append(id(i)).append('|').append(name(i)).append('|')
                .append(guide(i)).append("|scratch-loopback:qa06/").append(String.format(Locale.ROOT, "%05d", i))
                .append('\n');
        }
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            for (int slot = 0; slot < PROGRAMMES_PER_CHANNEL; slot++) {
                long start = BASE + (long) slot * SLOT_SECONDS;
                text.append("PG ").append(guide(i)).append('|').append(title(i, slot)).append('|')
                    .append(start).append('|').append(start + SLOT_SECONDS).append('\n');
            }
        }
        for (int page : pageIndices()) {
            int first = page * PAGE_ROWS;
            int count = Math.min(PAGE_ROWS, CHANNEL_COUNT - first);
            text.append("PAGE ").append(page).append('|').append(first).append('|').append(count)
                .append('|').append(id(first)).append('|').append(id(first + count - 1)).append('\n');
        }
        if (text.length() > MAX_FIXTURE_BYTES) throw new IllegalStateException("fixture byte bound exceeded");
        return text.toString();
    }

    private static String manifestText() throws Exception {
        return "QUA06-LARGE-EPG-V1\n"
            + "channels=" + CHANNEL_COUNT + "\n"
            + "programmes=" + TOTAL_PROGRAMMES + "\n"
            + "window_from=" + WINDOW_FROM + "\n"
            + "window_to=" + WINDOW_TO + "\n"
            + "page_rows=" + PAGE_ROWS + "\n"
            + "max_cells=" + MAX_CELLS + "\n"
            + "sha256=" + digest(generate()) + "\n";
    }

    static String digest(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    private static Set<Integer> sampleIndices() {
        Set<Integer> indices = new LinkedHashSet<>();
        for (int page : pageIndices()) {
            int first = page * PAGE_ROWS;
            indices.add(first);
            indices.add(Math.min(CHANNEL_COUNT - 1, first + Math.min(PAGE_ROWS, CHANNEL_COUNT - first) - 1));
        }
        for (int i = 0; i < CHANNEL_COUNT; i += SAMPLE_STEP) indices.add(i);
        indices.add(CHANNEL_COUNT - 1);
        return indices;
    }

    private static int[] pageIndices() {
        return new int[]{0, 1, PAGE_COUNT / 2, PAGE_COUNT - 1};
    }

    /** Verify fixture content. No script, tool, dependency or external state is consulted. */
    static Result verify(String fixtureText) {
        Result result = new Result();
        Set<Integer> samples = sampleIndices();
        Set<String> sampledGuides = new HashSet<>();
        for (int index : samples) sampledGuides.add(guide(index));

        Map<String, List<Entry>> sampleEntries = new HashMap<>();
        Map<String, Long> lastStart = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        int channels = 0;
        int programmes = 0;
        int pages = 0;
        int marker = 0;
        int version = 0;
        int countChannels = -1;
        int countProgrammes = -1;
        long windowFrom = 0;
        long windowTo = 0;
        int pageRows = -1;
        int maxCells = -1;
        boolean structureOk = true;

        for (String raw : fixtureText.split("\n", -1)) {
            String line = raw.replace("\r", "");
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                check(result, !FORBIDDEN.matcher(line).find(), "PRIVACY_REF", "comment");
                continue;
            }
            check(result, !FORBIDDEN.matcher(line).find(), "PRIVACY_REF", "data line");
            String[] field = line.split("\\|", -1);
            String[] head = field[0].split(" ", -1);
            String kind = head[0];
            switch (kind) {
                case "MARKER":
                    marker++;
                    check(result, field.length == 1 && head.length == 2 && MARKER.equals(head[1]),
                        "MARKER", "field=" + head.length);
                    break;
                case "VERSION":
                    version = (int) parseLong(head.length > 1 ? head[1] : "");
                    break;
                case "COUNTS":
                    if (head.length >= 3) {
                        countChannels = (int) parseLong(head[1]);
                        countProgrammes = (int) parseLong(head[2]);
                    }
                    break;
                case "WINDOW":
                    if (head.length >= 3) {
                        windowFrom = parseLong(head[1]);
                        windowTo = parseLong(head[2]);
                    }
                    break;
                case "PAGE_ROWS":
                    pageRows = (int) parseLong(head.length > 1 ? head[1] : "");
                    break;
                case "MAX_CELLS":
                    maxCells = (int) parseLong(head.length > 1 ? head[1] : "");
                    break;
                case "CH": {
                    channels++;
                    String expected = id(channels - 1);
                    check(result, field.length == 4 && head.length == 2, "CH_FORMAT", "line=" + channels);
                    if (field.length != 4 || head.length != 2) { structureOk = false; break; }
                    check(result, expected.equals(head[1]), "CHANNEL_ORDER", "row=" + channels);
                    check(result, name(channels - 1).equals(field[1]), "CHANNEL_NAME", "row=" + channels);
                    check(result, guide(channels - 1).equals(field[2]), "CHANNEL_GUIDE", "row=" + channels);
                    check(result, ("scratch-loopback:qa06/" + String.format(Locale.ROOT, "%05d", channels - 1))
                        .equals(field[3]), "CHANNEL_REF", "row=" + channels);
                    break;
                }
                case "PG": {
                    programmes++;
                    check(result, field.length == 4 && head.length == 2, "PG_FORMAT", "line=" + programmes);
                    if (field.length != 4 || head.length != 2) { structureOk = false; break; }
                    String channelGuide = head[1];
                    long start = parseLong(field[2]);
                    long end = parseLong(field[3]);
                    check(result, end > start && end - start <= 2L * SLOT_SECONDS,
                        "PROGRAMME_DURATION", "programme=" + programmes);
                    Integer index = guideIndex(channelGuide);
                    check(result, index != null && index >= 0 && index < CHANNEL_COUNT,
                        "PROGRAMME_GUIDE", "programme=" + programmes);
                    if (index != null) {
                        Long previous = lastStart.get(channelGuide);
                        check(result, previous == null || start > previous, "PROGRAMME_ORDER",
                            "guide=" + channelGuide);
                        lastStart.put(channelGuide, start);
                        int count = (seen.getOrDefault(channelGuide, 0) + 1);
                        seen.put(channelGuide, count);
                        check(result, count <= PROGRAMMES_PER_CHANNEL, "PROGRAMME_COUNT_BOUND",
                            "guide=" + channelGuide);
                        check(result, title(index, count - 1).equals(field[1]), "PROGRAMME_TITLE",
                            "guide=" + channelGuide + " slot=" + count);
                    }
                    if (sampledGuides.contains(channelGuide) && end > WINDOW_FROM && start < WINDOW_TO) {
                        sampleEntries.computeIfAbsent(channelGuide, key -> new ArrayList<>())
                            .add(new Entry(start, end));
                    }
                    break;
                }
                case "PAGE": {
                    pages++;
                    check(result, field.length == 5 && head.length == 2, "PAGE_FORMAT", "line=" + pages);
                    if (field.length != 5 || head.length != 2) { structureOk = false; break; }
                    int page = (int) parseLong(head[1]);
                    int first = (int) parseLong(field[1]);
                    int count = (int) parseLong(field[2]);
                    check(result, count >= 1 && count <= PAGE_ROWS, "ROW_COUNT_BOUND",
                        "page=" + page + " count=" + count);
                    check(result, page >= 0 && page < PAGE_COUNT && first == page * PAGE_ROWS,
                        "PAGE_INDEX_BOUND", "page=" + page + " first=" + first);
                    check(result, first >= 0 && first + count <= CHANNEL_COUNT,
                        "PAGE_END_BOUND", "page=" + page + " end=" + (first + count));
                    check(result, id(first).equals(field[3]) && id(first + count - 1).equals(field[4]),
                        "PAGE_ORDER", "page=" + page);
                    break;
                }
                default:
                    check(result, false, "FORMAT", "kind=" + kind);
                    structureOk = false;
                    break;
            }
        }

        check(result, marker == 1, "MARKER_COUNT", "markers=" + marker);
        check(result, version == 1, "VERSION", "version=" + version);
        check(result, countChannels == CHANNEL_COUNT, "CHANNEL_COUNT", "header=" + countChannels);
        check(result, countProgrammes == TOTAL_PROGRAMMES, "PROGRAMME_COUNT", "header=" + countProgrammes);
        check(result, channels == CHANNEL_COUNT, "CHANNEL_COUNT", "records=" + channels);
        check(result, programmes == TOTAL_PROGRAMMES, "PROGRAMME_COUNT", "records=" + programmes);
        check(result, windowFrom == WINDOW_FROM && windowTo == WINDOW_TO && windowTo > windowFrom,
            "WINDOW", "from=" + windowFrom + " to=" + windowTo);
        check(result, pageRows == PAGE_ROWS, "PAGE_ROWS", "header=" + pageRows);
        check(result, maxCells == MAX_CELLS, "MAX_CELLS", "header=" + maxCells);
        check(result, pages == pageIndices().length, "PAGE_RECORDS", "pages=" + pages);
        check(result, structureOk, "STRUCTURE", "malformed records present");

        if (structureOk) {
            for (int page : pageIndices()) {
                int first = page * PAGE_ROWS;
                int count = Math.min(PAGE_ROWS, CHANNEL_COUNT - first);
                int last = first + count - 1;
                check(result, first >= 0 && last < CHANNEL_COUNT && count <= PAGE_ROWS,
                    "PAGE_BOUND", "page=" + page);
                check(result, id(first).compareTo(id(last)) < 0, "STABLE_ORDER", "page=" + page);
            }
            for (int index : samples) {
                List<Entry> entries = sampleEntries.get(guide(index));
                check(result, entries != null && !entries.isEmpty(), "ROW_PRESENT", "channel=" + id(index));
                if (entries == null || entries.isEmpty()) continue;
                entries.sort(Comparator.comparingLong(entry -> entry.start));
                long cursor = WINDOW_FROM;
                int cells = 0;
                boolean contiguous = true;
                for (Entry entry : entries) {
                    long start = Math.max(entry.start, WINDOW_FROM);
                    long end = Math.min(entry.end, WINDOW_TO);
                    if (end <= start) continue;
                    cells++;
                    if (start != cursor || end > WINDOW_TO) contiguous = false;
                    cursor = end;
                }
                check(result, contiguous && cursor == WINDOW_TO, "ROW_NOT_CONTIGUOUS", "channel=" + id(index));
                check(result, cells >= 1 && cells <= MAX_CELLS, "ROW_CELL_BOUND",
                    "channel=" + id(index) + " cells=" + cells);
                check(result, cells - 1 >= 0 && cells - 1 < MAX_CELLS, "INDEX_BOUND",
                    "channel=" + id(index) + " focus=" + (cells - 1));
            }
        }
        return result;
    }

    private static Integer guideIndex(String guideId) {
        if (guideId == null || !guideId.startsWith("g-") || guideId.length() != 7) return null;
        try {
            return Integer.parseInt(guideId.substring(2));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String[] lines(String text) {
        return text.replace("\r", "").split("\n", -1);
    }

    private static String joinLines(String[] lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (!line.isEmpty()) text.append(line).append('\n');
        }
        return text.toString();
    }

    private static String[] mutateOrder(String[] good) {
        String[] mutated = good.clone();
        int first = -1;
        for (int i = 0; i < mutated.length; i++) {
            if (mutated[i].startsWith("CH c-00010|")) { first = i; break; }
        }
        if (first < 0 || !mutated[first + 1].startsWith("CH c-00011|")) {
            throw new IllegalStateException("order mutation target missing");
        }
        String swap = mutated[first];
        mutated[first] = mutated[first + 1];
        mutated[first + 1] = swap;
        return mutated;
    }

    private static String[] mutateClippedRow(String[] good) {
        String[] mutated = good.clone();
        for (int i = 0; i < mutated.length; i++) {
            if (mutated[i].startsWith("PG g-00000|QA06 Show 00000-03|")) {
                String[] field = mutated[i].split("\\|", -1);
                field[2] = Long.toString(Long.parseLong(field[2]) + 900L);
                field[3] = Long.toString(Long.parseLong(field[3]) + 900L);
                mutated[i] = String.join("|", field);
                return mutated;
            }
        }
        throw new IllegalStateException("clipping mutation target missing");
    }

    private static String[] mutatePageCount(String[] good) {
        String[] mutated = good.clone();
        for (int i = 0; i < mutated.length; i++) {
            if (mutated[i].startsWith("PAGE 1|24|24|")) {
                mutated[i] = mutated[i].replace("PAGE 1|24|24|", "PAGE 1|24|25|");
                return mutated;
            }
        }
        throw new IllegalStateException("page mutation target missing");
    }

    private static String[] mutateReference(String[] good) {
        String[] mutated = good.clone();
        for (int i = 0; i < mutated.length; i++) {
            if (mutated[i].startsWith("CH c-00007|")) {
                String[] field = mutated[i].split("\\|", -1);
                field[3] = "http://127.0.0.1:9/qa06/negative";
                mutated[i] = String.join("|", field);
                return mutated;
            }
        }
        throw new IllegalStateException("reference mutation target missing");
    }

    private static String read(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FIXTURE_BYTES * 2L) {
            throw new IOException("input exceeds bounded scratch size");
        }
        return text;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("usage: --generate|--verify|--all ...");
        String mode = args[0];
        String canonical = generate();
        if ("--generate".equals(mode)) {
            if (args.length != 2) throw new IllegalArgumentException("usage: --generate FILE");
            Path output = Paths.get(args[1]);
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(output, canonical.getBytes(StandardCharsets.UTF_8));
            System.out.println("PASS\tqa06-generate\tchannels=" + CHANNEL_COUNT
                + " programmes=" + TOTAL_PROGRAMMES
                + " bytes=" + canonical.getBytes(StandardCharsets.UTF_8).length
                + " sha256=" + digest(canonical));
            return;
        }
        if ("--verify".equals(mode)) {
            if (args.length != 2) throw new IllegalArgumentException("usage: --verify FILE");
            Result result = verify(read(Paths.get(args[1])));
            report("qa06-verify", result);
            if (!result.pass()) System.exit(1);
            return;
        }
        if ("--mutate".equals(mode)) {
            if (args.length != 3) throw new IllegalArgumentException("usage: --mutate NAME FILE");
            String[] good = lines(canonical);
            String[] mutated;
            switch (args[1]) {
                case "ordering-altered": mutated = mutateOrder(good); break;
                case "clipped-visible-row": mutated = mutateClippedRow(good); break;
                case "page-count-overflow": mutated = mutatePageCount(good); break;
                case "non-loopback-reference": mutated = mutateReference(good); break;
                default: throw new IllegalArgumentException("unknown mutation: " + args[1]);
            }
            String text = joinLines(mutated);
            if (text.isEmpty() || text.equals(joinLines(good))) {
                throw new IllegalStateException("mutation produced no change");
            }
            Path output = Paths.get(args[2]);
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(output, text.getBytes(StandardCharsets.UTF_8));
            System.out.println("PASS\tqa06-mutate\t" + args[1] + "\tbytes=" + text.length());
            return;
        }
        if (!"--all".equals(mode) || args.length != 4) {
            throw new IllegalArgumentException("usage: --all FIXTURE MANIFEST REPORT");
        }

        String fixture = read(Paths.get(args[1]));
        String manifest = read(Paths.get(args[2]));
        boolean fixtureMatch = fixture.equals(canonical);
        boolean manifestMatch = manifest.equals(manifestText());
        System.out.println((fixtureMatch ? "PASS" : "FAIL") + "\tqa06-fixture-match\tsha256=" + digest(canonical));
        System.out.println((manifestMatch ? "PASS" : "FAIL") + "\tqa06-manifest-match\tbytes="
            + manifest.getBytes(StandardCharsets.UTF_8).length);

        Result positive = verify(fixture);
        report("qa06-positive-verify", positive);

        String[] good = lines(canonical);
        String[][] controls = {
            {"ordering-altered", "CHANNEL_ORDER"},
            {"clipped-visible-row", "ROW_NOT_CONTIGUOUS"},
            {"page-count-overflow", "ROW_COUNT_BOUND"},
            {"non-loopback-reference", "PRIVACY_REF"},
        };
        int negativeFailures = 0;
        int negativeChecks = 0;
        boolean[] rejectedControls = new boolean[controls.length];
        for (int i = 0; i < controls.length; i++) {
            String[] mutated;
            switch (i) {
                case 0: mutated = mutateOrder(good); break;
                case 1: mutated = mutateClippedRow(good); break;
                case 2: mutated = mutatePageCount(good); break;
                default: mutated = mutateReference(good); break;
            }
            boolean changed = !joinLines(mutated).equals(joinLines(good));
            Result negative = verify(joinLines(mutated));
            negativeChecks += negative.checks;
            boolean rejected = changed && !negative.pass() && controls[i][1].equals(negative.firstCode);
            rejectedControls[i] = rejected;
            if (!rejected) negativeFailures++;
            System.out.println((rejected ? "PASS" : "FAIL") + "\tqa06-negative-mutation\t"
                + controls[i][0] + " expected=" + controls[i][1]
                + " actual=" + (negative.firstCode.isEmpty() ? "NONE" : negative.firstCode));
        }

        StringBuilder report = new StringBuilder();
        report.append("QA-06 large EPG gate | channels=").append(CHANNEL_COUNT)
            .append(" programmes=").append(TOTAL_PROGRAMMES)
            .append(" pages=").append(PAGE_COUNT)
            .append(" sample_rows=").append(sampleIndices().size())
            .append(" sha256=").append(digest(canonical)).append('\n');
        report.append((fixtureMatch ? "PASS" : "FAIL")).append("\tqa06-fixture-match\n");
        report.append((manifestMatch ? "PASS" : "FAIL")).append("\tqa06-manifest-match\n");
        report.append((positive.pass() ? "PASS" : "FAIL")).append("\tqa06-positive-verify\tchecks=")
            .append(positive.checks).append(" failures=").append(positive.failures).append('\n');
        for (int i = 0; i < controls.length; i++) {
            report.append((rejectedControls[i] ? "PASS" : "FAIL")).append("\tqa06-negative-mutation\t")
                .append(controls[i][0]).append('\n');
        }
        boolean privacyOk = !FORBIDDEN.matcher(report).find();
        report.append((privacyOk ? "PASS" : "FAIL")).append("\tqa06-report-privacy\t")
            .append("no URL-like or credential-like token in generated report\n");
        if (report.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
            throw new IllegalStateException("report byte bound exceeded");
        }

        int gateChecks = positive.checks + negativeChecks + controls.length + 4;
        int gateFailures = positive.failures + negativeFailures
            + (fixtureMatch ? 0 : 1) + (manifestMatch ? 0 : 1) + (privacyOk ? 0 : 1);
        Path reportPath = Paths.get(args[3]);
        Path parent = reportPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println((privacyOk ? "PASS" : "FAIL") + "\tqa06-report-privacy\t"
            + "report=" + reportPath.toAbsolutePath() + " bytes="
            + report.toString().getBytes(StandardCharsets.UTF_8).length);
        System.out.println((gateFailures == 0 ? "PASS" : "FAIL") + "\tqa06-jvm-gate\tchecks=" + gateChecks
            + " positive=" + positive.checks + " negative=" + negativeChecks
            + " negative_failures=" + negativeFailures
            + " fixture_match=" + fixtureMatch + " manifest_match=" + manifestMatch
            + " report_privacy=" + privacyOk);
        if (gateFailures != 0) System.exit(1);
    }

    private static void report(String name, Result result) {
        System.out.println((result.pass() ? "PASS" : "FAIL") + "\t" + name + "\tchecks=" + result.checks
            + " failures=" + result.failures
            + (result.firstCode.isEmpty() ? "" : " first=" + result.firstCode));
        for (String line : result.details) System.out.println("DETAIL\t" + name + "\t" + line);
    }
}
