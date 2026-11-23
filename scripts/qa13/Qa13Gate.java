import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * QA-13: deterministic, zero-dependency host gate for bounded timeshift live-window state.
 *
 * The model mirrors the app-level controls: a bounded set of overlapping retention windows per
 * channel, half-open window boundaries, Back = current - 10 s clamped at 0, Live = the current
 * edge, and fail-closed rejection when no window exists. No production code is changed here.
 * Diagnostics carry check ids, counts and digests only.
 */
public final class Qa13Gate {
    static final String VERSION = "qa13-fixture-v1";
    static final String MIRROR_DIGEST = "687e06ebb5c450576c3c0e8a8de3195fa647b7c4d7ffda9dd692a43a19faa8ff";
    static final long SEED = 20261123L;
    static final int NORMAL_CHANNELS = 256;
    static final int SNAPSHOTS = 17;
    static final int WINDOW_CAP = 7;
    static final long RETENTION_MILLIS = 60_000L;
    static final long HALF_SPAN_MILLIS = 7_000L;
    static final long STEP_MILLIS = 4_000L;
    static final long BASE_MILLIS = 10_000L;
    static final long BACK_MILLIS = 10_000L;
    static final long MAX_POSITION_MILLIS = 1_000_000L;
    static final long MAX_WINDOW_BYTES = 4_096L;
    static final long MAX_SPAN_MILLIS = 60_000L;
    static final int SEEKS_PER_CHANNEL = 10;
    static final int EMPTY_CHANNEL_1 = NORMAL_CHANNELS + 1;
    static final int EMPTY_CHANNEL_2 = NORMAL_CHANNELS + 2;
    static final int SINGLE_CHANNEL = NORMAL_CHANNELS + 3;
    static final int TOUCHING_CHANNEL = NORMAL_CHANNELS + 4;
    static final int CHANNELS = NORMAL_CHANNELS + 4;
    static final int MAX_ROWS = 20_000;
    static final int MAX_FILE_BYTES = 2_000_000;

    static final String WINDOWS_HEADER = "QA13-WINDOWS-V1\tid\tchannel\tfromMillis\ttoMillis\tbytes\tsegments";
    static final String SEEKS_HEADER = "QA13-SEEKS-V1\tid\tchannel\tmode\tpositionMillis";
    static final String CORRUPT_HEADER = "QA13-CORRUPT-V1\tcase\thex";
    static final String GITATTRIBUTES = "* -text\n.gitattributes -text\n";
    static final String[] DATA_FILES = {"dimensions.tsv", "windows.tsv", "seeks.tsv", "corrupt.tsv"};
    static final String[] ALL_FILES = {".gitattributes", "dimensions.tsv", "windows.tsv",
            "seeks.tsv", "corrupt.tsv", "manifest.sha256"};

    static final List<String> report = new ArrayList<String>();
    static String firstCode = "";
    static boolean firstIsFailure = false;

    static final class QaException extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        QaException(String code) { super(code); this.code = code; }
    }

    static final class Policy {
        int channels, normalChannels, snapshots, windowCap, seekRows;
        long retention, halfSpan, step, base, back, maxPosition, maxBytes;
        String mirrorDigest;
    }

    static final class Win {
        String id;
        int channel;
        long from, to, bytes;
        int segments;
        Win(String id, int c, long f, long t, long b, int s) {
            this.id = id; this.channel = c; this.from = f; this.to = t; this.bytes = b; this.segments = s;
        }
    }

    static final class Seek {
        String id;
        int channel;
        String mode;
        long position;
        Seek(String id, int c, String m, long p) {
            this.id = id; this.channel = c; this.mode = m; this.position = p;
        }
    }

    static final class State {
        int retained;
        List<long[]> intervals = new ArrayList<long[]>();
        int overlapPairs;
        int touchPairs;
        long liveEdge;
        boolean empty() { return intervals.isEmpty(); }
    }

    static final class Result {
        boolean accepted;
        long position = -1L;
        String code;
        Result(boolean a, long p, String c) { accepted = a; position = p; code = c; }
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static String sha(byte[] data) throws QaException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new QaException("DIGEST_UNAVAILABLE");
        }
    }

    static String secret() {
        long mix = SEED * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
        return Long.toString(10_000_000L + Math.floorMod(mix, 90_000_000L));
    }

    static String id(int n) { return String.format(Locale.ROOT, "w%06d", n); }
    static String sid(int n) { return String.format(Locale.ROOT, "s%06d", n); }

    static String hex(String text) {
        byte[] b = utf8(text);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte value : b) sb.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return sb.toString();
    }

    static String unhex(String text) throws QaException {
        if (text == null || text.length() % 2 != 0) throw new QaException("CORRUPT_HEX");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(text.charAt(2 * i), 16);
            int lo = Character.digit(text.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new QaException("CORRUPT_HEX");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    static Policy defaultPolicy() {
        Policy p = new Policy();
        p.channels = CHANNELS;
        p.normalChannels = NORMAL_CHANNELS;
        p.snapshots = SNAPSHOTS;
        p.windowCap = WINDOW_CAP;
        p.retention = RETENTION_MILLIS;
        p.halfSpan = HALF_SPAN_MILLIS;
        p.step = STEP_MILLIS;
        p.base = BASE_MILLIS;
        p.back = BACK_MILLIS;
        p.maxPosition = MAX_POSITION_MILLIS;
        p.maxBytes = MAX_WINDOW_BYTES;
        p.seekRows = SEEKS_PER_CHANNEL;
        p.mirrorDigest = MIRROR_DIGEST;
        return p;
    }

    static List<String> lines(byte[] data) throws QaException {
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0) throw new QaException("ENCODING");
        String[] split = text.split("\n", -1);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < split.length - 1; i++) out.add(split[i]);
        return out;
    }

    static Policy parsePolicy(byte[] data) throws QaException {
        List<String> ls = lines(data);
        if (data.length == 0 || data[data.length - 1] != '\n') throw new QaException("POLICY_TERMINATOR");
        if (ls.isEmpty() || !ls.get(0).equals(VERSION)) throw new QaException("POLICY_HEADER");
        if (ls.size() != 14) throw new QaException("POLICY_ROWS");
        Map<String, String> kv = new LinkedHashMap<String, String>();
        for (int i = 1; i < ls.size(); i++) {
            int eq = ls.get(i).indexOf('=');
            if (eq <= 0) throw new QaException("POLICY_FIELDS");
            String key = ls.get(i).substring(0, eq);
            String value = ls.get(i).substring(eq + 1);
            if (kv.put(key, value) != null) throw new QaException("POLICY_DUPLICATE");
        }
        String[] keys = {"channels", "normal-channels", "snapshots", "window-cap", "retention-millis",
                "half-span-millis", "step-millis", "base-millis", "back-millis", "max-position-millis",
                "max-window-bytes", "seek-rows-per-channel", "mirror-digest"};
        Policy p = new Policy();
        try {
            for (String key : keys) {
                if (!kv.containsKey(key) || kv.size() != keys.length) throw new QaException("POLICY_KEYS");
            }
            p.channels = positive(kv.get("channels"), 512);
            p.normalChannels = positive(kv.get("normal-channels"), 512);
            p.snapshots = positive(kv.get("snapshots"), 512);
            p.windowCap = positive(kv.get("window-cap"), 64);
            p.retention = positive(kv.get("retention-millis"), 120_000L);
            p.halfSpan = positive(kv.get("half-span-millis"), 10_000L);
            p.step = positive(kv.get("step-millis"), 100_000L);
            p.base = nonNegative(kv.get("base-millis"), MAX_POSITION_MILLIS);
            p.back = positive(kv.get("back-millis"), 60_000L);
            p.maxPosition = positive(kv.get("max-position-millis"), 1_000_000_000L);
            p.maxBytes = positive(kv.get("max-window-bytes"), 100_000L);
            p.seekRows = positive(kv.get("seek-rows-per-channel"), 64);
            String digest = kv.get("mirror-digest");
            if (digest == null || digest.length() != 64) throw new QaException("POLICY_DIGEST");
            for (int i = 0; i < digest.length(); i++) {
                if (Character.digit(digest.charAt(i), 16) < 0) throw new QaException("POLICY_DIGEST");
            }
            p.mirrorDigest = digest;
        } catch (NumberFormatException e) {
            throw new QaException("POLICY_NUMBER");
        }
        return p;
    }

    static int positive(String value, int max) throws QaException {
        long v = number(value);
        if (v <= 0 || v > max) throw new QaException("POLICY_RANGE");
        return (int) v;
    }

    static long positive(String value, long max) throws QaException {
        long v = number(value);
        if (v <= 0 || v > max) throw new QaException("POLICY_RANGE");
        return v;
    }

    static long nonNegative(String value, long max) throws QaException {
        long v = number(value);
        if (v < 0 || v > max) throw new QaException("POLICY_RANGE");
        return v;
    }

    static long number(String value) throws QaException {
        if (value == null || value.isEmpty() || value.length() > 18) throw new QaException("POLICY_NUMBER");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' && i == 0) continue;
            if (c < '0' || c > '9') throw new QaException("POLICY_NUMBER");
        }
        return Long.parseLong(value);
    }

    static byte[] manifest(Map<String, byte[]> data) throws QaException {
        StringBuilder sb = new StringBuilder();
        for (String name : DATA_FILES) {
            byte[] bytes = data.get(name);
            if (bytes == null) throw new QaException("MANIFEST_MISSING");
            sb.append(sha(bytes)).append("  ").append(name).append('\n');
        }
        return utf8(sb.toString());
    }

    static Map<String, byte[]> generated() throws QaException {
        Policy p = defaultPolicy();
        Map<String, byte[]> data = new LinkedHashMap<String, byte[]>();
        StringBuilder dim = new StringBuilder(VERSION).append('\n');
        dim.append("channels=").append(p.channels).append('\n');
        dim.append("normal-channels=").append(p.normalChannels).append('\n');
        dim.append("snapshots=").append(p.snapshots).append('\n');
        dim.append("window-cap=").append(p.windowCap).append('\n');
        dim.append("retention-millis=").append(p.retention).append('\n');
        dim.append("half-span-millis=").append(p.halfSpan).append('\n');
        dim.append("step-millis=").append(p.step).append('\n');
        dim.append("base-millis=").append(p.base).append('\n');
        dim.append("back-millis=").append(p.back).append('\n');
        dim.append("max-position-millis=").append(p.maxPosition).append('\n');
        dim.append("max-window-bytes=").append(p.maxBytes).append('\n');
        dim.append("seek-rows-per-channel=").append(p.seekRows).append('\n');
        dim.append("mirror-digest=").append(p.mirrorDigest).append('\n');
        data.put("dimensions.tsv", utf8(dim.toString()));

        List<Win> windows = buildWindows(p);
        StringBuilder w = new StringBuilder(WINDOWS_HEADER).append('\n');
        for (Win win : windows) {
            w.append(win.id).append('\t').append(win.channel).append('\t').append(win.from)
                    .append('\t').append(win.to).append('\t').append(win.bytes)
                    .append('\t').append(win.segments).append('\n');
        }
        data.put("windows.tsv", utf8(w.toString()));

        List<Seek> seeks = buildSeeks(p, windows);
        StringBuilder s = new StringBuilder(SEEKS_HEADER).append('\n');
        for (Seek seek : seeks) {
            s.append(seek.id).append('\t').append(seek.channel).append('\t').append(seek.mode)
                    .append('\t').append(seek.position).append('\n');
        }
        data.put("seeks.tsv", utf8(s.toString()));

        LinkedHashMap<String, String> corrupt = corruptCases(p);
        StringBuilder c = new StringBuilder(CORRUPT_HEADER).append('\n');
        for (Map.Entry<String, String> e : corrupt.entrySet()) {
            c.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        data.put("corrupt.tsv", utf8(c.toString()));

        Map<String, byte[]> all = new LinkedHashMap<String, byte[]>();
        all.put(".gitattributes", utf8(GITATTRIBUTES));
        all.putAll(data);
        all.put("manifest.sha256", manifest(data));
        return all;
    }

    static List<Win> buildWindows(Policy p) {
        List<Win> out = new ArrayList<Win>();
        int n = 0;
        // A fixed template of overlapping half-open windows with one four-slot live gap.
        int[] slots = {0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 13, 14, 15, 16};
        for (int channel = 1; channel <= p.normalChannels; channel++) {
            for (int slot : slots) {
                long from = 200_000L + slot * p.step;
                long to = from + 14_000L;
                long bytes = 1024L + ((slot * 37 + channel * 11) % 24) * 32L;
                int segments = 2 + (slot + channel) % 5;
                out.add(new Win(id(++n), channel, from, to, bytes, segments));
            }
        }
        out.add(new Win(id(++n), SINGLE_CHANNEL, 10_000L, 17_000L, 1024L, 2));
        out.add(new Win(id(++n), TOUCHING_CHANNEL, 10_000L, 17_000L, 1024L, 2));
        out.add(new Win(id(++n), TOUCHING_CHANNEL, 17_000L, 24_000L, 1536L, 3));
        return out;
    }

    static List<Seek> buildSeeks(Policy p, List<Win> windows) {
        List<Seek> out = new ArrayList<Seek>();
        int n = 0;
        String[] modes = {"AT", "LIVE", "BACK"};
        for (int channel = 1; channel <= p.channels; channel++) {
            List<Win> raw = new ArrayList<Win>();
            for (Win w : windows) if (w.channel == channel) raw.add(w);
            raw.sort(Comparator.comparingLong((Win w) -> w.from).thenComparingLong(w -> w.to));
            long earliest = raw.isEmpty() ? 20_000L : raw.get(0).from;
            long edge = raw.isEmpty() ? 90_000L : raw.get(raw.size() - 1).to;
            long span = edge - earliest;
            long gap = edge - 2_500L;
            for (int i = 1; i < raw.size(); i++) {
                if (raw.get(i).from > raw.get(i - 1).to) {
                    gap = (raw.get(i - 1).to + raw.get(i).from) / 2L;
                    break;
                }
            }
            long[] positions = {
                    Math.max(0L, earliest - 500L), earliest, earliest + 1_000L,
                    earliest + span / 2L, edge - 1L, edge, edge + 500L, gap,
                    earliest + 2_000L, Math.max(0L, edge - 3_000L),
            };
            for (int i = 0; i < p.seekRows; i++) {
                out.add(new Seek(sid(++n), channel, modes[(n - 1 + i) % modes.length], positions[i % positions.length]));
            }
        }
        return out;
    }

    static LinkedHashMap<String, String> corruptCases(Policy p) {
        LinkedHashMap<String, String> cases = new LinkedHashMap<String, String>();
        cases.put("inner-bad-header", hex("QA13-WINDOWS-V2\tid\tchannel\tfrom\tto\tbytes\tsegments"));
        cases.put("wrong-field-count", hex("w000001\t1\t10000\t17000\t1024"));
        cases.put("non-numeric-from", hex("w000001\t1\tx\t17000\t1024\t2"));
        cases.put("reversed-interval", hex("w000001\t1\t20000\t10000\t1024\t2"));
        cases.put("span-too-long", hex("w000001\t1\t10000\t81000\t1024\t2"));
        cases.put("negative-time", hex("w000001\t1\t-1\t17000\t1024\t2"));
        cases.put("oversize-bytes", hex("w000001\t1\t10000\t17000\t5000\t2"));
        cases.put("bad-channel", hex("w000001\t999\t10000\t17000\t1024\t2"));
        cases.put("empty-id", hex("\t1\t10000\t17000\t1024\t2"));
        cases.put("duplicate-id", hex("w000001\t2\t10000\t17000\t1024\t2"));
        cases.put("truncated-row", hex("w000001\t1\t10000\t17000\t1024"));
        cases.put("seek-unknown-mode", hex("s000001\t1\tJUMP\t10000"));
        cases.put("seek-negative-position", hex("s000001\t1\tAT\t-5"));
        cases.put("seek-bad-channel", hex("s000001\t999\tAT\t10000"));
        cases.put("seek-truncated", hex("s000001\t1\tAT"));
        return cases;
    }

    static String[] csv(String line) { return line.split("\t", -1); }

    static String parseWindowRow(String line, boolean duplicateCheck, Policy p) {
        String[] f = csv(line);
        if (f.length != 6) return "STRICT_WINDOWS_FIELDS";
        if (!f[0].matches("w[0-9]{6}")) return "STRICT_WINDOWS_ID";
        int channel = parsePositiveInt(f[1], p.channels);
        if (channel < 1) return "STRICT_WINDOWS_CHANNEL";
        long from = parseSignedLong(f[2]);
        long to = parseSignedLong(f[3]);
        long bytes = parseSignedLong(f[4]);
        int segments = parsePositiveInt(f[5], 8);
        if (Long.MIN_VALUE == from || Long.MIN_VALUE == to || Long.MIN_VALUE == bytes) return "STRICT_WINDOWS_NUMBER";
        if (from < 0 || to < 0 || from > p.maxPosition || to > p.maxPosition) return "STRICT_WINDOWS_RANGE";
        if (to <= from) return "STRICT_WINDOWS_INTERVAL";
        if (to - from > MAX_SPAN_MILLIS) return "STRICT_WINDOWS_SPAN";
        if (bytes < 0 || bytes > p.maxBytes) return "STRICT_WINDOWS_BYTES";
        if (segments < 1) return "STRICT_WINDOWS_SEGMENTS";
        if (duplicateCheck && f[0].equals(id(1))) return "STRICT_WINDOWS_DUPLICATE";
        return "";
    }

    static String parseSeekRow(String line, Policy p) {
        String[] f = csv(line);
        if (f.length != 4) return "STRICT_SEEKS_FIELDS";
        if (!f[0].matches("s[0-9]{6}")) return "STRICT_SEEKS_ID";
        int channel = parsePositiveInt(f[1], p.channels);
        if (channel < 1) return "STRICT_SEEKS_CHANNEL";
        if (!f[2].equals("AT") && !f[2].equals("BACK") && !f[2].equals("LIVE")) return "STRICT_SEEKS_MODE";
        long position = parseSignedLong(f[3]);
        if (Long.MIN_VALUE == position) return "STRICT_SEEKS_NUMBER";
        if (position < 0 || position > p.maxPosition) return "STRICT_SEEKS_RANGE";
        return "";
    }

    static int parsePositiveInt(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > 9) return 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return 0;
        }
        long v = Long.parseLong(value);
        return v <= max ? (int) v : 0;
    }

    static long parseSignedLong(String value) {
        if (value == null || value.isEmpty() || value.length() > 18) return Long.MIN_VALUE;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' && i == 0) continue;
            if (c < '0' || c > '9') return Long.MIN_VALUE;
        }
        return Long.parseLong(value);
    }

    static List<Win> parseWindows(byte[] data, Policy p) throws QaException {
        if (data.length == 0 || data[data.length - 1] != '\n') throw new QaException("STRICT_WINDOWS_TERMINATOR");
        List<String> ls = lines(data);
        if (ls.isEmpty() || !ls.get(0).equals(WINDOWS_HEADER)) throw new QaException("STRICT_WINDOWS_HEADER");
        List<Win> out = new ArrayList<Win>();
        TreeSet<String> seen = new TreeSet<String>();
        long lastId = -1L;
        for (int i = 1; i < ls.size(); i++) {
            String[] f = csv(ls.get(i));
            String problem = parseWindowRow(ls.get(i), false, p);
            if (!problem.isEmpty()) throw new QaException(problem);
            long currentId = Long.parseLong(f[0].substring(1));
            if (currentId <= lastId) throw new QaException("STRICT_WINDOWS_ORDER");
            if (!seen.add(f[0])) throw new QaException("STRICT_WINDOWS_DUPLICATE");
            lastId = currentId;
            out.add(new Win(f[0], Integer.parseInt(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]),
                    Long.parseLong(f[4]), Integer.parseInt(f[5])));
        }
        if (out.size() > MAX_ROWS) throw new QaException("STRICT_WINDOWS_LIMIT");
        return out;
    }

    static List<Seek> parseSeeks(byte[] data, Policy p) throws QaException {
        if (data.length == 0 || data[data.length - 1] != '\n') throw new QaException("STRICT_SEEKS_TERMINATOR");
        List<String> ls = lines(data);
        if (ls.isEmpty() || !ls.get(0).equals(SEEKS_HEADER)) throw new QaException("STRICT_SEEKS_HEADER");
        List<Seek> out = new ArrayList<Seek>();
        long lastId = -1L;
        for (int i = 1; i < ls.size(); i++) {
            String[] f = csv(ls.get(i));
            String problem = parseSeekRow(ls.get(i), p);
            if (!problem.isEmpty()) throw new QaException(problem);
            long currentId = Long.parseLong(f[0].substring(1));
            if (currentId <= lastId) throw new QaException("STRICT_SEEKS_ORDER");
            lastId = currentId;
            out.add(new Seek(f[0], Integer.parseInt(f[1]), f[2], Long.parseLong(f[3])));
        }
        if (out.size() > MAX_ROWS) throw new QaException("STRICT_SEEKS_LIMIT");
        return out;
    }

    static Map<String, String> parseCorrupt(byte[] data) throws QaException {
        if (data.length == 0 || data[data.length - 1] != '\n') throw new QaException("STRICT_CORRUPT_TERMINATOR");
        List<String> ls = lines(data);
        if (ls.isEmpty() || !ls.get(0).equals(CORRUPT_HEADER)) throw new QaException("STRICT_CORRUPT_HEADER");
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 1; i < ls.size(); i++) {
            String[] f = csv(ls.get(i));
            if (f.length != 2 || f[0].isEmpty()) throw new QaException("STRICT_CORRUPT_FIELDS");
            if (out.put(f[0], f[1]) != null) throw new QaException("STRICT_CORRUPT_DUPLICATE");
        }
        if (out.size() > 64) throw new QaException("STRICT_CORRUPT_LIMIT");
        return out;
    }

    static State buildState(Policy p, List<Win> all, int channel) {
        State state = new State();
        List<Win> own = new ArrayList<Win>();
        for (Win w : all) if (w.channel == channel) own.add(w);
        if (own.isEmpty()) return state;
        long edge = 0L;
        for (Win w : own) edge = Math.max(edge, w.to);
        state.liveEdge = edge;
        long floor = edge - p.retention;
        List<long[]> clipped = new ArrayList<long[]>();
        for (Win w : own) {
            long from = Math.max(w.from, floor);
            if (from < w.to) clipped.add(new long[]{from, w.to});
        }
        clipped.sort(Comparator.comparingLong(a -> a[0]));
        while (clipped.size() > p.windowCap) clipped.remove(0);
        state.retained = clipped.size();
        if (clipped.isEmpty()) return state;
        long from = clipped.get(0)[0];
        long to = clipped.get(0)[1];
        for (int i = 1; i < clipped.size(); i++) {
            long nextFrom = clipped.get(i)[0];
            long nextTo = clipped.get(i)[1];
            if (nextFrom <= to) {
                if (nextFrom < to) state.overlapPairs++;
                else if (nextFrom == to) state.touchPairs++;
                to = Math.max(to, nextTo);
            } else {
                state.intervals.add(new long[]{from, to});
                from = nextFrom;
                to = nextTo;
            }
        }
        state.intervals.add(new long[]{from, to});
        return state;
    }

    static boolean covered(State state, long position) {
        for (long[] interval : state.intervals) {
            if (position >= interval[0] && position <= interval[1]) return true;
        }
        return false;
    }

    static Result evaluate(Policy p, State state, Seek seek) {
        if (state.empty()) return new Result(false, -1L, "EMPTY_WINDOW");
        long earliest = state.intervals.get(0)[0];
        long edge = state.liveEdge;
        long target;
        if (seek.mode.equals("LIVE")) {
            target = edge;
        } else if (seek.mode.equals("BACK")) {
            if (seek.position < earliest || seek.position > edge) return new Result(false, -1L, "POSITION_OUT_OF_WINDOW");
            target = Math.max(0L, seek.position - p.back);
        } else {
            target = seek.position;
        }
        String clip = "NONE";
        if (target < earliest) {
            target = earliest;
            clip = "LOW";
        } else if (target > edge) {
            target = edge;
            clip = "HIGH";
        } else if (!covered(state, target)) {
            long lower = earliest;
            long upper = edge;
            for (long[] interval : state.intervals) {
                if (target > interval[1]) lower = interval[1];
                if (target < interval[0]) { upper = interval[0]; break; }
            }
            target = (target - lower <= upper - target) ? lower : upper;
            clip = "GAP";
        }
        return new Result(true, target, clip);
    }

    static void check(String id, boolean ok, String detail) {
        report.add("CHECK\t" + (ok ? "PASS" : "FAIL") + "\t" + id + "\t" + detail);
        if (!ok && !firstIsFailure) { firstCode = id; firstIsFailure = true; }
    }

    static void reject(String id, boolean buggyPredicate, String detail) {
        check(id, !buggyPredicate, detail);
    }

    static void parseFailure(String code) {
        report.add("CHECK\tFAIL\t" + code + "\tparse");
        if (!firstIsFailure) { firstCode = code; firstIsFailure = true; }
    }

    static void finish(int exit) {
        int passed = 0, total = 0, negative = 0;
        for (String line : report) {
            if (!line.startsWith("CHECK\t")) continue;
            total++;
            String[] f = line.split("\t", -1);
            if (f[1].equals("PASS")) {
                passed++;
                if (f[2].startsWith("red-")) negative++;
            }
        }
        System.out.println("FIRST\t" + (firstIsFailure ? "FAIL" : "PASS") + "\t" + (firstCode.isEmpty() ? "NONE" : firstCode));
        for (String line : report) System.out.println(line);
        System.out.println("SUMMARY\tpositive=" + passed + "\tnegative-red=" + negative + "\ttotal=" + total
                + "\tresult=" + (passed == total && total > 0 ? "PASS" : "FAIL"));
        System.exit(exit);
    }

    static Map<String, byte[]> readAll(File dir) throws QaException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        File[] files = dir.listFiles();
        if (files == null) throw new QaException("FIXTURE_MISSING");
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) throw new QaException("FIXTURE_FILE_SIZE");
            try {
                out.put(file.getName(), Files.readAllBytes(file.toPath()));
            } catch (Exception e) {
                throw new QaException("FIXTURE_READ");
            }
        }
        return out;
    }

    static boolean leaky(String text, String value) {
        return text.contains("://") || text.contains("pin=") || text.contains("Bearer ")
                || text.contains("token=") || text.contains("secret=") || text.contains(value);
    }

    static int verify(String directory, String mode) {
        File dir = new File(directory);
        Map<String, byte[]> committed;
        try {
            committed = readAll(dir);
        } catch (QaException e) {
            parseFailure(e.code); finish(1); return 1;
        }
        Map<String, byte[]> gen;
        try {
            gen = generated();
        } catch (QaException e) {
            parseFailure(e.code); finish(1); return 1;
        }
        boolean mutant = mode.equals("verify-mutant");
        boolean manifestMutant = mode.equals("verify-manifest");

        if (!mutant) {
            TreeSet<String> expected = new TreeSet<String>(Arrays.asList(ALL_FILES));
            check("FILE_SET", committed.keySet().equals(expected), "files=" + committed.size());
        }
        boolean identical = true;
        for (String name : ALL_FILES) {
            if (manifestMutant && name.equals("manifest.sha256")) continue;
            if (!mutant) {
                byte[] a = committed.get(name);
                byte[] b = gen.get(name);
                if (a == null || b == null || !Arrays.equals(a, b)) identical = false;
            }
        }
        if (!mutant) check("BYTE_IDENTICAL_REGENERATION", identical, "files=" + ALL_FILES.length);

        boolean manifestOk = true;
        try {
            byte[] actualManifest = committed.get("manifest.sha256");
            if (actualManifest == null) throw new QaException("MANIFEST_MISSING");
            if (!mutant && !Arrays.equals(actualManifest, gen.get("manifest.sha256"))) manifestOk = false;
            for (String line : lines(actualManifest)) {
                String[] f = line.split("  ", -1);
                if (f.length != 2 || !Arrays.asList(DATA_FILES).contains(f[1])) { manifestOk = false; break; }
                byte[] bytes = committed.get(f[1]);
                if (bytes == null || !sha(bytes).equals(f[0])) { manifestOk = false; break; }
            }
        } catch (QaException e) {
            manifestOk = false;
        }
        check("MANIFEST_SHA256_MATCH", manifestOk, "files=" + DATA_FILES.length);

        Policy p;
        List<Win> windows;
        List<Seek> seeks;
        Map<String, String> corrupt;
        try {
            p = parsePolicy(committed.get("dimensions.tsv"));
            windows = parseWindows(committed.get("windows.tsv"), p);
            seeks = parseSeeks(committed.get("seeks.tsv"), p);
            corrupt = parseCorrupt(committed.get("corrupt.tsv"));
        } catch (QaException e) {
            parseFailure(e.code);
            finish(1);
            return 1;
        }

        // Named fail-closed corrupt cases. Each must be rejected for its own pinned reason.
        String[] cases = {"inner-bad-header", "wrong-field-count", "non-numeric-from", "reversed-interval",
                "span-too-long", "negative-time", "oversize-bytes", "bad-channel", "empty-id",
                "duplicate-id", "truncated-row", "seek-unknown-mode", "seek-negative-position",
                "seek-bad-channel", "seek-truncated"};
        String[] expected = {"STRICT_WINDOWS_HEADER", "STRICT_WINDOWS_FIELDS", "STRICT_WINDOWS_NUMBER",
                "STRICT_WINDOWS_INTERVAL", "STRICT_WINDOWS_SPAN", "STRICT_WINDOWS_RANGE",
                "STRICT_WINDOWS_BYTES", "STRICT_WINDOWS_CHANNEL", "STRICT_WINDOWS_ID",
                "STRICT_WINDOWS_DUPLICATE", "STRICT_WINDOWS_FIELDS", "STRICT_SEEKS_MODE",
                "STRICT_SEEKS_RANGE", "STRICT_SEEKS_CHANNEL", "STRICT_SEEKS_FIELDS"};
        for (int i = 0; i < cases.length; i++) {
            String code = "NONE";
            try {
                String value = unhex(corrupt.get(cases[i]));
                if (value == null) code = "CORRUPT_HEX";
                else if (cases[i].equals("inner-bad-header")) {
                    if (!value.equals(WINDOWS_HEADER)) code = "STRICT_WINDOWS_HEADER";
                } else if (cases[i].startsWith("seek-")) {
                    code = parseSeekRow(value, p);
                } else {
                    code = parseWindowRow(value, true, p);
                }
            } catch (QaException e) {
                code = e.code;
            }
            check("CORRUPT_REJECT_" + cases[i], expected[i].equals(code), "code=" + code);
        }
        String laxValue = "";
        try { laxValue = unhex(corrupt.get("reversed-interval")); } catch (QaException ignored) { }
        String[] laxFields = csv(laxValue);
        boolean laxAccepted = laxFields.length == 6 && Long.parseLong(laxFields[3]) > 0L;
        boolean strictRejects = "STRICT_WINDOWS_INTERVAL".equals(parseWindowRow(laxValue, true, p));
        check("LAX_ACCEPTS_REVERSED_INTERVAL", laxAccepted, "fields=" + laxFields.length);
        reject("red-strict-rejection-meaningful", !(laxAccepted && strictRejects), "lax-only-acceptance");

        String value = secret();
        boolean leak = false;
        for (Map.Entry<String, byte[]> e : committed.entrySet()) {
            if (leaky(new String(e.getValue(), StandardCharsets.UTF_8), value)) leak = true;
        }
        check("LEAK_SCAN", !leak, "files=" + committed.size());
        reject("red-secret-in-fixture", leak, "derived-marker");

        Map<Integer, State> states = new HashMap<Integer, State>();
        for (int channel = 1; channel <= p.channels; channel++) {
            states.put(channel, buildState(p, windows, channel));
        }
        boolean capBinds = true;
        for (int channel = 1; channel <= p.normalChannels; channel++) {
            State state = states.get(channel);
            if (state.empty() || state.retained != p.windowCap) capBinds = false;
        }
        check("WINDOW_CAP_BINDS", capBinds, "cap=" + p.windowCap + " channels=" + p.normalChannels);

        boolean retentionOk = true;
        for (int channel = 1; channel <= p.normalChannels; channel++) {
            for (Win w : windows) {
                if (w.channel != channel || w.from < states.get(channel).liveEdge - p.retention) continue;
                if (w.to > states.get(channel).liveEdge) retentionOk = false;
            }
        }
        check("RETENTION_BOUND", retentionOk, "retention=" + p.retention);

        long overlapPairs = 0;
        long touchPairs = 0;
        long unionCount = 0;
        for (State state : states.values()) {
            overlapPairs += state.overlapPairs;
            touchPairs += state.touchPairs;
            unionCount += state.intervals.size();
        }
        check("OVERLAP_COUNT", overlapPairs == 5L * p.normalChannels, "pairs=" + overlapPairs);
        check("TOUCHING_NOT_OVERLAP", touchPairs >= 1L
                && states.get(TOUCHING_CHANNEL).overlapPairs == 0
                && states.get(TOUCHING_CHANNEL).intervals.size() == 1,
                "touch=" + touchPairs + " touch-overlaps=" + states.get(TOUCHING_CHANNEL).overlapPairs);
        check("UNION_CONTIGUITY", unionCount == 2L * p.normalChannels + 2L, "unions=" + unionCount);

        long accepted = 0, rejected = 0, backsExact = 0, backsClamped = 0;
        long liveEdges = 0, lowClips = 0, highClips = 0, gapClips = 0, emptyRejects = 0;
        boolean boundsOk = true, rejectedStill = true, buggyBoundsOk = true;
        for (Seek seek : seeks) {
            State state = states.get(seek.channel);
            Result result = evaluate(p, state, seek);
            if (!state.empty()) {
                long raw = seek.mode.equals("LIVE") ? state.liveEdge
                        : seek.mode.equals("BACK") ? Math.max(0L, seek.position - p.back) : seek.position;
                if (raw < state.intervals.get(0)[0] || raw > state.liveEdge || !covered(state, raw)) {
                    buggyBoundsOk = false;
                }
            }
            if (!result.accepted) {
                rejected++;
                if (result.position != -1L) rejectedStill = false;
                if (state.empty() && result.code.equals("EMPTY_WINDOW")) emptyRejects++;
                continue;
            }
            accepted++;
            if (result.position < 0L || result.position > state.liveEdge || !covered(state, result.position)) boundsOk = false;
            if (seek.mode.equals("BACK")) {
                long raw = Math.max(0L, seek.position - p.back);
                if (result.code.equals("NONE") && result.position == raw) backsExact++;
                if (result.code.equals("LOW") && result.position == state.intervals.get(0)[0]) backsClamped++;
            }
            if (seek.mode.equals("LIVE") && result.position == state.liveEdge) liveEdges++;
            if (result.code.equals("LOW")) lowClips++;
            if (result.code.equals("HIGH")) highClips++;
            if (result.code.equals("GAP")) gapClips++;
        }
        check("SEEK_RESULT_BOUNDS", boundsOk, "accepted=" + accepted);
        check("SEEK_BACK_EXACT", backsExact > 0L, "exact=" + backsExact);
        check("SEEK_BACK_CLAMP", backsClamped > 0L, "clamped=" + backsClamped);
        check("SEEK_LIVE_EDGE", liveEdges > 0L, "live=" + liveEdges);
        check("BOUNDARY_CLIPS", lowClips > 0L && highClips > 0L && gapClips > 0L,
                "low=" + lowClips + " high=" + highClips + " gap=" + gapClips);
        check("EMPTY_WINDOW_FAIL_CLOSED", emptyRejects >= 2L * p.seekRows, "rejected=" + emptyRejects);
        check("REJECTED_NEVER_MOVES", rejectedStill, "rejected=" + rejected);
        reject("red-unclamped-position", buggyBoundsOk, "clip-disabled");

        check("MIRROR_DIGEST_PINNED", p.mirrorDigest.equals(MIRROR_DIGEST),
                "sha256=pinned");
        check("PINNED_DIMENSIONS", Arrays.equals(committed.get("dimensions.tsv"), gen.get("dimensions.tsv")),
                "bytes=" + committed.get("dimensions.tsv").length);
        check("FIXTURE_ROW_COUNTS", windows.size() == 3_587 && seeks.size() == 2_600 && corrupt.size() == 15,
                "windows=" + windows.size() + " seeks=" + seeks.size() + " corrupt=" + corrupt.size());
        check("BOUNDED_WORKLOAD", windows.size() <= MAX_ROWS && seeks.size() <= MAX_ROWS
                && report.size() <= 160, "windows=" + windows.size() + " seeks=" + seeks.size());

        StringBuilder allText = new StringBuilder();
        for (String line : report) allText.append(line).append('\n');
        boolean reportLeak = leaky(allText.toString(), value);
        check("DIAGNOSTICS_SECRET_FREE", !reportLeak, "lines=" + report.size());
        reject("red-secret-in-diagnostics", reportLeak, "derived-marker");

        int ok = 0, total = 0;
        for (String line : report) {
            if (line.startsWith("CHECK\tPASS")) ok++;
            if (line.startsWith("CHECK\t")) total++;
        }
        finish(ok == total ? 0 : 1);
        return 0;
    }

    static void writeAll(File dir, Map<String, byte[]> files) throws QaException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new QaException("FIXTURE_MKDIR");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            try {
                Files.write(new File(dir, e.getKey()).toPath(), e.getValue());
            } catch (Exception ex) {
                throw new QaException("FIXTURE_WRITE");
            }
        }
    }

    static String replaceField(String line, int index, String replacement) {
        String[] f = csv(line);
        f[index] = replacement;
        return String.join("\t", f);
    }

    static String remanifestText(Map<String, byte[]> files) throws QaException {
        Map<String, byte[]> data = new LinkedHashMap<String, byte[]>();
        for (String name : DATA_FILES) data.put(name, files.get(name));
        return new String(manifest(data), StandardCharsets.UTF_8);
    }

    static int mutate(String source, String kind, String destination) {
        File src = new File(source);
        File dst = new File(destination);
        try {
            Map<String, byte[]> files = readAll(src);
            if (kind.equals("flip") || kind.equals("manifest")) {
                String name = kind.equals("flip") ? "windows.tsv" : "manifest.sha256";
                String text = new String(files.get(name), StandardCharsets.UTF_8);
                if (kind.equals("flip")) {
                    String[] ls = text.split("\n", -1);
                    ls[1] = replaceField(ls[1], 4, "2048");
                    text = String.join("\n", ls);
                } else {
                    char first = text.charAt(0);
                    char other = first == '0' ? '1' : '0';
                    text = other + text.substring(1);
                }
                files.put(name, utf8(text));
            } else if (kind.equals("header")) {
                String text = new String(files.get("windows.tsv"), StandardCharsets.UTF_8);
                files.put("windows.tsv", utf8(text.replace(WINDOWS_HEADER, "QA13-WINDOWS-V2\tid\tchannel\tfrom\tto\tbytes\tsegments")));
            } else if (kind.equals("fields")) {
                String[] ls = new String(files.get("windows.tsv"), StandardCharsets.UTF_8).split("\n", -1);
                String[] f = csv(ls[1]);
                ls[1] = String.join("\t", Arrays.copyOf(f, f.length - 1));
                files.put("windows.tsv", utf8(String.join("\n", ls)));
            } else if (kind.equals("truncated")) {
                String text = new String(files.get("seeks.tsv"), StandardCharsets.UTF_8);
                files.put("seeks.tsv", utf8(text.substring(0, text.length() - 1)));
            } else if (kind.equals("bounds")) {
                String[] ls = new String(files.get("windows.tsv"), StandardCharsets.UTF_8).split("\n", -1);
                ls[1] = replaceField(ls[1], 3, "999999999");
                files.put("windows.tsv", utf8(String.join("\n", ls)));
            } else if (kind.equals("leak")) {
                files.put("leak.txt", utf8(secret() + "\n"));
            } else if (kind.equals("cap")) {
                String text = new String(files.get("dimensions.tsv"), StandardCharsets.UTF_8);
                files.put("dimensions.tsv", utf8(text.replace("window-cap=7", "window-cap=64")));
            } else if (kind.equals("overlap")) {
                StringBuilder w = new StringBuilder(WINDOWS_HEADER).append('\n');
                int n = 0;
                for (int channel = 1; channel <= NORMAL_CHANNELS; channel++) {
                    for (int i = 0; i < WINDOW_CAP; i++) {
                        long from = 200_000L + i * 2_000L;
                        w.append(id(++n)).append('\t').append(channel).append('\t').append(from)
                                .append('\t').append(from + 1_000L).append('\t').append(1024).append('\t').append(2).append('\n');
                    }
                }
                w.append(id(++n)).append('\t').append(SINGLE_CHANNEL).append("\t10000\t17000\t1024\t2\n");
                w.append(id(++n)).append('\t').append(TOUCHING_CHANNEL).append("\t10000\t17000\t1024\t2\n");
                w.append(id(++n)).append('\t').append(TOUCHING_CHANNEL).append("\t17000\t24000\t1536\t3\n");
                files.put("windows.tsv", utf8(w.toString()));
            } else if (kind.equals("seekbound")) {
                String[] ls = new String(files.get("seeks.tsv"), StandardCharsets.UTF_8).split("\n", -1);
                ls[1] = replaceField(ls[1], 3, "-5");
                files.put("seeks.tsv", utf8(String.join("\n", ls)));
            } else if (kind.equals("unknownmode")) {
                String[] ls = new String(files.get("seeks.tsv"), StandardCharsets.UTF_8).split("\n", -1);
                ls[1] = replaceField(ls[1], 2, "JUMP");
                files.put("seeks.tsv", utf8(String.join("\n", ls)));
            } else if (kind.equals("corruptaccepted")) {
                String[] ls = new String(files.get("corrupt.tsv"), StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 1; i < ls.length; i++) {
                    if (ls[i].startsWith("bad-channel\t")) {
                        ls[i] = "bad-channel\t" + hex("w999999\t1\t10000\t17000\t1024\t2");
                    }
                }
                files.put("corrupt.tsv", utf8(String.join("\n", ls)));
            } else if (kind.equals("mirror")) {
                String text = new String(files.get("dimensions.tsv"), StandardCharsets.UTF_8);
                char first = MIRROR_DIGEST.charAt(0);
                char other = first == '0' ? '1' : '0';
                int at = text.indexOf("mirror-digest=") + "mirror-digest=".length();
                text = text.substring(0, at) + other + text.substring(at + 1);
                files.put("dimensions.tsv", utf8(text));
            } else {
                System.out.println("MUTATE\tFAIL\tunknown-kind");
                return 2;
            }
            if (!kind.equals("flip") && !kind.equals("manifest")) {
                files.put("manifest.sha256", utf8(remanifestText(files)));
            }
            writeAll(dst, files);
            System.out.println("MUTATE\tPASS\t" + kind);
            return 0;
        } catch (QaException e) {
            System.out.println("MUTATE\tFAIL\t" + e.code);
            return 2;
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) throw new QaException("USAGE");
            String mode = args[0];
            if (mode.equals("generate") && args.length == 2) {
                File dir = new File(args[1]);
                writeAll(dir, generated());
                System.out.println("GENERATE\tPASS\tfiles=" + ALL_FILES.length);
                return;
            }
            if (mode.equals("mutate") && args.length == 4) {
                System.exit(mutate(args[1], args[2], args[3]));
                return;
            }
            if ((mode.equals("verify") || mode.equals("verify-mutant") || mode.equals("verify-manifest"))
                    && args.length == 2) {
                verify(args[1], mode);
                return;
            }
            throw new QaException("USAGE");
        } catch (QaException e) {
            System.out.println("FIRST\tFAIL\t" + e.code);
            System.out.println("SUMMARY\tpositive=0\tnegative-red=0\ttotal=0\tresult=FAIL");
            System.exit(2);
        } catch (Exception e) {
            System.out.println("FIRST\tFAIL\tEXCEPTION_" + e.getClass().getSimpleName());
            System.out.println("SUMMARY\tpositive=0\tnegative-red=0\ttotal=0\tresult=FAIL");
            System.exit(2);
        }
    }
}
