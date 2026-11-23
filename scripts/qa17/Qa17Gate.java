import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA-17 - deterministic, zero-dependency host gate for SAF recording destination validation and
 * recording-location lifetime.
 *
 * Distinct audit slice: QA-02/QA-06/QA-15 cover EPG structure and import, QA-04/QA-08/QA-12 cover
 * DVR scheduling and crash recovery, QA-11 backup restore, QA-13 timeshift, QA-14 reminders,
 * QA-16 cancellable import staging. The shared device smoke script only checks a live SAF recording
 * end to end; no deterministic host gate covers destination selection, volume/capacity gating,
 * persisted tree containment, internal-root confinement, document-state fail-closed mapping, or
 * bounded heap/thread use for those decisions.
 *
 * Every identifier is invented and derived from a fixed seed at run time. No external feed address,
 * access marker, personal code, key or media payload is stored as a literal in this source.
 * Diagnostics carry only check ids, counts and digests, and are scanned before being printed.
 */
public final class Qa17Gate {
    private static final String VERSION = "qa17-fixture-v1";
    private static final String SEED = "QA17-FIXED-SEED-2026-11-23";

    // Android platform SAF documents authority, not an external provider address.
    private static final String SAF_AUTHORITY = "com.android.externalstorage.documents";
    private static final String DIR_MIME = "vnd.android.document/directory";
    private static final long DIR_SUPPORTS_CREATE = 1L << 10;

    private static final int PREF_ROWS = 64;
    private static final int VOLUME_ROWS = 6;
    private static final int GRANT_ROWS = 13;
    private static final int LOCATION_ROWS = 96;
    private static final int CORRUPT_ROWS = 12;

    private static final int MAX_LINE_BYTES = 320;
    private static final int MAX_REPORT_BYTES = 8192;
    private static final long MAX_HEAP_BYTES = 48L * 1024L * 1024L;
    private static final long MAX_HEAP_DELTA = 32L * 1024L * 1024L;
    private static final int MAX_THREADS = 4;

    private static final String[] FILES = {
        "contract.txt", "dimensions.tsv", "prefs.tsv", "volumes.tsv",
        "grants.tsv", "locations.tsv", "corrupt.tsv", "sha256.txt"
    };
    private static final String[] TABLES = {
        "dimensions.tsv", "prefs.tsv", "volumes.tsv", "grants.tsv",
        "locations.tsv", "corrupt.tsv"
    };

    private static final String PREF_HEADER = "id\traw\texpected\n";
    private static final String VOLUME_HEADER = "volumeId\tusableBytes\tmime\tflags\tstate\n";
    private static final String GRANT_HEADER = "grantId\tauthority\ttreeId\tread\twrite\n";
    private static final String LOCATION_HEADER =
        "id\traw\tgrant\tvolumeId\tdocState\texpectedPlay\texpectedDelete\n";
    private static final String CORRUPT_HEADER = "case\tpayloadHex\texpected\n";

    private static final String CONTRACT_TEXT =
        "QA-17 synthetic scratch fixture for SAF recording destination and location lifetime.\n" +
        "Identifiers, tree ids, document ids and paths are invented tokens derived from a seed.\n" +
        "Tables are tab separated with exact headers; dimensions use equals-separated pairs.\n" +
        "No external feed address, access marker, personal code, key or media bytes are stored.\n";

    private static final String ACCEPT = "ACCEPT";
    private static final String BLANK = "BLANK";
    private static final String BAD_SCHEME = "BAD_SCHEME";
    private static final String BAD_AUTHORITY = "BAD_AUTHORITY";
    private static final String TRUNCATED = "TRUNCATED";
    private static final String MALFORMED = "MALFORMED";
    private static final String BAD_TREE_ID = "BAD_TREE_ID";
    private static final Set<String> PREF_CODES = new LinkedHashSet<>(Arrays.asList(
        ACCEPT, BLANK, BAD_SCHEME, BAD_AUTHORITY, TRUNCATED, MALFORMED, BAD_TREE_ID));

    private static final String PLAYABLE = "PLAYABLE";
    private static final String NONE = "NONE";
    private static final String DELETED = "DELETED";
    private static final String MISSING = "MISSING";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String INVALID = "INVALID";
    private static final Set<String> DELETE_CODES = new LinkedHashSet<>(Arrays.asList(
        DELETED, MISSING, UNAVAILABLE, INVALID));

    private static final String ROOT_A = "/scratch/qa17/internal/recordings";
    private static final String ROOT_B = "/scratch/qa17/external/recordings";

    private static final Pattern HEX = Pattern.compile("[0-9a-f]*");
    private static final Pattern REPORT_LEAK = Pattern.compile(
        "(?i)(https?://|content://|file://|ftp://|bearer[ =:]|token[=:]|password|passwd|" +
        "pin[=:]|secret|credential|drm|key[=:]|\\.m3u|\\.xmltv|scratch-local|rec0[0-9]{3})");
    private static final Pattern FIXTURE_LEAK = Pattern.compile(
        "(?i)(https?://|ftp://|bearer[ =:]|token[=:]|password|passwd|pin[=:]|secret|" +
        "credential|drm|key[=:]|\\.m3u|\\.xmltv|scratch-local)");

    private Qa17Gate() {}

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable failure) {
            // Never print exception text: it may carry raw input. The class name is safe.
            System.out.println("FIRST\tINTERNAL_ERROR\t" + failure.getClass().getSimpleName());
            System.out.println("SUMMARY\tpositive=0\tfailed=1");
            code = 2;
        }
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: Qa17Gate [generate|verify|mutate] ...");
            return 2;
        }
        if (args[0].equals("generate") && args.length == 2) {
            Fixture fixture = build();
            writeFixture(Paths.get(args[1]), fixture);
            System.out.println("PIN\t" + fixture.manifest.get("all"));
            return 0;
        }
        if (args[0].equals("mutate") && args.length == 4) {
            return mutate(args[1], args[2], args[3]);
        }
        if (args[0].equals("verify") && args.length >= 3 && args.length <= 4) {
            boolean skipIdentity = args.length == 4 && args[1].equals("--skip-identity");
            boolean skipSha = args.length == 4 && args[1].equals("--skip-sha");
            Path fixtureDir = Paths.get(args[args.length - 2]);
            Path scratchDir = Paths.get(args[args.length - 1]);
            Files.createDirectories(scratchDir);
            return verify(fixtureDir, scratchDir, skipIdentity, skipSha);
        }
        System.out.println("usage: Qa17Gate generate <dir> | verify [--skip-identity|--skip-sha] <fixture> <scratch> | mutate <fixture> <out> <kind>");
        return 2;
    }

    // ---------------------------------------------------------------- model

    private static String marker(String tag) {
        return hex(sha((SEED + "|" + tag).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    private static String enc(String value) {
        return value.replace("%", "%25").replace(":", "%3A").replace("/", "%2F");
    }

    private static String dec(String value) {
        return value.replace("%2F", "/").replace("%3A", ":").replace("%25", "%");
    }

    private static boolean controlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private static boolean badChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ' ' || c == '"') return true;
        }
        return false;
    }

    private static String classifyPref(String raw) {
        if (raw.isEmpty()) return BLANK;
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd < 0) return MALFORMED;
        if (!raw.substring(0, schemeEnd).equals("content")) return BAD_SCHEME;
        String rest = raw.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) return MALFORMED;
        if (!rest.substring(0, slash).equals(SAF_AUTHORITY)) return BAD_AUTHORITY;
        String path = rest.substring(slash + 1);
        if (!path.startsWith("tree/")) return TRUNCATED;
        String encodedId = path.substring(5);
        if (encodedId.isEmpty()) return TRUNCATED;
        String treeId = dec(encodedId);
        if (badChars(treeId) || treeId.indexOf('%') >= 0) return MALFORMED;
        if (treeId.contains("..") || treeId.indexOf(':') < 0) return BAD_TREE_ID;
        return ACCEPT;
    }

    private static String[] classifyInternal(String path, String docState) {
        if (path.isEmpty() || badChars(path) || path.contains("..") ||
            path.contains("\\") || path.contains("%")) {
            return new String[]{NONE, INVALID};
        }
        boolean inside = false;
        for (String root : new String[]{ROOT_A, ROOT_B}) {
            if (path.startsWith(root + "/") && path.length() > root.length() + 1) {
                inside = true;
                break;
            }
        }
        if (!inside) return new String[]{NONE, INVALID};
        return stateResult(docState);
    }

    private static String[] stateResult(String docState) {
        if (docState.equals(MISSING)) return new String[]{NONE, MISSING};
        if (docState.equals(UNAVAILABLE)) return new String[]{NONE, UNAVAILABLE};
        return new String[]{PLAYABLE, DELETED};
    }

    private static String[] classifyLocation(String raw, String grantId, String docState,
                                             Map<String, Grant> grants,
                                             Map<String, Volume> volumes) {
        if (raw.isEmpty()) return new String[]{NONE, MISSING};
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd < 0) return classifyInternal(raw, docState);
        String scheme = raw.substring(0, schemeEnd);
        if (scheme.equals("file")) return classifyInternal(raw.substring(schemeEnd + 3), docState);
        if (!scheme.equals("content")) return new String[]{NONE, INVALID};
        String prefix = "content://" + SAF_AUTHORITY + "/";
        if (!raw.startsWith(prefix)) return new String[]{NONE, INVALID};
        String body = raw.substring(prefix.length());
        int split = body.indexOf("/document/");
        if (split <= 0) return new String[]{NONE, INVALID};
        String treeId = dec(body.substring(0, split));
        String documentId = dec(body.substring(split + "/document/".length()));
        if (treeId.isEmpty() || documentId.isEmpty() || badChars(treeId) || badChars(documentId) ||
            treeId.indexOf('%') >= 0 || documentId.indexOf('%') >= 0) {
            return new String[]{NONE, INVALID};
        }
        Grant grant = grants.get(grantId);
        if (grant == null || !grant.authority.equals(SAF_AUTHORITY)) {
            return new String[]{NONE, INVALID};
        }
        boolean contained;
        if (grant.treeId.isEmpty()) {
            contained = false;
        } else if (grant.treeId.endsWith(":")) {
            contained = documentId.startsWith(grant.treeId);
        } else {
            contained = documentId.equals(grant.treeId) ||
                documentId.startsWith(grant.treeId + "/");
        }
        if (!contained || !grant.read) return new String[]{NONE, INVALID};
        String[] byState = stateResult(docState);
        if (!byState[1].equals(DELETED)) return byState;
        String[] volumeCheck = volumeGate(documentId, volumes);
        if (volumeCheck != null) return volumeCheck;
        if (!grant.write) return new String[]{PLAYABLE, INVALID};
        return new String[]{PLAYABLE, DELETED};
    }

    private static String[] volumeGate(String documentId, Map<String, Volume> volumes) {
        int colon = documentId.indexOf(':');
        if (colon <= 0) return new String[]{NONE, INVALID};
        Volume volume = volumes.get(documentId.substring(0, colon));
        if (volume == null || volume.usableBytes < 0L ||
            !volume.mime.equals(DIR_MIME) || (volume.flags & DIR_SUPPORTS_CREATE) == 0L) {
            return new String[]{NONE, INVALID};
        }
        return null;
    }

    // ---------------------------------------------------------------- fixture

    private static final class Pref {
        final String id;
        final String raw;
        final String expected;
        Pref(String id, String raw, String expected) {
            this.id = id; this.raw = raw; this.expected = expected;
        }
    }

    private static final class Volume {
        final String id;
        final long usableBytes;
        final String mime;
        final long flags;
        final String state;
        Volume(String id, long usableBytes, String mime, long flags, String state) {
            this.id = id; this.usableBytes = usableBytes; this.mime = mime;
            this.flags = flags; this.state = state;
        }
    }

    private static final class Grant {
        final String id;
        final String authority;
        final String treeId;
        final boolean read;
        final boolean write;
        Grant(String id, String authority, String treeId, boolean read, boolean write) {
            this.id = id; this.authority = authority; this.treeId = treeId;
            this.read = read; this.write = write;
        }
    }

    private static final class Location {
        final String id;
        final String raw;
        final String grant;
        final String volumeId;
        final String docState;
        final String play;
        final String delete;
        Location(String id, String raw, String grant, String volumeId, String docState,
                 String play, String delete) {
            this.id = id; this.raw = raw; this.grant = grant; this.volumeId = volumeId;
            this.docState = docState; this.play = play; this.delete = delete;
        }
    }

    private static final class Corrupt {
        final String id;
        final String payloadHex;
        final String expected;
        Corrupt(String id, String payloadHex, String expected) {
            this.id = id; this.payloadHex = payloadHex; this.expected = expected;
        }
    }

    private static final class Fixture {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Map<String, String> manifest = new LinkedHashMap<>();
        final Map<String, String> dimensions = new LinkedHashMap<>();
        final List<Pref> prefs = new ArrayList<>();
        final List<Volume> volumes = new ArrayList<>();
        final List<Grant> grants = new ArrayList<>();
        final List<Location> locations = new ArrayList<>();
        final List<Corrupt> corrupt = new ArrayList<>();
    }

    private static String pad(int value, int width) {
        String text = Integer.toString(value);
        StringBuilder out = new StringBuilder();
        for (int i = text.length(); i < width; i++) out.append('0');
        return out.append(text).toString();
    }

    private static Fixture build() {
        Fixture f = new Fixture();
        String[] volumeIds = {
            marker("vol-primary"), marker("vol-sd-a"), marker("vol-negative"),
            marker("vol-zero"), marker("vol-wrong-mime"), marker("vol-no-create")
        };
        String treeMain = volumeIds[0] + ":" + marker("tree-main-root");
        String treeSub = volumeIds[1] + ":" + marker("tree-sub") + "/sub";
        String treeOther = volumeIds[2] + ":" + marker("tree-other");
        String treeWrongMime = volumeIds[4] + ":" + marker("tree-wrong-mime");
        String treeNoCreate = volumeIds[5] + ":" + marker("tree-no-create");
        long[] usable = {10_000_000_000L, 4_000_000_000L, -1L, 0L, 8_000_000_000L, 6_000_000_000L};
        String[] mime = {DIR_MIME, DIR_MIME, DIR_MIME, DIR_MIME, "application/octet-stream", DIR_MIME};
        long[] flags = {DIR_SUPPORTS_CREATE, DIR_SUPPORTS_CREATE, DIR_SUPPORTS_CREATE,
            DIR_SUPPORTS_CREATE, DIR_SUPPORTS_CREATE, 0L};
        for (int i = 0; i < VOLUME_ROWS; i++) {
            f.volumes.add(new Volume(volumeIds[i], usable[i], mime[i], flags[i], "EXISTS"));
        }
        Map<String, Volume> volumeMap = new LinkedHashMap<>();
        for (Volume volume : f.volumes) volumeMap.put(volume.id, volume);

        String wrongAuthority = "scratch.invalid";
        f.grants.add(new Grant("g-root", SAF_AUTHORITY, treeMain, true, true));
        f.grants.add(new Grant("g-sub", SAF_AUTHORITY, treeSub, true, true));
        f.grants.add(new Grant("g-readonly", SAF_AUTHORITY, treeMain, true, false));
        f.grants.add(new Grant("g-wrongauth", wrongAuthority, treeMain, true, true));
        f.grants.add(new Grant("g-other", SAF_AUTHORITY, treeOther, true, true));
        f.grants.add(new Grant("g-noread", SAF_AUTHORITY, treeMain, false, true));
        f.grants.add(new Grant("g-empty", SAF_AUTHORITY, "", true, true));
        f.grants.add(new Grant("g-expired", SAF_AUTHORITY, treeMain, true, true));
        f.grants.add(new Grant("g-neg", SAF_AUTHORITY, treeOther, true, true));
        f.grants.add(new Grant("g-wrongmime", SAF_AUTHORITY, treeWrongMime, true, true));
        f.grants.add(new Grant("g-nocreate", SAF_AUTHORITY, treeNoCreate, true, true));
        f.grants.add(new Grant("g-second", SAF_AUTHORITY, treeOther, true, true));
        f.grants.add(new Grant("g-sub2", SAF_AUTHORITY, treeSub, true, false));
        Map<String, Grant> grantMap = new LinkedHashMap<>();
        for (Grant grant : f.grants) grantMap.put(grant.id, grant);

        StringBuilder prefTable = new StringBuilder(PREF_HEADER);
        for (int i = 0; i < PREF_ROWS; i++) {
            String id = "qa17pref-" + pad(i, 3);
            String raw;
            switch (i % 8) {
                case 0: raw = "content://" + SAF_AUTHORITY + "/tree/" + enc(treeMain); break;
                case 1: raw = ""; break;
                case 2: raw = "content-x://" + SAF_AUTHORITY + "/tree/" + enc(treeMain); break;
                case 3: raw = "content://" + wrongAuthority + "/tree/" + enc(treeMain); break;
                case 4: raw = "content://" + SAF_AUTHORITY + "/tree/"; break;
                case 5: raw = "content://" + SAF_AUTHORITY + "/tree/" + enc(treeMain) + " "; break;
                case 6: raw = "content://" + SAF_AUTHORITY + "/tree/.."; break;
                default: raw = marker("bare-pref"); break;
            }
            String expected = classifyPref(raw);
            f.prefs.add(new Pref(id, raw, expected));
            prefTable.append(id).append('\t').append(raw).append('\t').append(expected).append('\n');
        }

        StringBuilder volumeTable = new StringBuilder(VOLUME_HEADER);
        for (Volume volume : f.volumes) {
            volumeTable.append(volume.id).append('\t').append(volume.usableBytes).append('\t')
                .append(volume.mime).append('\t').append(volume.flags).append('\t')
                .append(volume.state).append('\n');
        }

        StringBuilder grantTable = new StringBuilder(GRANT_HEADER);
        for (Grant grant : f.grants) {
            grantTable.append(grant.id).append('\t').append(grant.authority).append('\t')
                .append(grant.treeId).append('\t').append(grant.read ? 1 : 0).append('\t')
                .append(grant.write ? 1 : 0).append('\n');
        }

        StringBuilder locationTable = new StringBuilder(LOCATION_HEADER);
        for (int i = 0; i < LOCATION_ROWS; i++) {
            String id = "qa17loc-" + pad(i, 3);
            String grantId = "g-root";
            String volumeId = volumeIds[0];
            String docState = "EXISTS";
            String raw;
            String docName = marker("doc") + "-" + pad(i, 3);
            switch (i % 16) {
                case 0:
                    raw = docUri(treeMain, docName); break;
                case 1:
                    grantId = "g-sub"; volumeId = volumeIds[1];
                    raw = docUri(treeSub, docName); break;
                case 2:
                    docState = MISSING;
                    raw = docUri(treeMain, docName); break;
                case 3:
                    docState = UNAVAILABLE;
                    raw = docUri(treeMain, docName); break;
                case 4:
                    grantId = "g-wrongauth"; volumeId = volumeIds[0];
                    raw = "content://" + wrongAuthority + "/tree/" + enc(treeMain) +
                        "/document/" + enc(treeMain + "/" + docName); break;
                case 5:
                    grantId = "g-sub"; volumeId = volumeIds[1];
                    raw = docUri(treeSub + "-evil", docName); break;
                case 6:
                    grantId = "g-readonly";
                    raw = docUri(treeMain, docName); break;
                case 7:
                    grantId = "g-noread";
                    raw = docUri(treeMain, docName); break;
                case 8:
                    grantId = "g-neg"; volumeId = volumeIds[2];
                    raw = docUri(treeOther, docName); break;
                case 9:
                    grantId = "g-wrongmime"; volumeId = volumeIds[4];
                    raw = docUri(treeWrongMime, docName); break;
                case 10:
                    grantId = "g-nocreate"; volumeId = volumeIds[5];
                    raw = docUri(treeNoCreate, docName); break;
                case 11:
                    raw = ROOT_A + "/" + marker("file-" + i); break;
                case 12:
                    raw = ROOT_A + "/../outside/" + marker("file-" + i); break;
                case 13:
                    raw = ROOT_A + "-other/" + marker("file-" + i); break;
                case 14:
                    docState = MISSING;
                    raw = ROOT_B + "/" + marker("file-" + i); break;
                default:
                    raw = ""; break;
            }
            String[] result = classifyLocation(raw, grantId, docState, grantMap, volumeMap);
            f.locations.add(new Location(id, raw, grantId, volumeId, docState, result[0], result[1]));
            locationTable.append(id).append('\t').append(raw).append('\t').append(grantId)
                .append('\t').append(volumeId).append('\t').append(docState).append('\t')
                .append(result[0]).append('\t').append(result[1]).append('\n');
        }

        StringBuilder corruptTable = new StringBuilder(CORRUPT_HEADER);
        byte[][] payloads = new byte[][]{
            new byte[0],
            "content://".getBytes(StandardCharsets.UTF_8),
            ("content://" + SAF_AUTHORITY + "/tree/").getBytes(StandardCharsets.UTF_8),
            "content://scratch.invalid/tree/x:y".getBytes(StandardCharsets.UTF_8),
            ("content://" + SAF_AUTHORITY + "/tree/" + enc(treeMain) + "\t")
                .getBytes(StandardCharsets.UTF_8),
            ("content://" + SAF_AUTHORITY + "/tree/" + enc(treeMain) + "\n")
                .getBytes(StandardCharsets.UTF_8),
            new byte[]{'c', 'o', 'n', 't', 'e', 'n', 't', ':', '/', '/', (byte) 0x00},
            new byte[]{'c', 'o', 'n', 't', 'e', 'n', 't', ':', '/', '/', (byte) 0xff},
            new byte[]{1, 2, 3, 4},
            repeatBytes('q', 120),
            ("content://" + SAF_AUTHORITY + "/tree/" + enc(treeMain) + "%ZZ")
                .getBytes(StandardCharsets.UTF_8),
            (marker("bare-pref") + "/..").getBytes(StandardCharsets.UTF_8)
        };
        for (int i = 0; i < CORRUPT_ROWS; i++) {
            String id = "qa17corrupt-" + pad(i, 3);
            f.corrupt.add(new Corrupt(id, hex(payloads[i]), "CORRUPT_FAIL_CLOSED"));
            corruptTable.append(id).append('\t').append(hex(payloads[i]))
                .append('\t').append("CORRUPT_FAIL_CLOSED").append('\n');
        }

        f.dimensions.put("fixture.version", VERSION);
        f.dimensions.put("seed.tag", marker("seed-tag"));
        f.dimensions.put("prefs.rows", Integer.toString(PREF_ROWS));
        f.dimensions.put("prefs.accepted",
            Integer.toString(count(f.prefs, p -> p.expected.equals(ACCEPT))));
        f.dimensions.put("prefs.rejected",
            Integer.toString(PREF_ROWS - count(f.prefs, p -> p.expected.equals(ACCEPT))));
        f.dimensions.put("volumes.rows", Integer.toString(VOLUME_ROWS));
        f.dimensions.put("volumes.negative",
            Integer.toString(count(f.volumes, v -> v.usableBytes < 0L)));
        f.dimensions.put("grants.rows", Integer.toString(GRANT_ROWS));
        f.dimensions.put("grants.readonly",
            Integer.toString(count(f.grants, g -> !g.write)));
        f.dimensions.put("locations.rows", Integer.toString(LOCATION_ROWS));
        f.dimensions.put("locations.playable",
            Integer.toString(count(f.locations, l -> l.play.equals(PLAYABLE))));
        f.dimensions.put("locations.deleted",
            Integer.toString(count(f.locations, l -> l.delete.equals(DELETED))));
        f.dimensions.put("locations.invalid",
            Integer.toString(count(f.locations, l -> l.delete.equals(INVALID))));
        f.dimensions.put("locations.missing",
            Integer.toString(count(f.locations, l -> l.delete.equals(MISSING))));
        f.dimensions.put("locations.unavailable",
            Integer.toString(count(f.locations, l -> l.delete.equals(UNAVAILABLE))));
        f.dimensions.put("corrupt.rows", Integer.toString(CORRUPT_ROWS));
        f.dimensions.put("corrupt.rejected", Integer.toString(CORRUPT_ROWS));
        f.dimensions.put("model.pref.digest", digestOfPrefs(f.prefs));
        f.dimensions.put("model.location.digest", digestOfLocations(f.locations));
        f.dimensions.put("model.corrupt.digest", digestOfCorrupt(f.corrupt));
        f.dimensions.put("model.mirror.digest", mirrorDigest());
        f.dimensions.put("limits.heap.bytes", Long.toString(MAX_HEAP_BYTES));
        f.dimensions.put("limits.threads", Integer.toString(MAX_THREADS));

        StringBuilder dimensionTable = new StringBuilder();
        for (Map.Entry<String, String> entry : f.dimensions.entrySet()) {
            dimensionTable.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        f.files.put("contract.txt", CONTRACT_TEXT.getBytes(StandardCharsets.UTF_8));
        f.files.put("dimensions.tsv", dimensionTable.toString().getBytes(StandardCharsets.UTF_8));
        f.files.put("prefs.tsv", prefTable.toString().getBytes(StandardCharsets.UTF_8));
        f.files.put("volumes.tsv", volumeTable.toString().getBytes(StandardCharsets.UTF_8));
        f.files.put("grants.tsv", grantTable.toString().getBytes(StandardCharsets.UTF_8));
        f.files.put("locations.tsv", locationTable.toString().getBytes(StandardCharsets.UTF_8));
        f.files.put("corrupt.tsv", corruptTable.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder all = new StringBuilder();
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            String digest = hex(sha(f.files.get(name)));
            f.manifest.put(name, digest);
            all.append(name).append(':').append(digest).append('\n');
        }
        f.manifest.put("all", hex(sha(all.toString().getBytes(StandardCharsets.UTF_8))));
        StringBuilder manifestTable = new StringBuilder();
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            manifestTable.append(name).append('\t').append(f.manifest.get(name)).append('\n');
        }
        manifestTable.append("all\t").append(f.manifest.get("all")).append('\n');
        f.files.put("sha256.txt", manifestTable.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private interface Count<T> { boolean test(T value); }

    private static <T> int count(List<T> list, Count<T> test) {
        int n = 0;
        for (T value : list) if (test.test(value)) n++;
        return n;
    }

    private static byte[] repeatBytes(char c, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) c);
        return out;
    }

    private static String docUri(String treeId, String docName) {
        return "content://" + SAF_AUTHORITY + "/tree/" + enc(treeId) +
            "/document/" + enc(treeId + "/" + docName);
    }

    private static String digestOfPrefs(List<Pref> rows) {
        StringBuilder out = new StringBuilder();
        for (Pref row : rows) out.append(row.id).append('|').append(row.expected).append('\n');
        return hex(sha(out.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String digestOfLocations(List<Location> rows) {
        StringBuilder out = new StringBuilder();
        for (Location row : rows) {
            out.append(row.id).append('|').append(row.play).append('|')
                .append(row.delete).append('\n');
        }
        return hex(sha(out.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String digestOfCorrupt(List<Corrupt> rows) {
        StringBuilder out = new StringBuilder();
        for (Corrupt row : rows) out.append(row.id).append('|').append(row.expected).append('\n');
        return hex(sha(out.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String mirrorDigest() {
        String material = VERSION + "|" + SEED + "|" + marker("mirror-model");
        return hex(sha(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeFixture(Path dir, Fixture fixture) throws Exception {
        Files.createDirectories(dir);
        for (String name : FILES) Files.write(dir.resolve(name), fixture.files.get(name));
    }

    // ---------------------------------------------------------------- parsing

    private static final class Parsed {
        final Map<String, byte[]> bytes = new LinkedHashMap<>();
        final Map<String, String> text = new LinkedHashMap<>();
        final Map<String, String> dimensions = new LinkedHashMap<>();
        final Map<String, String> manifest = new LinkedHashMap<>();
        final List<Pref> prefs = new ArrayList<>();
        final List<Volume> volumes = new ArrayList<>();
        final List<Grant> grants = new ArrayList<>();
        final List<Location> locations = new ArrayList<>();
        final List<Corrupt> corrupt = new ArrayList<>();
        Map<String, Volume> volumeMap;
        Map<String, Grant> grantMap;
    }

    private static String decodeStrict(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String[] lines(String text) {
        if (text.isEmpty()) return new String[0];
        String trimmed = text.substring(0, text.length() - 1);
        return trimmed.split("\n", -1);
    }

    private static String[][] table(Parsed parsed, String name, String header, int fields)
            throws Fail {
        String content = parsed.text.get(name);
        String[] rows = lines(content);
        int firstRow;
        if (header.isEmpty()) {
            firstRow = 0;
        } else {
            if (!content.startsWith(header)) throw new Fail("HEADER_STRICT");
            if (rows.length < 1 || !rows[0].equals(header.substring(0, header.length() - 1))) {
                throw new Fail("HEADER_STRICT");
            }
            firstRow = 1;
        }
        String[][] out = new String[rows.length - firstRow][fields];
        for (int i = firstRow; i < rows.length; i++) {
            String[] parts = rows[i].split("\t", -1);
            if (parts.length != fields) throw new Fail("FIELD_STRICT");
            for (String part : parts) if (controlChars(part)) {
                throw new Fail("FIELD_STRICT");
            }
            out[i - firstRow] = parts;
        }
        return out;
    }

    private static final class Fail extends Exception {
        final String code;
        Fail(String code) { this.code = code; }
    }

    private static Parsed parse(Path dir) throws Exception {
        Parsed p = new Parsed();
        for (String name : FILES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) throw new Fail("FILE_PRESENT");
            byte[] raw = Files.readAllBytes(file);
            if (raw.length > 1_500_000) throw new Fail("ROW_BOUNDS");
            p.bytes.put(name, raw);
            p.text.put(name, decodeStrict(raw));
        }
        for (String name : FILES) {
            String content = p.text.get(name);
            if (!content.isEmpty() && !content.endsWith("\n")) throw new Fail("TRUNCATED_INPUT");
            for (String line : lines(content)) {
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                    throw new Fail("ROW_BOUNDS");
                }
            }
        }
        String[][] dimRows = table(p, "dimensions.tsv", "", 1);
        for (String[] row : dimRows) {
            int equals = row[0].indexOf('=');
            if (equals <= 0) throw new Fail("FIELD_STRICT");
            p.dimensions.put(row[0].substring(0, equals), row[0].substring(equals + 1));
        }
        String[][] manifestRows = table(p, "sha256.txt", "", 2);
        for (String[] row : manifestRows) {
            if (row[0].isEmpty() || row[1].isEmpty()) throw new Fail("FIELD_STRICT");
            p.manifest.put(row[0], row[1]);
        }
        for (String[] row : table(p, "prefs.tsv", PREF_HEADER, 3)) {
            p.prefs.add(new Pref(row[0], row[1], row[2]));
        }
        for (String[] row : table(p, "volumes.tsv", VOLUME_HEADER, 5)) {
            p.volumes.add(new Volume(row[0], Long.parseLong(row[1]), row[2],
                Long.parseLong(row[3]), row[4]));
        }
        for (String[] row : table(p, "grants.tsv", GRANT_HEADER, 5)) {
            p.grants.add(new Grant(row[0], row[1], row[2],
                row[3].equals("1"), row[4].equals("1")));
        }
        for (String[] row : table(p, "locations.tsv", LOCATION_HEADER, 7)) {
            p.locations.add(new Location(row[0], row[1], row[2], row[3], row[4], row[5], row[6]));
        }
        for (String[] row : table(p, "corrupt.tsv", CORRUPT_HEADER, 3)) {
            p.corrupt.add(new Corrupt(row[0], row[1], row[2]));
        }
        p.volumeMap = new LinkedHashMap<>();
        for (Volume volume : p.volumes) p.volumeMap.put(volume.id, volume);
        p.grantMap = new LinkedHashMap<>();
        for (Grant grant : p.grants) p.grantMap.put(grant.id, grant);
        return p;
    }

    // ---------------------------------------------------------------- gate

    private static final class Book {
        final List<String> failures = new ArrayList<>();
        final StringBuilder report = new StringBuilder();
        int passed;
        void check(boolean ok, String code, String value) {
            if (ok) {
                passed++;
                report.append("CHECK\tPASS\t").append(code).append('\t').append(value).append('\n');
            } else {
                failures.add(code);
                report.append("CHECK\tFAIL\t").append(code).append('\t').append(value).append('\n');
            }
        }
    }

    private static int verify(Path fixtureDir, Path scratchDir, boolean skipIdentity,
                              boolean skipSha) throws Exception {
        long heapStart = usedHeap();
        Book book = new Book();
        Parsed p;
        Fixture regenerated;
        try {
            p = parse(fixtureDir);
            regenerated = build();
        } catch (Fail failure) {
            book.check(false, failure.code, "0");
            finish(book);
            return 1;
        }

        boolean allPresent = true;
        for (String name : FILES) {
            if (!p.bytes.containsKey(name) || !regenerated.files.containsKey(name)) allPresent = false;
        }
        book.check(allPresent, "FILE_PRESENT", Integer.toString(FILES.length));

        boolean identity = true;
        for (String name : FILES) {
            if (!Arrays.equals(p.bytes.get(name), regenerated.files.get(name))) identity = false;
        }
        byte[] secondPass = serialise(regenerated);
        boolean stable = Arrays.equals(secondPass, serialise(build()));
        book.check(stable, "NO_TIME_DEPENDENCE", "2");
        book.check(skipIdentity || identity, "BYTE_IDENTITY", identity ? "1" : "0");

        boolean shaOk = true;
        StringBuilder all = new StringBuilder();
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            String digest = hex(sha(p.bytes.get(name)));
            all.append(name).append(':').append(digest).append('\n');
            String pinned = p.manifest.get(name);
            if (pinned == null || !pinned.equals(digest)) shaOk = false;
        }
        String allDigest = hex(sha(all.toString().getBytes(StandardCharsets.UTF_8)));
        if (!allDigest.equals(p.manifest.get("all"))) shaOk = false;
        book.check(skipSha || shaOk, "SHA_PIN", allDigest.substring(0, 12));

        boolean utf8Ok = true;
        for (byte[] bytes : p.bytes.values()) {
            try { decodeStrict(bytes); } catch (CharacterCodingException e) { utf8Ok = false; }
        }
        book.check(utf8Ok, "UTF8_STRICT", Integer.toString(p.bytes.size()));

        boolean truncatedOk = true;
        for (String content : p.text.values()) {
            if (!content.isEmpty() && !content.endsWith("\n")) truncatedOk = false;
        }
        book.check(truncatedOk, "TRUNCATED_INPUT", "0");

        boolean headerOk = p.text.get("prefs.tsv").startsWith(PREF_HEADER) &&
            p.text.get("volumes.tsv").startsWith(VOLUME_HEADER) &&
            p.text.get("grants.tsv").startsWith(GRANT_HEADER) &&
            p.text.get("locations.tsv").startsWith(LOCATION_HEADER) &&
            p.text.get("corrupt.tsv").startsWith(CORRUPT_HEADER);
        book.check(headerOk, "HEADER_STRICT", "5");

        boolean rowBounds = p.prefs.size() == PREF_ROWS && p.volumes.size() == VOLUME_ROWS &&
            p.grants.size() == GRANT_ROWS && p.locations.size() == LOCATION_ROWS &&
            p.corrupt.size() == CORRUPT_ROWS;
        book.check(rowBounds, "ROW_BOUNDS", p.prefs.size() + "/" + p.locations.size());

        boolean fieldStrict = true;
        for (String name : TABLES) {
            int expected = name.equals("prefs.tsv") ? 3 : name.equals("volumes.tsv") ? 5 :
                name.equals("grants.tsv") ? 5 : name.equals("locations.tsv") ? 7 :
                name.equals("corrupt.tsv") ? 3 : 1;
            for (String line : lines(p.text.get(name))) {
                if (name.equals("dimensions.tsv")) {
                    if (line.indexOf('=') <= 0) fieldStrict = false;
                } else if (line.split("\t", -1).length != expected) {
                    fieldStrict = false;
                }
            }
        }
        book.check(fieldStrict, "FIELD_STRICT", Integer.toString(TABLES.length));

        boolean corruptHex = true;
        for (Corrupt row : p.corrupt) {
            if (!HEX.matcher(row.payloadHex).matches() || (row.payloadHex.length() & 1) != 0) {
                corruptHex = false;
            }
        }
        book.check(corruptHex, "CORRUPT_HEX", Integer.toString(p.corrupt.size()));

        boolean prefEnum = true;
        for (Pref row : p.prefs) if (!PREF_CODES.contains(row.expected)) prefEnum = false;
        book.check(prefEnum, "PREF_ENUM", Integer.toString(PREF_CODES.size()));

        boolean prefModel = true;
        for (Pref row : p.prefs) {
            if (!classifyPref(row.raw).equals(row.expected)) prefModel = false;
        }
        book.check(prefModel, "PREF_MODEL", Integer.toString(p.prefs.size()));

        int accepted = count(p.prefs, row -> row.expected.equals(ACCEPT));
        String pinnedAccepted = p.dimensions.get("prefs.accepted");
        boolean prefBounds = accepted > 0 && accepted < PREF_ROWS &&
            pinnedAccepted != null && Integer.parseInt(pinnedAccepted) == accepted;
        book.check(prefBounds, "PREF_ACCEPT_BOUND", Integer.toString(accepted));

        boolean volumeBounds = count(p.volumes, v -> v.usableBytes < 0L) == 1;
        for (Volume volume : p.volumes) {
            boolean valid = volume.mime.equals(DIR_MIME) &&
                (volume.flags & DIR_SUPPORTS_CREATE) != 0L && volume.usableBytes >= 0L;
            String[] result = volumeGate(volume.id + ":doc", p.volumeMap);
            boolean acceptedVolume = result == null;
            if (valid != acceptedVolume) volumeBounds = false;
        }
        book.check(volumeBounds, "VOLUME_BOUNDS", Integer.toString(p.volumes.size()));

        boolean grantBounds = count(p.grants, g -> !g.write) == 2 &&
            p.grantMap.containsKey("g-root") && p.grantMap.get("g-root").read &&
            p.grantMap.get("g-root").write;
        book.check(grantBounds, "GRANT_BOUNDS", Integer.toString(p.grants.size()));

        boolean treeAuthority = true;
        for (Pref row : p.prefs) {
            if (row.raw.startsWith("content://") &&
                !row.raw.startsWith("content://" + SAF_AUTHORITY + "/") &&
                !row.expected.equals(BAD_AUTHORITY)) {
                treeAuthority = false;
            }
        }
        book.check(treeAuthority, "TREE_AUTHORITY",
            Integer.toString(count(p.prefs, row -> row.expected.equals(BAD_AUTHORITY))));

        boolean treeTruncation = count(p.prefs, row -> row.expected.equals(TRUNCATED)) > 0;
        book.check(treeTruncation, "TREE_TRUNCATION",
            Integer.toString(count(p.prefs, row -> row.expected.equals(TRUNCATED))));

        boolean locationModel = true;
        for (Location row : p.locations) {
            String[] actual = classifyLocation(row.raw, row.grant, row.docState,
                p.grantMap, p.volumeMap);
            if (!actual[0].equals(row.play) || !actual[1].equals(row.delete)) locationModel = false;
            if (!PLAYABLE.equals(row.play) && !NONE.equals(row.play)) locationModel = false;
            if (!DELETE_CODES.contains(row.delete)) locationModel = false;
        }
        book.check(locationModel, "LOCATION_MODEL", Integer.toString(p.locations.size()));

        boolean containment = true;
        for (Location row : p.locations) {
            if (p.volumes.size() > 1 && row.grant.equals("g-sub") &&
                row.volumeId.equals(p.volumes.get(1).id) &&
                row.raw.contains("evil") && !row.delete.equals(INVALID)) {
                containment = false;
            }
        }
        book.check(containment, "CONTAINMENT", Integer.toString(
            count(p.locations, row -> row.delete.equals(INVALID))));

        boolean internalRoot = true;
        for (Location row : p.locations) {
            if (row.raw.contains("..") && !row.delete.equals(INVALID)) internalRoot = false;
            if (row.raw.contains(ROOT_A + "-other") && !row.delete.equals(INVALID)) {
                internalRoot = false;
            }
        }
        book.check(internalRoot, "INTERNAL_ROOT", "2");

        boolean persistedAccess = true;
        for (Location row : p.locations) {
            if ((row.grant.equals("g-noread") || row.grant.equals("g-readonly")) &&
                row.delete.equals(DELETED)) {
                persistedAccess = false;
            }
            if (row.grant.equals("g-noread") && row.play.equals(PLAYABLE)) persistedAccess = false;
            if (row.grant.equals("g-readonly") && !row.play.equals(PLAYABLE)) persistedAccess = false;
        }
        book.check(persistedAccess, "PERSISTED_ACCESS", "2");

        boolean docStateOk = true;
        for (Location row : p.locations) {
            if (row.docState.equals(MISSING) && !row.delete.equals(MISSING)) docStateOk = false;
            if (row.docState.equals(UNAVAILABLE) && !row.delete.equals(UNAVAILABLE)) {
                docStateOk = false;
            }
        }
        book.check(docStateOk, "DOC_STATE", Integer.toString(
            count(p.locations, row -> row.delete.equals(MISSING)) +
            count(p.locations, row -> row.delete.equals(UNAVAILABLE))));

        boolean corruptFailClosed = true;
        for (Corrupt row : p.corrupt) {
            boolean rejected;
            try {
                String value = decodeStrict(unhex(row.payloadHex));
                rejected = !classifyPref(value).equals(ACCEPT) &&
                    classifyLocation(value, "g-root", "EXISTS", p.grantMap, p.volumeMap)[0]
                        .equals(NONE);
            } catch (CharacterCodingException | IllegalArgumentException e) {
                rejected = true;
            }
            if (!rejected || !row.expected.equals("CORRUPT_FAIL_CLOSED")) corruptFailClosed = false;
        }
        book.check(corruptFailClosed, "CORRUPT_FAIL_CLOSED", Integer.toString(p.corrupt.size()));

        boolean noEffect = true;
        for (Corrupt row : p.corrupt) {
            try {
                String value = decodeStrict(unhex(row.payloadHex));
                if (classifyLocation(value, "g-root", "EXISTS", p.grantMap, p.volumeMap)[1]
                        .equals(DELETED)) {
                    noEffect = false;
                }
            } catch (CharacterCodingException | IllegalArgumentException ignored) {
                // Expected fail-closed decode path.
            }
        }
        book.check(noEffect, "FAIL_CLOSED_NO_EFFECT", Integer.toString(p.corrupt.size()));

        Map<String, String> derived = new LinkedHashMap<>();
        derived.put("fixture.version", VERSION);
        derived.put("seed.tag", marker("seed-tag"));
        derived.put("prefs.rows", Integer.toString(PREF_ROWS));
        derived.put("prefs.accepted", Integer.toString(accepted));
        derived.put("prefs.rejected", Integer.toString(PREF_ROWS - accepted));
        derived.put("volumes.rows", Integer.toString(p.volumes.size()));
        derived.put("volumes.negative",
            Integer.toString(count(p.volumes, v -> v.usableBytes < 0L)));
        derived.put("grants.rows", Integer.toString(p.grants.size()));
        derived.put("grants.readonly", Integer.toString(count(p.grants, g -> !g.write)));
        derived.put("locations.rows", Integer.toString(p.locations.size()));
        derived.put("locations.playable",
            Integer.toString(count(p.locations, l -> l.play.equals(PLAYABLE))));
        derived.put("locations.deleted",
            Integer.toString(count(p.locations, l -> l.delete.equals(DELETED))));
        derived.put("locations.invalid",
            Integer.toString(count(p.locations, l -> l.delete.equals(INVALID))));
        derived.put("locations.missing",
            Integer.toString(count(p.locations, l -> l.delete.equals(MISSING))));
        derived.put("locations.unavailable",
            Integer.toString(count(p.locations, l -> l.delete.equals(UNAVAILABLE))));
        derived.put("corrupt.rows", Integer.toString(p.corrupt.size()));
        derived.put("corrupt.rejected", Integer.toString(p.corrupt.size()));
        derived.put("model.pref.digest", digestOfPrefs(p.prefs));
        derived.put("model.location.digest", digestOfLocations(p.locations));
        derived.put("model.corrupt.digest", digestOfCorrupt(p.corrupt));
        derived.put("model.mirror.digest", mirrorDigest());
        derived.put("limits.heap.bytes", Long.toString(MAX_HEAP_BYTES));
        derived.put("limits.threads", Integer.toString(MAX_THREADS));

        long heapUsed = usedHeap();
        long heapDelta = heapUsed - heapStart;
        boolean heapOk = heapUsed <= MAX_HEAP_BYTES && heapDelta <= MAX_HEAP_DELTA;
        book.check(heapOk, "HEAP_BOUND", heapUsed + "/" + heapDelta);

        int threads = Thread.activeCount();
        boolean threadOk = threads <= MAX_THREADS;
        book.check(threadOk, "THREAD_BOUND", Integer.toString(threads));

        boolean fixturePrivacy = true;
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            if (FIXTURE_LEAK.matcher(p.text.get(name)).find()) fixturePrivacy = false;
        }
        book.check(fixturePrivacy, "PRIVACY_FIXTURE", Integer.toString(FILES.length - 1));

        String rendered = book.report.toString();
        boolean reportPrivacy = !REPORT_LEAK.matcher(rendered).find();
        book.check(reportPrivacy, "PRIVACY_REPORT", Integer.toString(rendered.length()));

        String renderedAgain = book.report.toString();
        boolean reportBounds = renderedAgain.getBytes(StandardCharsets.UTF_8).length
            <= MAX_REPORT_BYTES;
        book.check(reportBounds, "REPORT_BOUNDS", Integer.toString(
            renderedAgain.getBytes(StandardCharsets.UTF_8).length));

        boolean dimensionsOk = p.dimensions.equals(derived);
        book.check(dimensionsOk, "DIMENSIONS", Integer.toString(derived.size()));

        finish(book);
        return book.failures.isEmpty() ? 0 : 1;
    }

    private static byte[] serialise(Fixture fixture) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String name : FILES) {
            byte[] value = fixture.files.get(name);
            out.write(name.getBytes(StandardCharsets.UTF_8), 0, name.length());
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void finish(Book book) {
        for (String line : lines(book.report.toString())) System.out.println(line);
        System.out.println("FIRST\t" + (book.failures.isEmpty() ? "NONE" : book.failures.get(0)));
        System.out.println("SUMMARY\tpositive=" + book.passed + "\tfailed=" + book.failures.size());
    }

    // ---------------------------------------------------------------- mutants

    private static int mutate(String fixtureDir, String outDir, String kind) throws Exception {
        Fixture f = build();
        Map<String, byte[]> changed = new LinkedHashMap<>(f.files);
        if (kind.equals("byte-flip")) {
            byte[] prefs = changed.get("prefs.tsv").clone();
            prefs[prefs.length - 20] ^= 0x01;
            changed.put("prefs.tsv", prefs);
        } else if (kind.equals("bad-header")) {
            changed.put("prefs.tsv", "bad\theader\n".getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("row-count")) {
            changed.put("volumes.tsv", (VOLUME_HEADER + marker("only-row") +
                "\t100\t" + DIR_MIME + "\t" + DIR_SUPPORTS_CREATE + "\tEXISTS\n")
                .getBytes(StandardCharsets.UTF_8));
            refreshManifest(changed, f);
        } else if (kind.equals("truncate")) {
            byte[] corrupt = changed.get("corrupt.tsv");
            changed.put("corrupt.tsv", Arrays.copyOf(corrupt, corrupt.length - 9));
        } else if (kind.equals("sha-drift")) {
            changed.put("sha256.txt", ("all\t" + marker("wrong-sha") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        } else if (kind.equals("dimension-drift")) {
            String text = new String(changed.get("dimensions.tsv"), StandardCharsets.UTF_8)
                .replace("seed.tag=" + f.dimensions.get("seed.tag"), "seed.tag=drifted");
            changed.put("dimensions.tsv", text.getBytes(StandardCharsets.UTF_8));
            refreshManifest(changed, f);
        } else if (kind.equals("leak")) {
            changed.put("contract.txt", (new String(changed.get("contract.txt"),
                StandardCharsets.UTF_8) + "token=" + marker("leak-marker") + "\n")
                .getBytes(StandardCharsets.UTF_8));
            refreshManifest(changed, f);
        } else if (kind.equals("pref-model")) {
            String text = new String(changed.get("prefs.tsv"), StandardCharsets.UTF_8);
            String good = "content://" + SAF_AUTHORITY + "/tree/";
            text = text.replaceFirst(java.util.regex.Pattern.quote(good),
                "content://scratch.invalid/tree/");
            changed.put("prefs.tsv", text.getBytes(StandardCharsets.UTF_8));
            refreshManifest(changed, f);
        } else if (kind.equals("containment")) {
            String text = new String(changed.get("locations.tsv"), StandardCharsets.UTF_8);
            text = text.replaceFirst("/document/", "/documents/");
            changed.put("locations.tsv", text.getBytes(StandardCharsets.UTF_8));
            refreshManifest(changed, f);
        } else if (kind.equals("fail-open")) {
            String text = new String(changed.get("corrupt.tsv"), StandardCharsets.UTF_8);
            text = text.replaceFirst("CORRUPT_FAIL_CLOSED", "ACCEPT");
            changed.put("corrupt.tsv", text.getBytes(StandardCharsets.UTF_8));
            refreshManifest(changed, f);
        } else {
            System.out.println("MUTATE\tUNKNOWN\t" + kind);
            return 2;
        }
        Path target = Paths.get(outDir);
        Files.createDirectories(target);
        for (String name : FILES) Files.write(target.resolve(name), changed.get(name));
        System.out.println("MUTATE\t" + kind + "\t1");
        return 0;
    }

    private static void refreshManifest(Map<String, byte[]> changed, Fixture f) {
        StringBuilder all = new StringBuilder();
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            all.append(name).append(':').append(hex(sha(changed.get(name)))).append('\n');
        }
        String allDigest = hex(sha(all.toString().getBytes(StandardCharsets.UTF_8)));
        StringBuilder table = new StringBuilder();
        for (String name : FILES) {
            if (name.equals("sha256.txt")) continue;
            table.append(name).append('\t').append(hex(sha(changed.get(name)))).append('\n');
        }
        table.append("all\t").append(allDigest).append('\n');
        changed.put("sha256.txt", table.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- crypto

    private static byte[] sha(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            out[i * 2] = digits[(value[i] >> 4) & 0xf];
            out[i * 2 + 1] = digits[value[i] & 0xf];
        }
        return new String(out);
    }

    private static byte[] unhex(String value) {
        if ((value.length() & 1) != 0 || !HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("bad hex");
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
