package com.devtunasoft.tuneros.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * QA-03 on-device instrumentation. No JUnit or androidx.test dependency: this is a
 * plain android.app.Instrumentation run with `adb shell am instrument -w`.
 *
 * It runs the shared bounded soak on the device, then exercises the REAL app
 * diagnostics sink (OnDeviceSelfTest.observe) with synthetic playback events and
 * scans the resulting app-private event file for fixed format and canary leaks.
 * It does NOT open a network stream, start a decoder, or confirm a real video
 * frame; that evidence remains UNVERIFIED.
 */
public final class PlaybackSoakInstrumentation extends Instrumentation {

    private static final String EVENTS_FILE = "tuneros-selftest-events.tsv";
    private static final int MAX_PROBE_SWITCHES = 24;
    private static final String[] CANARIES = {
            QaPlaybackSoak.CANARY_URL, QaPlaybackSoak.CANARY_TOKEN,
            QaPlaybackSoak.CANARY_DRM, QaPlaybackSoak.CANARY_RECORDING
    };

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle status = new Bundle();
        Context context = getTargetContext();
        File work = new File(context.getCacheDir(), "qa-playback-soak");
        Path report = new File(context.getCacheDir(), "qa-playback-soak-report.txt").toPath();
        try {
            QaPlaybackSoak.Result soak = QaPlaybackSoak.generateAndCheck(work.toPath());
            QaPlaybackSoak.Result diagnostics = probeDiagnostics(context, work.toPath());
            List<String> lines = new ArrayList<>(soak.lines.size() + diagnostics.lines.size() + 2);
            lines.add("QA-03 on-device playback switching soak (synthetic, no decoder)");
            lines.addAll(soak.lines);
            lines.addAll(diagnostics.lines);
            lines.add("QA-03 DEVICE CHECK PASS");
            writeBounded(report, lines);
            status.putString("qa_playback_result", "PASS");
            status.putString("qa_playback_detail", "soak_checks=" + soak.lines.size()
                    + " diagnostic_checks=" + diagnostics.lines.size());
            status.putString("qa_playback_report", report.toString());
        } catch (Throwable failure) {
            List<String> lines = new ArrayList<>(3);
            lines.add("FAIL qa-playback-device " + failure.getClass().getName());
            lines.add("QA-03 DEVICE CHECK FAIL");
            try {
                writeBounded(report, lines);
            } catch (IOException ignored) {
                // The status bundle still carries the failure.
            }
            status.putString("qa_playback_result", "FAIL");
            status.putString("qa_playback_detail", "exception=" + failure.getClass().getName());
        } finally {
            deleteFixture(work.toPath());
        }
        finish(Activity.RESULT_OK, status);
    }

    private static void writeBounded(Path target, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
            if (text.length() > QaPlaybackSoak.MAX_REPORT_BYTES) {
                throw new IOException("on-device report bound exceeded");
            }
        }
        Files.write(target, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteFixture(Path dir) {
        for (String name : new String[] {"playlist.m3u", "plan.tsv", "manifest.properties"}) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException ignored) {
                // Scratch cleanup is best effort; the report and status are authoritative.
            }
        }
    }

    private static List<String> readBounded(Path file) throws IOException {
        List<String> lines = new ArrayList<>(512);
        int bytes = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                bytes += line.length() + 1;
                if (bytes > QaPlaybackSoak.MAX_DIAGNOSTICS_BYTES) {
                    throw new IOException("device diagnostic file exceeds its cap");
                }
                lines.add(line);
            }
        }
        return lines;
    }

    private static Object enumValue(Class<?> eventClass, String name) {
        try {
            Method valueOf = eventClass.getMethod("valueOf", String.class);
            return valueOf.invoke(null, name);
        } catch (Exception failure) {
            throw new IllegalStateException("event enum unusable: " + name, failure);
        }
    }

    /**
     * Emits a bounded synthetic switch sequence through the app's real numeric-only
     * diagnostics sink, then scans the resulting file without modifying it.
     */
    private static QaPlaybackSoak.Result probeDiagnostics(Context context, Path fixture) throws Exception {
        Class<?> owner = Class.forName("com.devtunasoft.tuneros.OnDeviceSelfTest");
        Class<?> eventClass = Class.forName("com.devtunasoft.tuneros.OnDeviceSelfTest$Event");
        Object instance = owner.getField("INSTANCE").get(null);
        Method observe = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().startsWith("observe") && params.length == 3
                    && params[0].isAssignableFrom(Context.class)
                    && params[1].equals(eventClass) && params[2] == long.class) {
                method.setAccessible(true);
                observe = method;
                break;
            }
        }
        if (observe == null) throw new IllegalStateException("OnDeviceSelfTest.observe not found");

        Path events = new File(context.getFilesDir(), EVENTS_FILE).toPath();
        List<String> names = new ArrayList<>(MAX_PROBE_SWITCHES);
        List<Long> metrics = new ArrayList<>(MAX_PROBE_SWITCHES);
        try (BufferedReader reader = Files.newBufferedReader(fixture.resolve("plan.tsv"),
                StandardCharsets.UTF_8)) {
            String line = reader.readLine(); // header
            if (line == null) throw new IOException("empty plan");
            while (names.size() < MAX_PROBE_SWITCHES && (line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if ("OK".equals(fields[2])) {
                    names.add("PLAYBACK_FIRST_FRAME");
                } else {
                    names.add("PLAYBACK_ERROR");
                }
                metrics.add(Long.parseLong(fields[4]));
            }
        }
        if (names.isEmpty()) throw new IOException("no planned switches");

        int emitted = 0;
        for (int i = 0; i < names.size(); i++) {
            observe.invoke(instance, context, enumValue(eventClass, "PLAYBACK_REQUESTED"), 0L);
            observe.invoke(instance, context, enumValue(eventClass, names.get(i)), metrics.get(i));
            emitted += 2;
        }

        List<String> after = readBounded(events);
        if (after.size() < emitted) {
            throw new IOException("diagnostic sink did not record all synthetic events");
        }
        // The app bounds its event file and may rotate it, so verify only the tail we emitted.
        int tail = after.size() - emitted;
        for (int i = 0; i < names.size(); i++) {
            String requested = after.get(tail + 2 * i);
            String outcome = after.get(tail + 2 * i + 1);
            if (!requested.contains("\tPLAYBACK_REQUESTED\t")
                    || !outcome.contains("\t" + names.get(i) + "\t")) {
                throw new IOException("diagnostic sink reordered or dropped a synthetic event");
            }
        }
        QaPlaybackSoak.Result scan = QaPlaybackSoak.scanDiagnostics(events, CANARIES);
        List<String> lines = new ArrayList<>(scan.lines.size() + 1);
        lines.add("PASS qa-playback-device-sink emitted=" + emitted + " observed=" + emitted
                + " events=" + after.size() + " sink=OnDeviceSelfTest.observe");
        lines.addAll(scan.lines);
        return new QaPlaybackSoak.Result(true, lines);
    }
}
