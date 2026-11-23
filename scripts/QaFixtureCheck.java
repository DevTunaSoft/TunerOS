import com.devtunasoft.tuneros.core.Channel;
import com.devtunasoft.tuneros.core.ChannelVisibility;
import com.devtunasoft.tuneros.core.GuideProgram;
import com.devtunasoft.tuneros.core.GuideParser;
import com.devtunasoft.tuneros.core.GuideSnapshot;
import com.devtunasoft.tuneros.core.PlaylistImport;
import com.devtunasoft.tuneros.core.PlaylistParser;

import java.io.InputStream;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

/**
 * QA-02 / PERF-01 harness. Imports the checked-in deterministic fixture through the
 * EXISTING core parser entry points only (PlaylistParser, GuideParser,
 * ChannelVisibility, GuideSnapshot). No core source or build file is modified.
 * No network, provider URL, credential or media is used.
 */
public final class QaFixtureCheck {

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.out.println("FAIL " + message);
            throw new AssertionError(message);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] block = new byte[1 << 16];
            int count;
            while ((count = in.read(block)) != -1) digest.update(block, 0, count);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    /** Samples live JVM heap during the whole import; also reports Linux peak RSS when available. */
    private static final class MemoryMonitor implements AutoCloseable {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final Thread sampler;
        private volatile boolean running = true;
        private volatile long peakHeap;

        MemoryMonitor() {
            sampler = new Thread(() -> {
                while (running) {
                    long used = memory.getHeapMemoryUsage().getUsed();
                    if (used > peakHeap) peakHeap = used;
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "qa-heap-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        long peakHeap() {
            return Math.max(peakHeap, memory.getHeapMemoryUsage().getUsed());
        }

        static long peakRssKb() {
            Path status = Paths.get("/proc/self/status");
            if (!Files.exists(status)) return -1;
            try (Reader reader = Files.newBufferedReader(status, StandardCharsets.UTF_8)) {
                java.io.BufferedReader lines = new java.io.BufferedReader(reader);
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("VmHWM:")) {
                        return Long.parseLong(line.substring(6).trim().split("\\s+")[0]);
                    }
                }
            } catch (Exception ignored) {
                return -1;
            }
            return -1;
        }

        @Override
        public void close() {
            running = false;
            sampler.interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.out.println("usage: QaFixtureCheck <playlist.m3u> <guide.xml> <manifest.properties>");
            System.exit(2);
        }
        Path playlist = Paths.get(args[0]);
        Path guide = Paths.get(args[1]);
        Path manifest = Paths.get(args[2]);

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        int expectedChannels = Integer.parseInt(props.getProperty("channels"));
        int expectedPrograms = Integer.parseInt(props.getProperty("programs"));
        int expectedEpgChannels = Integer.parseInt(props.getProperty("epg_channels"));
        long anchor = Long.parseLong(props.getProperty("anchor_epoch"));
        long maxHeap = Long.parseLong(props.getProperty("max_heap_bytes"));
        long maxRssKb = Long.parseLong(props.getProperty("max_rss_kb"));
        String searchName = props.getProperty("search_name");
        String searchGuid = props.getProperty("search_guid");
        String noEpgGuid = props.getProperty("no_epg_guid");
        int windowExpected = Integer.parseInt(props.getProperty("future_expected_search"));
        int otherWindowExpected = Integer.parseInt(props.getProperty("future_expected_other"));

        check(Files.size(playlist) == Long.parseLong(props.getProperty("playlist_bytes")),
                "playlist byte size changed");
        check(Files.size(guide) == Long.parseLong(props.getProperty("guide_bytes")),
                "guide byte size changed");
        check(sha256(playlist).equals(props.getProperty("playlist_sha256")),
                "playlist sha256 changed");
        check(sha256(guide).equals(props.getProperty("guide_sha256")),
                "guide sha256 changed");
        System.out.println("PASS qa-large-epg-fixture-integrity seed=" + props.getProperty("seed")
                + " playlist_bytes=" + props.getProperty("playlist_bytes")
                + " guide_bytes=" + props.getProperty("guide_bytes"));

        try (MemoryMonitor monitor = new MemoryMonitor()) {
            List<Channel> channels;
            try (Reader reader = Files.newBufferedReader(playlist, StandardCharsets.UTF_8)) {
                PlaylistImport imported = PlaylistParser.INSTANCE.parseReport(
                        reader, PlaylistParser.MAX_CHANNELS, "qa-fixture");
                check(imported.getWarnings().isEmpty(), "playlist parser reported warnings");
                channels = imported.getChannels();
            }

            GuideSnapshot snapshot;
            try (InputStream input = Files.newInputStream(guide)) {
                snapshot = GuideParser.INSTANCE.parse(input, GuideParser.MAX_PROGRAMS,
                        GuideParser.MAX_CHANNELS, ZoneOffset.UTC, null, true);
            }
            check(snapshot.getWarnings().isEmpty(), "guide parser reported warnings");

            check(channels.size() == expectedChannels,
                    "channel count " + channels.size() + " != " + expectedChannels);
            check(snapshot.getPrograms().size() == expectedPrograms,
                    "programme count " + snapshot.getPrograms().size() + " != " + expectedPrograms);
            check(snapshot.getChannelNames().size() == expectedChannels,
                    "guide channel count " + snapshot.getChannelNames().size() + " != " + expectedChannels);
            System.out.println("PASS qa-large-epg-counts channels=" + channels.size()
                    + " programs=" + snapshot.getPrograms().size()
                    + " guide_channels=" + snapshot.getChannelNames().size());

            // Index is built on the worker in the app; here it is part of the measured import.
            snapshot.prepareIndex();

            List<Channel> all = ChannelVisibility.INSTANCE.filter(channels, snapshot, "", false);
            List<Channel> withGuide = ChannelVisibility.INSTANCE.filter(channels, snapshot, "", true);
            check(all.size() == expectedChannels, "unfiltered channel count changed");
            check(withGuide.size() == expectedEpgChannels,
                    "hide-no-EPG count " + withGuide.size() + " != " + expectedEpgChannels);
            check(snapshot.hasSchedule(searchGuid), "search channel unexpectedly has no schedule");
            check(!snapshot.hasSchedule(noEpgGuid), "no-EPG channel unexpectedly has a schedule");
            System.out.println("PASS qa-large-epg-hide-no-epg visible=" + withGuide.size()
                    + " hidden=" + (all.size() - withGuide.size()));

            List<Channel> found = ChannelVisibility.INSTANCE.filter(channels, snapshot, searchName, false);
            check(found.size() == 1, "name search returned " + found.size() + " channels, expected 1");
            check(searchName.equals(found.get(0).getName()), "name search returned the wrong channel");
            check(ChannelVisibility.INSTANCE.filter(channels, snapshot, searchName, true).size() == 1,
                    "name search with hide-no-EPG lost the matching channel");
            System.out.println("PASS qa-large-epg-name-search matches=" + found.size());

            List<GuideProgram> window = snapshot.programsBetween(searchGuid, anchor, anchor + 86_400);
            check(window.size() == windowExpected,
                    "future window on " + searchGuid + " returned " + window.size()
                            + ", expected " + windowExpected);
            for (GuideProgram program : window) {
                check(program.getEndEpochSeconds() > anchor, "a finished programme is visible in the future window");
            }
            GuideProgram current = snapshot.now(searchGuid, anchor);
            check(current != null && current.getStartEpochSeconds() <= anchor
                            && current.getEndEpochSeconds() > anchor,
                    "current programme at the frozen anchor is wrong");
            check(snapshot.programsBetween(noEpgGuid, anchor, anchor + 86_400).isEmpty(),
                    "no-EPG channel returned a schedule");
            check(snapshot.now(noEpgGuid, anchor) == null, "no-EPG channel returned a current programme");
            String otherGuid = "qa.ch.2500";
            check(snapshot.programsBetween(otherGuid, anchor, anchor + 86_400).size() == otherWindowExpected,
                    "future window on a short-schedule channel is wrong");
            System.out.println("PASS qa-large-epg-future-only channel=" + searchGuid
                    + " window_programs=" + window.size() + " finished_visible=0");

            long peakHeap = monitor.peakHeap();
            long peakRss = MemoryMonitor.peakRssKb();
            check(peakHeap > 0 && peakHeap <= maxHeap,
                    "peak heap " + peakHeap + " bytes exceeds bound " + maxHeap);
            if (peakRss > 0) {
                check(peakRss <= maxRssKb,
                        "peak RSS " + peakRss + " KiB exceeds bound " + maxRssKb);
            }
            System.out.println("PASS qa-large-epg-peak-memory peak_heap_bytes=" + peakHeap
                    + " heap_limit=" + maxHeap + " peak_rss_kb=" + peakRss
                    + " rss_limit=" + maxRssKb);
        }
        System.out.println("LARGE EPG FIXTURE CHECK PASS");
    }
}
