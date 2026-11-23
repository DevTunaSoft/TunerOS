import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * QA-05 deterministic fixture verifier. Reads the generated scratch fixture and asserts the
 * guide-search / hide-no-EPG filter matrix, view bindings, focus and navigation expectations.
 * No dependencies, no network, no device:
 *   java scripts/qa05/Qa05FixtureVerifier.java <fixture> [--embedded <kotlin-assertions-file>]
 */
public final class Qa05FixtureVerifier {
    private record Channel(String id, String name, String guideId, String streamRef) {}
    private record Programme(String channelId, String title, long start, long end) {}
    private record Entry(long start, long end, String title) {
        boolean isGap() { return title == null; }
    }
    private record Expect(String id, String query, boolean hide, List<String> expected) {}
    private record NoGuide(String id, String query, List<String> expected) {}
    private record View(String id, String query, boolean hide, List<String> rows) {}
    private record Bound(String id, String query, boolean hide, int cells, int timelines) {}
    private record Focus(String id, long from, long to, String channel, int index, String title, boolean cellBound) {}
    private record NavRight(String id, long from, long to, String channel, int start, int right, int back) {}
    private record NavEdge(String id, long from, long to, String channel, int start, int left, int shiftDirection) {}

    private static final class Fixture {
        int version = -1;
        long windowFrom;
        long windowTo;
        final List<Channel> channels = new ArrayList<>();
        final List<Programme> programmes = new ArrayList<>();
        final List<Expect> expects = new ArrayList<>();
        final List<NoGuide> noGuides = new ArrayList<>();
        final List<View> views = new ArrayList<>();
        final List<Bound> bounds = new ArrayList<>();
        final List<Focus> focuses = new ArrayList<>();
        final List<NavRight> navRights = new ArrayList<>();
        final List<NavEdge> navEdges = new ArrayList<>();
    }

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 3) {
            System.err.println("usage: java Qa05FixtureVerifier.java <fixture> [--embedded <kotlin-assertions-file>]");
            System.exit(2);
        }
        Path fixturePath = Paths.get(args[0]);
        String embeddedPath = null;
        if (args.length == 3) {
            if (!"--embedded".equals(args[1])) {
                System.err.println("unknown option: " + args[1]);
                System.exit(2);
            }
            embeddedPath = args[2];
        }
        String text = Files.readString(fixturePath, StandardCharsets.UTF_8).replace("\r", "");
        Fixture fixture = parse(text);

        checkStructure(fixture);
        checkFilter(fixture);
        checkViewBindings(fixture);
        checkFocus(fixture);
        checkNavigation(fixture);
        if (embeddedPath != null) checkEmbedded(fixturePath, embeddedPath);

        System.out.println("QA05 JVM VERIFY " + (failures == 0 ? "PASS" : "FAIL") +
            ": " + (checks - failures) + "/" + checks + " checks; device not required");
        if (failures != 0) System.exit(1);
    }

    private static void check(String id, boolean passed, String detail) {
        checks++;
        if (!passed) failures++;
        System.out.println((passed ? "PASS" : "FAIL") + "\t" + id + "\t" + detail);
    }

    private static Fixture parse(String text) {
        Fixture result = new Fixture();
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", -1);
            if (line.startsWith("VERSION ") || line.startsWith("WINDOW ")) {
                String[] words = line.split("\\s+");
                if (words[0].equals("VERSION")) result.version = Integer.parseInt(words[1]);
                else {
                    result.windowFrom = Long.parseLong(words[1]);
                    result.windowTo = Long.parseLong(words[2]);
                }
                continue;
            }
            // The record kind is separated from its first field by a space, later fields by '|'.
            int space = line.indexOf(' ');
            String normalized = space < 0 ? line :
                line.substring(0, space) + "|" + line.substring(space + 1);
            p = normalized.split("\\|", -1);
            switch (p[0]) {
                case "CHANNEL":
                    result.channels.add(new Channel(p[1], p[2], p[3].isEmpty() ? null : p[3], p[4]));
                    break;
                case "PROGRAMME":
                    result.programmes.add(new Programme(p[1], p[2], Long.parseLong(p[3]), Long.parseLong(p[4])));
                    break;
                case "EXPECT":
                    result.expects.add(new Expect(p[1], unempty(p[2]), Boolean.parseBoolean(p[3]), ids(p[4])));
                    break;
                case "NOGUIDE":
                    result.noGuides.add(new NoGuide(p[1], unempty(p[2]), ids(p[3])));
                    break;
                case "VIEW":
                    result.views.add(new View(p[1], unempty(p[2]), Boolean.parseBoolean(p[3]), ids(p[4])));
                    break;
                case "BOUND":
                    result.bounds.add(new Bound(p[1], unempty(p[2]), Boolean.parseBoolean(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5])));
                    break;
                case "FOCUS":
                    result.focuses.add(new Focus(p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), p[4],
                        Integer.parseInt(p[5]), unempty(p[6]), Boolean.parseBoolean(p[7])));
                    break;
                case "NAVRIGHT":
                    result.navRights.add(new NavRight(p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), p[4],
                        Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7])));
                    break;
                case "NAVEDGE":
                    result.navEdges.add(new NavEdge(p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), p[4],
                        Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7])));
                    break;
                default:
                    throw new IllegalArgumentException("unknown fixture record: " + p[0]);
            }
        }
        return result;
    }

    private static String unempty(String token) { return "(empty)".equals(token) ? "" : token; }

    private static List<String> ids(String token) {
        if ("(none)".equals(token)) return new ArrayList<>();
        return Arrays.asList(token.split(","));
    }

    private static void checkStructure(Fixture f) {
        check("fixture-version", f.version == 1, "version=" + f.version);
        check("fixture-window", f.windowTo > f.windowFrom,
            "from=" + f.windowFrom + " to=" + f.windowTo);
        Set<String> ids = new HashSet<>();
        boolean unique = true;
        for (Channel c : f.channels) unique &= ids.add(c.id());
        check("fixture-unique-channel-ids", unique && f.channels.size() == 5,
            "channels=" + f.channels.size());
        Set<String> guideIds = new HashSet<>();
        for (Channel c : f.channels) if (c.guideId() != null) guideIds.add(c.guideId());
        boolean known = true;
        for (Programme p : f.programmes) known &= guideIds.contains(p.channelId());
        check("fixture-known-guide-ids", known && f.programmes.size() == 5,
            "programmes=" + f.programmes.size());
    }

    /** Mirrors ChannelVisibility.filter: case-insensitive trimmed name search and user-controlled hide-no-EPG. */
    private static List<String> visible(Fixture f, boolean guidePresent, String query, boolean hide) {
        String search = query.trim();
        List<String> out = new ArrayList<>();
        if (search.isEmpty() && !hide) {
            for (Channel c : f.channels) out.add(c.id());
            return out;
        }
        if (hide && !guidePresent) return out;
        String needle = search.toLowerCase(Locale.ROOT);
        for (Channel c : f.channels) {
            boolean nameMatches = search.isEmpty() ||
                c.name().toLowerCase(Locale.ROOT).contains(needle);
            boolean hasSchedule = false;
            if (c.guideId() != null) {
                for (Programme p : f.programmes) {
                    if (p.channelId().equals(c.guideId())) { hasSchedule = true; break; }
                }
            }
            if (nameMatches && (!hide || hasSchedule)) out.add(c.id());
        }
        return out;
    }

    private static void checkFilter(Fixture f) {
        for (Expect e : f.expects) {
            List<String> actual = visible(f, true, e.query(), e.hide());
            check("filter-" + e.id(), actual.equals(e.expected()),
                "query=[" + e.query() + "] hide=" + e.hide() + " expected=" + e.expected() + " actual=" + actual);
        }
        for (NoGuide n : f.noGuides) {
            List<String> actual = visible(f, false, n.query(), true);
            check("noguide-" + n.id(), actual.equals(n.expected()),
                "query=[" + n.query() + "] no guide snapshot, hide=true, actual=" + actual);
        }
    }

    private static void checkViewBindings(Fixture f) {
        for (View v : f.views) {
            List<String> actual = visible(f, true, v.query(), v.hide());
            check("view-" + v.id(), actual.equals(v.rows()),
                "rows=" + actual + " expected=" + v.rows() +
                "; each bound row has one channel cell and one timeline");
        }
        for (Bound b : f.bounds) {
            int rows = visible(f, true, b.query(), b.hide()).size();
            check("bound-" + b.id(), rows == b.cells() && rows == b.timelines(),
                "rows=" + rows + " cells=" + b.cells() + " timelines=" + b.timelines());
        }
    }

    /** Mirrors TimelineLayout.rowFromSorted: a contiguous row of clipped programmes and gaps. */
    private static List<Entry> entriesFor(Fixture f, String guideId, long from, long to) {
        List<Programme> sorted = new ArrayList<>();
        for (Programme p : f.programmes) {
            if (p.channelId().equals(guideId) && p.end() > from && p.start() < to) sorted.add(p);
        }
        sorted.sort(Comparator.comparingLong(Programme::start).thenComparingLong(Programme::end));
        List<Entry> out = new ArrayList<>();
        long cursor = from;
        for (Programme p : sorted) {
            if (p.start() >= to) break;
            long start = Math.max(Math.max(p.start(), from), cursor);
            long end = Math.min(p.end(), to);
            if (end <= start) continue;
            if (start > cursor) out.add(new Entry(cursor, start, null));
            out.add(new Entry(start, end, p.title()));
            cursor = end;
            if (cursor >= to) break;
        }
        if (cursor < to) out.add(new Entry(cursor, to, null));
        return out;
    }

    /** Mirrors TimelineFocusPolicy.preferredIndex with no preferred programme. */
    private static int preferredIndex(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) if (!entries.get(i).isGap()) return i;
        return -1;
    }

    /** Mirrors TimelineFocusPolicy.nextIndex: gaps are never selectable. */
    private static int nextIndex(List<Entry> entries, int current, int direction) {
        int index = current + direction;
        while (index >= 0 && index < entries.size()) {
            if (!entries.get(index).isGap()) return index;
            index += direction;
        }
        return -1;
    }

    private static void checkFocus(Fixture f) {
        List<String> hiddenRows = visible(f, true, "", true);
        for (Focus focus : f.focuses) {
            Channel channel = null;
            for (Channel c : f.channels) if (c.id().equals(focus.channel())) channel = c;
            if (channel == null) {
                check("focus-" + focus.id(), false, "unknown channel " + focus.channel());
                continue;
            }
            boolean bound = hiddenRows.contains(focus.channel());
            List<Entry> entries = channel.guideId() == null
                ? new ArrayList<>() : entriesFor(f, channel.guideId(), focus.from(), focus.to());
            int preferred = preferredIndex(entries);
            String title = preferred < 0 ? "none" : entries.get(preferred).title();
            boolean contiguous = entries.isEmpty() ||
                (entries.get(0).start() == focus.from() &&
                 entries.get(entries.size() - 1).end() == focus.to() && covers(focus, entries));
            check("focus-" + focus.id(),
                bound == focus.cellBound() && preferred == focus.index() &&
                    title.equals(focus.title()) && contiguous,
                "cellBound=" + bound + " expected=" + focus.cellBound() +
                " preferredIndex=" + preferred + " expected=" + focus.index() +
                " label=" + title + " contiguous=" + contiguous);
        }
    }

    private static boolean covers(Focus focus, List<Entry> entries) {
        long cursor = focus.from();
        for (Entry entry : entries) {
            if (entry.start() != cursor || entry.end() <= entry.start()) return false;
            cursor = entry.end();
        }
        return cursor == focus.to();
    }

    private static void checkNavigation(Fixture f) {
        for (NavRight nav : f.navRights) {
            List<Entry> entries = navigationEntries(f, nav.id(), nav.channel(), nav.from(), nav.to());
            if (entries == null) continue;
            int right = nextIndex(entries, nav.start(), 1);
            int back = nextIndex(entries, right, -1);
            check("nav-" + nav.id(), right == nav.right() && back == nav.back(),
                "from=" + nav.start() + " RIGHT=" + right + " LEFT-from-right=" + back +
                "; no window shift while moving between programmes");
        }
        for (NavEdge nav : f.navEdges) {
            List<Entry> entries = navigationEntries(f, nav.id(), nav.channel(), nav.from(), nav.to());
            if (entries == null) continue;
            int left = nextIndex(entries, nav.start(), -1);
            int shift = left < 0 ? -1 : 0;
            check("nav-" + nav.id(), left == nav.left() && shift == nav.shiftDirection(),
                "LEFT from first programme=" + left + " expected=" + nav.left() +
                " window shift=" + shift + " expected=" + nav.shiftDirection());
        }
    }

    private static List<Entry> navigationEntries(Fixture f, String id, String channelId, long from, long to) {
        for (Channel c : f.channels) {
            if (c.id().equals(channelId) && c.guideId() != null) return entriesFor(f, c.guideId(), from, to);
        }
        check("nav-" + id, false, "unknown channel or no guide id: " + channelId);
        return null;
    }

    private static void checkEmbedded(Path fixturePath, String kotlinPath) throws IOException {
        String source = Files.readString(Paths.get(kotlinPath), StandardCharsets.UTF_8).replace("\r", "");
        String[] lines = source.split("\n", -1);
        StringBuilder embedded = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (line.trim().equals("// QA05-FIXTURE-END")) break;
            if (inside) embedded.append(line).append('\n');
            if (line.trim().equals("// QA05-FIXTURE-BEGIN")) inside = true;
        }
        String fixture = Files.readString(fixturePath, StandardCharsets.UTF_8).replace("\r", "");
        check("embedded-fixture-matches", inside && embedded.toString().equals(fixture),
            "instrumentation assertions use the same fixture bytes as the JVM verifier");
    }
}
