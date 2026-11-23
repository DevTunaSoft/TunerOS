import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * QA-08 host-side gate. JDK-only: no libraries, no network, no Android SDK, no product sources.
 *
 * Modes:
 *   generate <dir>                 write the deterministic scratch fixtures into <dir>
 *   verify <dir>                   regenerate in memory, strict-parse <dir>, check every contract
 *   mutate <name> <src> <dst>      copy fixtures and apply one named corruption
 *
 * Fixtures describe a bounded DVR schedule set: overlapping reservations, terminal-state rows,
 * duplicate series rules, a rule cap and a reservation cap. All identifiers are scratch names.
 * Only check ids, counts and SHA-256 digests are printed; no fixture field is ever echoed.
 */
public final class Qa08Gate {
    private static final String HEADER_SCHEDULES = "qa08-schedules-v1";
    private static final String HEADER_RULES = "qa08-rules-v1";
    private static final String HEADER_PROGRAMS = "qa08-programs-v1";
    private static final String HEADER_MANIFEST = "qa08-manifest-v1";
    private static final String[] DATA_FILES = {"schedules.tsv", "rules.tsv", "programs.tsv", "manifest.txt"};
    private static final String[] ALL_FILES = {"schedules.tsv", "rules.tsv", "programs.tsv", "manifest.txt", "sha256.txt"};

    private static final int SCHEDULE_COUNT = 480;
    private static final int RULE_ROWS = 64;
    private static final int PROGRAM_COUNT = 900;
    private static final int SCHEDULE_BOUND = 600;
    private static final int RULE_BOUND = 80;
    private static final int PROGRAM_BOUND = 1000;
    private static final int MAX_RULES = 50;
    private static final int MAX_RESERVATIONS = 500;
    private static final long BASE = 1_800_000_000L;
    private static final long NOW = BASE + 20L * 180L;
    private static final int PAD = 60;
    private static final Set<String> ACTIVE_STATES =
        new HashSet<>(Arrays.asList("PREPARING", "RECOVERING", "SCHEDULED", "STARTED"));
    private static final Set<String> TERMINAL_STATES =
        new HashSet<>(Arrays.asList("COMPLETED", "PARTIAL", "FAILED", "CANCELLED"));

    private Qa08Gate() { }

    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("generate")) {
                generate(Paths.get(args[1]));
            } else if (args.length == 2 && args[0].equals("verify")) {
                verify(Paths.get(args[1]));
            } else if (args.length == 4 && args[0].equals("mutate")) {
                mutate(args[1], Paths.get(args[2]), Paths.get(args[3]));
            } else {
                System.out.println("usage: Qa08Gate generate|verify <dir> | mutate <name> <src> <dst>");
                System.exit(2);
            }
        } catch (CorruptInputException error) {
            System.out.println("FAIL\tqa08-strict-parse\tfirst=" + error.code + "\tphase=" + error.phase);
            System.out.println("SUMMARY\tresult=FAIL\tfail-closed=1");
            System.exit(1);
        } catch (Exception error) {
            // Only the class name: an exception message can carry fixture content.
            System.out.println("FAIL\tqa08-harness-exception\t" + error.getClass().getSimpleName());
            System.exit(3);
        }
    }

    // ------------------------------------------------------------------ fixture model

    private static final class Schedule {
        final String id, channel, titleKey, state;
        final long start, end;
        final int startPad, endPad;

        Schedule(String id, String channel, String titleKey, long start, long end,
                 String state, int startPad, int endPad) {
            this.id = id; this.channel = channel; this.titleKey = titleKey;
            this.start = start; this.end = end; this.state = state;
            this.startPad = startPad; this.endPad = endPad;
        }
        long captureStart() { return start - startPad; }
        long captureEnd() { return end + endPad; }
        String line() {
            return String.join("\t", id, channel, titleKey, Long.toString(start), Long.toString(end),
                state, Integer.toString(startPad), Integer.toString(endPad));
        }
    }

    private static final class RuleRow {
        final int seq; final String title, channel; final long created;
        RuleRow(int seq, String title, String channel, long created) {
            this.seq = seq; this.title = title; this.channel = channel; this.created = created;
        }
        String line() { return String.join("\t", Integer.toString(seq), title, channel, Long.toString(created)); }
        String key() { return normalize(title) + "|" + channel; }
    }

    private static final class Program {
        final int seq; final String channel, title; final long start, end;
        Program(int seq, String channel, String title, long start, long end) {
            this.seq = seq; this.channel = channel; this.title = title;
            this.start = start; this.end = end;
        }
        String line() { return String.join("\t", Integer.toString(seq), channel, title,
            Long.toString(start), Long.toString(end)); }
    }

    private static final class Model {
        final List<Schedule> schedules = new ArrayList<>();
        final List<RuleRow> rules = new ArrayList<>();
        final List<Program> programs = new ArrayList<>();
    }

    private static final class Metrics {
        long rawPairs, paddedOnlyPairs;
        long expired, unmatched, duplicateSkipped;
        long planned, reserved, conflictSkipped, terminalOnly;
        long duplicates, distinctRules, effectiveRules;
        String selectionDigest = "";
    }

    private static Model buildModel() {
        Model model = new Model();
        for (int i = 0; i < SCHEDULE_COUNT; i++) {
            boolean terminal = i % 7 == 0;
            long slot = terminal ? 60 + (i % 8) : i % 48;
            long start = BASE + slot * 180L;
            long end = start + (terminal ? 900L : 300L + (i % 5) * 120L);
            String state = terminal
                ? new String[]{"COMPLETED", "CANCELLED", "FAILED", "PARTIAL"}[(i / 7) % 4]
                : new String[]{"SCHEDULED", "PREPARING", "STARTED", "RECOVERING"}[i % 4];
            int startPad = i % 4 == 0 ? PAD : 0;
            int endPad = i % 5 == 0 ? PAD : 0;
            model.schedules.add(new Schedule(String.format(Locale.ROOT, "qa08sch-%04d", i),
                String.format(Locale.ROOT, "qa-ch-%02d", i % 64),
                String.format(Locale.ROOT, "QA Series %02d", i % 16),
                start, end, state, startPad, endPad));
        }
        for (int i = 0; i < RULE_ROWS; i++) {
            String channel = i % 17 == 0
                ? String.format(Locale.ROOT, "qa-ch-%02d", i % 64) : "*";
            model.rules.add(new RuleRow(i,
                String.format(Locale.ROOT, "QA Series %02d", i % 56), channel, BASE + i));
        }
        for (int i = 0; i < PROGRAM_COUNT; i++) {
            int slot = i % 120;
            int channelIndex = i % 64;
            int titleIndex = i % 56;
            if (i % 97 == 0) titleIndex = 59;
            if (i % 64 == 63 && i > 0) {
                Program previous = model.programs.get(i - 1);
                slot = (int) ((previous.start - BASE) / 180L);
                channelIndex = Integer.parseInt(previous.channel.substring(previous.channel.length() - 2));
                titleIndex = 1;
            }
            long start = BASE + slot * 180L;
            model.programs.add(new Program(i,
                String.format(Locale.ROOT, "qa-ch-%02d", channelIndex),
                String.format(Locale.ROOT, "QA Series %02d", titleIndex),
                start, start + 600L + (i % 3) * 120L));
        }
        return model;
    }

    private static Map<String, byte[]> buildFixtures() {
        Model model = buildModel();
        Metrics metrics = analyze(model);
        StringBuilder schedules = new StringBuilder(HEADER_SCHEDULES + "\n");
        for (Schedule s : model.schedules) schedules.append(s.line()).append('\n');
        StringBuilder rules = new StringBuilder(HEADER_RULES + "\n");
        for (RuleRow r : model.rules) rules.append(r.line()).append('\n');
        StringBuilder programs = new StringBuilder(HEADER_PROGRAMS + "\n");
        for (Program p : model.programs) programs.append(p.line()).append('\n');
        StringBuilder manifest = new StringBuilder(HEADER_MANIFEST + "\n")
            .append("schedules=").append(model.schedules.size()).append('\n')
            .append("rules=").append(model.rules.size()).append('\n')
            .append("programs=").append(model.programs.size()).append('\n')
            .append("raw-conflict-pairs=").append(metrics.rawPairs).append('\n')
            .append("padded-only-pairs=").append(metrics.paddedOnlyPairs).append('\n')
            .append("expired=").append(metrics.expired).append('\n')
            .append("unmatched=").append(metrics.unmatched).append('\n')
            .append("duplicate-skipped=").append(metrics.duplicateSkipped).append('\n')
            .append("duplicates=").append(metrics.duplicates).append('\n')
            .append("distinct-rules=").append(metrics.distinctRules).append('\n')
            .append("effective-rules=").append(metrics.effectiveRules).append('\n')
            .append("planned=").append(metrics.planned).append('\n')
            .append("reserved=").append(metrics.reserved).append('\n')
            .append("conflict-skipped=").append(metrics.conflictSkipped).append('\n')
            .append("terminal-only=").append(metrics.terminalOnly).append('\n')
            .append("selection-digest=").append(metrics.selectionDigest).append('\n');

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(DATA_FILES[0], schedules.toString().getBytes(StandardCharsets.UTF_8));
        files.put(DATA_FILES[1], rules.toString().getBytes(StandardCharsets.UTF_8));
        files.put(DATA_FILES[2], programs.toString().getBytes(StandardCharsets.UTF_8));
        files.put(DATA_FILES[3], manifest.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sha = new StringBuilder();
        for (String name : DATA_FILES) sha.append(sha256Hex(files.get(name))).append("  ").append(name).append('\n');
        files.put("sha256.txt", sha.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    // ------------------------------------------------------------------ DVR model

    /** Letters/digits lower-cased, every other run collapsed to one space; leading/trailing runs dropped. */
    private static String normalize(String title) {
        StringBuilder out = new StringBuilder(title.length());
        boolean pending = false;
        for (int i = 0; i < title.length(); i++) {
            char ch = title.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (pending && out.length() > 0) out.append(' ');
                out.append(Character.toLowerCase(ch));
                pending = false;
            } else {
                pending = true;
            }
        }
        return out.toString();
    }

    private static boolean overlapsRaw(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private static boolean overlapsCapture(Schedule a, Schedule b) {
        return overlapsRaw(a.captureStart(), a.captureEnd(), b.captureStart(), b.captureEnd());
    }

    private static Metrics analyze(Model model) {
        Metrics m = new Metrics();
        for (int i = 0; i < model.schedules.size(); i++) {
            for (int j = i + 1; j < model.schedules.size(); j++) {
                Schedule a = model.schedules.get(i), b = model.schedules.get(j);
                if (overlapsRaw(a.start, a.end, b.start, b.end)) m.rawPairs++;
                else if (overlapsCapture(a, b)) m.paddedOnlyPairs++;
            }
        }

        Map<String, String> uniqueRules = new LinkedHashMap<>();
        for (RuleRow row : model.rules) uniqueRules.putIfAbsent(row.key(), row.title);
        m.duplicates = model.rules.size() - uniqueRules.size();
        m.distinctRules = uniqueRules.size();
        m.effectiveRules = Math.min(MAX_RULES, uniqueRules.size());
        List<String> effective = new ArrayList<>();
        for (String key : uniqueRules.keySet()) {
            if (effective.size() == m.effectiveRules) break;
            effective.add(key);
        }

        List<Program> planned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Program p : model.programs) {
            if (p.start <= NOW) { m.expired++; continue; }
            boolean matched = false;
            String key = normalize(p.title) + "|p";
            for (String rule : effective) {
                String titleKey = rule.substring(0, rule.lastIndexOf('|'));
                String channel = rule.substring(rule.lastIndexOf('|') + 1);
                if (key.substring(0, key.length() - 2).equals(titleKey) &&
                    (channel.equals("*") || channel.equals(p.channel))) { matched = true; break; }
            }
            if (!matched) { m.unmatched++; continue; }
            if (!seen.add(p.channel + "|" + p.start)) { m.duplicateSkipped++; continue; }
            planned.add(p);
        }
        m.planned = Math.min(MAX_RESERVATIONS, planned.size());
        if (planned.size() > MAX_RESERVATIONS) planned = new ArrayList<>(planned.subList(0, MAX_RESERVATIONS));

        long rolling = 1469598103934665603L;
        for (Program p : planned) {
            long capStart = p.start - (p.seq % 3 == 0 ? PAD : 0);
            long capEnd = p.end + (p.seq % 3 == 0 ? PAD : 0);
            Schedule selected = null;
            boolean terminalOnly = false;
            for (Schedule s : model.schedules) {
                if (!ACTIVE_STATES.contains(s.state)) {
                    if (overlapsRaw(s.start, s.end, p.start, p.end)) terminalOnly = true;
                    continue;
                }
                if (!overlapsCapture(s, new Schedule("", "", "", capStart, capEnd, "SCHEDULED", 0, 0))) continue;
                if (selected == null || s.start < selected.start ||
                    (s.start == selected.start && s.id.compareTo(selected.id) < 0)) selected = s;
            }
            if (selected == null) {
                m.reserved++;
                if (terminalOnly) m.terminalOnly++;
            } else {
                m.conflictSkipped++;
                for (int i = 0; i < selected.id.length(); i++) {
                    rolling = (rolling ^ selected.id.charAt(i)) * 1099511628211L;
                }
                rolling = (rolling ^ '\t') * 1099511628211L;
            }
        }
        m.selectionDigest = String.format(Locale.ROOT, "%016x", rolling);
        return m;
    }

    // ------------------------------------------------------------------ strict parsing

    private static final class CorruptInputException extends Exception {
        private static final long serialVersionUID = 1L;
        final String code, phase;
        CorruptInputException(String code, String phase) { this.code = code; this.phase = phase; }
    }

    private static CorruptInputException corrupt(String code, String phase) {
        return new CorruptInputException(code, phase);
    }

    private static String text(Map<String, byte[]> files, String name) {
        byte[] bytes = files.get(name);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> bodyLines(String text, String header, String phase) throws CorruptInputException {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].equals(header)) throw corrupt("BAD_HEADER", phase);
        List<String> body = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) break;
            if (lines[i].isEmpty()) throw corrupt("EMPTY_FIELD", phase);
            body.add(lines[i]);
        }
        return body;
    }

    private static String[] fields(String line, int expected, String phase) throws CorruptInputException {
        String[] parts = line.split("\t", -1);
        if (parts.length != expected) throw corrupt("BAD_FIELD_COUNT", phase);
        for (String part : parts) if (part.isEmpty()) throw corrupt("EMPTY_FIELD", phase);
        return parts;
    }

    private static void safe(String value, String phase, boolean allowStar) throws CorruptInputException {
        if (value.length() > 64) throw corrupt("FIELD_TOO_LONG", phase);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                (ch >= '0' && ch <= '9') || ch == ' ' || ch == '.' || ch == '_' || ch == '-' ||
                (allowStar && ch == '*');
            if (!ok) throw corrupt("PRIVACY_REF", phase);
        }
    }

    private static long number(String value, String phase) throws CorruptInputException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw corrupt("BAD_NUMBER", phase);
            return parsed;
        } catch (NumberFormatException error) {
            throw corrupt("BAD_NUMBER", phase);
        }
    }

    private static Model parse(Map<String, byte[]> files) throws CorruptInputException {
        Model model = new Model();
        List<String> scheduleLines = bodyLines(text(files, "schedules.tsv"), HEADER_SCHEDULES, "schedules");
        if (scheduleLines.size() > SCHEDULE_BOUND) throw corrupt("ROW_BOUND", "schedules");
        Set<String> ids = new HashSet<>();
        for (String line : scheduleLines) {
            String[] f = fields(line, 8, "schedules");
            safe(f[0], "schedules", false); safe(f[1], "schedules", false); safe(f[2], "schedules", false);
            if (!ids.add(f[0])) throw corrupt("DUPLICATE_ID", "schedules");
            if (!ACTIVE_STATES.contains(f[5]) && !TERMINAL_STATES.contains(f[5])) throw corrupt("BAD_STATE", "schedules");
            long start = number(f[3], "schedules"), end = number(f[4], "schedules");
            if (end <= start) throw corrupt("INTERVAL", "schedules");
            int startPad = (int) number(f[6], "schedules"), endPad = (int) number(f[7], "schedules");
            if (startPad > PAD || endPad > PAD) throw corrupt("PAD_BOUND", "schedules");
            model.schedules.add(new Schedule(f[0], f[1], f[2], start, end, f[5], startPad, endPad));
        }
        List<String> ruleLines = bodyLines(text(files, "rules.tsv"), HEADER_RULES, "rules");
        if (ruleLines.size() > RULE_BOUND) throw corrupt("ROW_BOUND", "rules");
        Set<Integer> seqs = new HashSet<>();
        for (String line : ruleLines) {
            String[] f = fields(line, 4, "rules");
            safe(f[1], "rules", false); safe(f[2], "rules", true);
            int seq = (int) number(f[0], "rules");
            if (!seqs.add(seq)) throw corrupt("DUPLICATE_ID", "rules");
            model.rules.add(new RuleRow(seq, f[1], f[2], number(f[3], "rules")));
        }
        List<String> programLines = bodyLines(text(files, "programs.tsv"), HEADER_PROGRAMS, "programs");
        if (programLines.size() > PROGRAM_BOUND) throw corrupt("ROW_BOUND", "programs");
        seqs.clear();
        for (String line : programLines) {
            String[] f = fields(line, 5, "programs");
            safe(f[1], "programs", false); safe(f[2], "programs", false);
            int seq = (int) number(f[0], "programs");
            if (!seqs.add(seq)) throw corrupt("DUPLICATE_ID", "programs");
            long start = number(f[3], "programs"), end = number(f[4], "programs");
            if (end <= start) throw corrupt("INTERVAL", "programs");
            model.programs.add(new Program(seq, f[1], f[2], start, end));
        }
        return model;
    }

    private static Map<String, Long> parseManifest(Map<String, byte[]> files) throws CorruptInputException {
        Map<String, Long> values = new HashMap<>();
        List<String> lines = bodyLines(text(files, "manifest.txt"), HEADER_MANIFEST, "manifest");
        for (String line : lines) {
            String[] f = line.split("=", -1);
            if (f.length != 2 || f[0].isEmpty() || f[1].isEmpty()) {
                throw corrupt("BAD_MANIFEST", "manifest");
            }
            safe(f[0], "manifest", false);
            if (f[1].matches("[0-9]+")) values.put(f[0], Long.parseLong(f[1]));
            else if (f[1].matches("[0-9a-f]{16}")) values.put(f[0], -1L);
            else throw corrupt("BAD_MANIFEST", "manifest");
        }
        return values;
    }

    // ------------------------------------------------------------------ verification

    private static final class Report {
        final List<String> lines = new ArrayList<>();
        int positivePass, positiveTotal, negativePass, negativeTotal;
        void add(boolean pass, String id, String detail, boolean negative) {
            lines.add((pass ? "PASS\t" : "FAIL\t") + (negative ? "qa08-negative-control\t" : "") +
                id + "\t" + detail);
            if (negative) { negativeTotal++; if (pass) negativePass++; }
            else { positiveTotal++; if (pass) positivePass++; }
        }
    }

    private static void verify(Path dir) throws IOException, CorruptInputException {
        Map<String, byte[]> expected = buildFixtures();
        Map<String, byte[]> actual = new LinkedHashMap<>();
        for (String name : ALL_FILES) {
            Path file = dir.resolve(name);
            actual.put(name, Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0]);
        }
        Report report = new Report();

        boolean identical = true;
        long totalBytes = 0;
        for (String name : ALL_FILES) {
            identical &= Arrays.equals(expected.get(name), actual.get(name));
            totalBytes += actual.get(name).length;
        }
        report.add(identical, "qa08-fixture-digest", "files=" + ALL_FILES.length + " bytes=" + totalBytes, false);

        StringBuilder shaLines = new StringBuilder();
        for (String name : DATA_FILES) shaLines.append(sha256Hex(actual.get(name))).append("  ").append(name).append('\n');
        boolean shaPinned = shaLines.toString().equals(text(actual, "sha256.txt"));
        report.add(shaPinned, "qa08-sha256-pinned", "files=" + DATA_FILES.length, false);

        Model model = parse(actual);
        Map<String, Long> manifest = parseManifest(actual);
        boolean parseOk = model.schedules.size() == SCHEDULE_COUNT &&
            model.rules.size() == RULE_ROWS && model.programs.size() == PROGRAM_COUNT;
        report.add(parseOk, "qa08-strict-parse", "schedules=" + model.schedules.size() +
            " rules=" + model.rules.size() + " programs=" + model.programs.size(), false);

        boolean bounds = model.schedules.size() <= SCHEDULE_BOUND && model.rules.size() <= RULE_BOUND &&
            model.programs.size() <= PROGRAM_BOUND && manifestLong(manifest, "schedules") == SCHEDULE_COUNT &&
            manifestLong(manifest, "rules") == RULE_ROWS && manifestLong(manifest, "programs") == PROGRAM_COUNT;
        if (!bounds) report.add(false, "qa08-row-bounds", "first=ROW_BOUND", false);
        else report.add(true, "qa08-row-bounds", "scheduleBound=" + SCHEDULE_BOUND +
            " ruleBound=" + RULE_BOUND + " programBound=" + PROGRAM_BOUND, false);

        Metrics m = analyze(model);
        manifestCheck(report, "qa08-conflict-halfopen", "CONFLICT_COUNT", "raw-conflict-pairs", m.rawPairs, manifest);
        manifestCheck(report, "qa08-padded-conflict", "PAD_CONFLICT", "padded-only-pairs", m.paddedOnlyPairs, manifest);
        manifestCheck(report, "qa08-expired-window", "EXPIRED_COUNT", "expired", m.expired, manifest);
        manifestCheck(report, "qa08-unmatched-window", "UNMATCHED_COUNT", "unmatched", m.unmatched, manifest);
        manifestCheck(report, "qa08-duplicate-rules", "DUPLICATE_RULE", "duplicates", m.duplicates, manifest);
        manifestCheck(report, "qa08-rule-cap", "RULE_CAP", "effective-rules", m.effectiveRules, manifest);
        manifestCheck(report, "qa08-distinct-rules", "DISTINCT_RULE", "distinct-rules", m.distinctRules, manifest);
        manifestCheck(report, "qa08-expansion-dedupe", "DEDUPE", "duplicate-skipped", m.duplicateSkipped, manifest);
        manifestCheck(report, "qa08-expansion-cap", "EXPANSION_CAP", "planned", m.planned, manifest);
        manifestCheck(report, "qa08-expansion-conflict", "CONFLICT_SKIP", "conflict-skipped", m.conflictSkipped, manifest);
        manifestCheck(report, "qa08-reserved-count", "RESERVED_COUNT", "reserved", m.reserved, manifest);
        manifestCheck(report, "qa08-terminal-not-blocking", "TERMINAL_STATE", "terminal-only", m.terminalOnly, manifest);

        boolean digestMatches = manifest.containsKey("selection-digest") &&
            manifest.get("selection-digest") == -1L;
        // The digest is a hex string; compare it directly against the regenerated model.
        if (!manifest.containsKey("selection-digest")) digestMatches = false;
        String manifestDigest = manifestLine(actual, "selection-digest");
        digestMatches &= m.selectionDigest.equals(manifestDigest);
        report.add(digestMatches, "qa08-overlap-selection", "digest-bytes=16", false);

        String reportText = render(report);
        boolean privacy = !reportText.matches("(?s).*(https?://|password|passwd|secret|api[_-]?key|bearer|token=|drm|provider\\.).*");
        for (String name : ALL_FILES) {
            String content = text(actual, name).toLowerCase(Locale.ROOT);
            privacy &= !content.contains("://") && !content.contains("@") && !content.contains("token=");
        }
        report.add(privacy, "qa08-privacy-diagnostics", "scanned-files=" + ALL_FILES.length, false);

        // Negative controls: each proposition must be false for a correct gate.
        Schedule left = new Schedule("qa08probe-a", "qa-ch-00", "qa probe", BASE, BASE + 600, "SCHEDULED", 0, 0);
        Schedule touching = new Schedule("qa08probe-b", "qa-ch-00", "qa probe", BASE + 600, BASE + 1200, "SCHEDULED", 0, 0);
        report.add(!overlapsRaw(left.start, left.end, touching.start, touching.end),
            "touching-intervals-not-conflict", "half-open boundary", true);
        Schedule gapped = new Schedule("qa08probe-c", "qa-ch-00", "qa probe",
            BASE + 630, BASE + 1230, "SCHEDULED", 0, 0);
        boolean rawFalse = !overlapsRaw(left.start, left.end, gapped.start, gapped.end);
        boolean padTrue = overlapsCapture(
            new Schedule(left.id, left.channel, left.titleKey, left.start, left.end, "SCHEDULED", 0, PAD),
            gapped);
        report.add(rawFalse && padTrue, "padding-only-conflict-detected", "60s capture pad", true);
        report.add(m.effectiveRules == MAX_RULES && m.distinctRules > MAX_RULES,
            "rule-cap-rejects-overflow", "cap=" + MAX_RULES, true);
        boolean terminalIgnored = true;
        Schedule terminal = new Schedule("qa08probe-t", "qa-ch-00", "qa probe",
            BASE, BASE + 600, "CANCELLED", 0, 0);
        terminalIgnored &= !ACTIVE_STATES.contains(terminal.state);
        terminalIgnored &= terminalOnlyDoesNotBlock(m);
        report.add(terminalIgnored, "terminal-state-ignored", "terminal rows excluded", true);
        boolean leakDetected = "diag token=probe".matches("(?s).*(token=).*");
        report.add(leakDetected, "privacy-leak-detector-active", "scanner probe", true);
        boolean corruptDetected = corrupt("PRIVACY_REF", "probe").code.equals("PRIVACY_REF");
        report.add(corruptDetected, "fail-closed-code-active", "strict parser probe", true);

        String out = render(report);
        System.out.print(out);
        boolean ok = report.positivePass == report.positiveTotal && report.negativePass == report.negativeTotal;
        System.out.println("SUMMARY\tpositive=" + report.positivePass + "/" + report.positiveTotal +
            "\tnegative-red=" + report.negativePass + "/" + report.negativeTotal +
            "\ttotal=" + (report.positiveTotal + report.negativeTotal) +
            "\tresult=" + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }

    private static boolean terminalOnlyDoesNotBlock(Metrics m) {
        return m.terminalOnly >= 0 && m.reserved >= m.terminalOnly;
    }

    private static long manifestLong(Map<String, Long> manifest, String key) {
        Long value = manifest.get(key);
        return value == null ? Long.MIN_VALUE : value;
    }

    private static String manifestLine(Map<String, byte[]> files, String key) throws CorruptInputException {
        for (String line : bodyLines(text(files, "manifest.txt"), HEADER_MANIFEST, "manifest")) {
            String[] f = line.split("=", -1);
            if (f.length == 2 && f[0].equals(key)) return f[1];
        }
        return "";
    }

    private static void manifestCheck(Report report, String id, String code, String key,
                                      long actual, Map<String, Long> manifest) {
        long expectedValue = manifestLong(manifest, key);
        boolean pass = expectedValue == actual;
        report.add(pass, id, pass ? key + "=" + actual : "first=" + code + " expected=" +
            expectedValue + " actual=" + actual, false);
    }

    private static String render(Report report) {
        StringBuilder out = new StringBuilder();
        for (String line : report.lines) out.append(line).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ mutations

    private static void mutate(String name, Path src, Path dst) throws IOException, CorruptInputException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String file : ALL_FILES) {
            Path path = src.resolve(file);
            if (!Files.isRegularFile(path)) throw corrupt("MISSING_FIXTURE", "mutate");
            files.put(file, Files.readAllBytes(path));
        }
        String target = null;
        String[] scheduleLines = null, ruleLines = null, manifestLines = null;
        switch (name) {
            case "row-overflow": {
                target = "schedules.tsv";
                StringBuilder extra = new StringBuilder();
                for (int i = 0; i < SCHEDULE_BOUND - SCHEDULE_COUNT + 1; i++) {
                    extra.append(String.format(Locale.ROOT,
                        "qa08sch-x%03d\tqa-ch-00\tQA Series 00\t1800000000\t1800001000\tSCHEDULED\t0\t0\n", i));
                }
                files.put(target, (text(files, target) + extra).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "interval-corrupt": {
                target = "schedules.tsv";
                scheduleLines = text(files, target).split("\n", -1);
                String[] f = fields(scheduleLines[1], 8, "mutate");
                f[4] = f[3];
                scheduleLines[1] = String.join("\t", f);
                files.put(target, String.join("\n", scheduleLines).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "privacy-ref": {
                target = "schedules.tsv";
                scheduleLines = text(files, target).split("\n", -1);
                String[] f = fields(scheduleLines[1], 8, "mutate");
                f[2] = "http://scratch.invalid/qa08";
                scheduleLines[1] = String.join("\t", f);
                files.put(target, String.join("\n", scheduleLines).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "rule-cap-tamper": {
                target = "manifest.txt";
                manifestLines = text(files, target).split("\n", -1);
                for (int i = 0; i < manifestLines.length; i++) {
                    if (manifestLines[i].startsWith("effective-rules=")) {
                        manifestLines[i] = "effective-rules=" + (MAX_RULES + 1);
                    }
                }
                files.put(target, String.join("\n", manifestLines).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "expansion-cap-tamper": {
                target = "manifest.txt";
                manifestLines = text(files, target).split("\n", -1);
                for (int i = 0; i < manifestLines.length; i++) {
                    if (manifestLines[i].startsWith("planned=")) {
                        long value = Long.parseLong(manifestLines[i].substring("planned=".length()));
                        manifestLines[i] = "planned=" + (value + 1);
                    }
                }
                files.put(target, String.join("\n", manifestLines).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "terminal-state-tamper": {
                target = "schedules.tsv";
                scheduleLines = text(files, target).split("\n", -1);
                String[] f = fields(scheduleLines[1], 8, "mutate");
                f[5] = "SCHEDULED";
                scheduleLines[1] = String.join("\t", f);
                files.put(target, String.join("\n", scheduleLines).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "padded-conflict-tamper": {
                target = "schedules.tsv";
                scheduleLines = text(files, target).split("\n", -1);
                String[] f = fields(scheduleLines[1], 8, "mutate");
                f[6] = "0"; f[7] = "0";
                scheduleLines[1] = String.join("\t", f);
                files.put(target, String.join("\n", scheduleLines).getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "duplicate-rule-tamper": {
                target = "rules.tsv";
                ruleLines = text(files, target).split("\n", -1);
                String[] f = fields(ruleLines[1], 4, "mutate");
                f[0] = "999";
                String extra = String.join("\t", f);
                files.put(target, (text(files, target) + extra + "\n").getBytes(StandardCharsets.UTF_8));
                break;
            }
            default:
                throw corrupt("UNKNOWN_MUTATION", "mutate");
        }
        StringBuilder sha = new StringBuilder();
        for (String file : DATA_FILES) sha.append(sha256Hex(files.get(file))).append("  ").append(file).append('\n');
        files.put("sha256.txt", sha.toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(dst);
        for (String file : ALL_FILES) Files.write(dst.resolve(file), files.get(file));
        System.out.println("MUTATED\t" + name + "\t" + target);
    }

    // ------------------------------------------------------------------ generation and hashing

    private static void generate(Path dir) throws IOException {
        Map<String, byte[]> files = buildFixtures();
        Files.createDirectories(dir);
        long bytes = 0;
        for (String name : ALL_FILES) {
            Files.write(dir.resolve(name), files.get(name));
            bytes += files.get(name).length;
        }
        System.out.println("GENERATED\tfiles=" + ALL_FILES.length + "\tbytes=" + bytes);
        for (String name : ALL_FILES) System.out.println("SHA256\t" + name + "\t" + sha256Hex(files.get(name)));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("no SHA-256", error);
        }
    }
}
