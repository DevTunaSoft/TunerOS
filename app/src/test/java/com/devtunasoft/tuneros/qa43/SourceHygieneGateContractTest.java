package com.devtunasoft.tuneros.qa43;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * QA-43 pure JVM source-contract tests. No Android classes, no stubs and no
 * product source is modified. The gate and its fixture contract are the
 * subjects under test.
 */
public class SourceHygieneGateContractTest {
    private static final String[] EXPECTED_IDS = {
        "CREDENTIAL_ASSIGNMENT", "URI_CREDENTIAL_TOKEN", "HOSTILE_URI_CONTROL",
        "PRIVATE_FILE_PATH", "DRM_KEY_LITERAL", "CANARY_MARKER"
    };
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final String CANARY_SEED = "qa43-source-hygiene-canary-seed-v1";
    private static final String CANARY_PREFIX = "QA43SYNTH-";

    private static Path root() {
        String wd = System.getProperty("user.dir");
        Path dir = Paths.get(wd == null ? "." : wd).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("scripts/qa43/source_hygiene_gate.py"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("repo root not found above the test working directory");
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static List<String> rows(String content) {
        List<String> out = new ArrayList<String>();
        for (String line : content.split("\n")) {
            String clean = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!clean.trim().isEmpty() && !clean.trim().startsWith("#")) {
                out.add(clean);
            }
        }
        return out;
    }

    private static List<String[]> contract() throws Exception {
        List<String[]> rows = new ArrayList<String[]>();
        for (String line : rows(text(root().resolve("qa/fixtures/source-hygiene43/contract.tsv")))) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) {
                throw new AssertionError("contract row must have four tab fields");
            }
            rows.add(fields);
        }
        return rows;
    }

    private static Map<String, String> expectations() throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        for (String line : rows(text(root().resolve("qa/fixtures/source-hygiene43/expectations.tsv")))) {
            int at = line.indexOf('=');
            if (at < 0) {
                throw new AssertionError("expectation row is not key=value");
            }
            map.put(line.substring(0, at).trim(), line.substring(at + 1).trim());
        }
        return map;
    }

    /** Mirrors the gate's literal-only scan; returns path:line:code ids, never matched text. */
    private static List<String> findings(Path file, List<String[]> patterns) throws Exception {
        List<String> hits = new ArrayList<String>();
        String content = text(file);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            Matcher literals = STRING_LITERAL.matcher(line);
            while (literals.find()) {
                String literal = literals.group();
                for (String[] pattern : patterns) {
                    if (Pattern.compile(pattern[1]).matcher(literal).find()) {
                        hits.add(file.getFileName() + ":" + (i + 1) + ":" + pattern[0]);
                    }
                }
            }
        }
        return hits;
    }

    private static String derivedCanary() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(CANARY_SEED.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            hex.append(String.format("%02x", hash[i] & 0xFF));
        }
        return CANARY_PREFIX + hex;
    }

    @Test
    public void contractIsPinnedAndEveryRegexCompiles() throws Exception {
        List<String[]> rows = contract();
        Map<String, String> expected = expectations();
        assertEquals("pattern count", 6, rows.size());
        assertEquals("expected pattern count", "6", expected.get("patterns"));
        assertEquals("expected mutant count", "3", expected.get("mutants"));
        assertEquals("baseline findings", "0", expected.get("baseline_findings"));
        assertTrue("minimum source files must be a real bound", Integer.parseInt(expected.get("min_files")) >= 100);
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("pattern id at " + i, EXPECTED_IDS[i], rows.get(i)[0]);
            assertEquals("scope at " + i, "all", rows.get(i)[2]);
            Pattern.compile(rows.get(i)[1]);
        }
    }

    @Test
    public void fixtureBytesMatchPinnedManifest() throws Exception {
        Path dir = root().resolve("qa/fixtures/source-hygiene43");
        List<String> manifest = rows(text(dir.resolve("sha256.txt")));
        assertEquals("manifest rows", 4, manifest.size());
        for (String line : manifest) {
            String[] fields = line.trim().split("\\s+");
            assertEquals("manifest row fields", 2, fields.length);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(fields[1])));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            assertEquals("digest for " + fields[1], fields[0], hex.toString());
        }
    }

    @Test
    public void committedSourcesHaveNoDeclaredFindings() throws Exception {
        Path base = root();
        List<Path> files = new ArrayList<Path>();
        for (String dirName : new String[]{"app/src", "core/src"}) {
            try (Stream<Path> stream = Files.walk(base.resolve(dirName))) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".java"))
                    .forEach(files::add);
            }
        }
        assertTrue("source file count too small: " + files.size(),
            files.size() >= Integer.parseInt(expectations().get("min_files")));
        List<String[]> patterns = contract();
        List<String> hits = new ArrayList<String>();
        for (Path file : files) {
            hits.addAll(findings(file, patterns));
        }
        assertTrue("hygiene findings: count=" + hits.size() + " first=" +
            (hits.isEmpty() ? "none" : hits.subList(0, Math.min(5, hits.size())).toString()),
            hits.isEmpty());
    }

    @Test
    public void mutationNeedlesArePinnedAndCanaryDetectorMatches() throws Exception {
        Path dir = root().resolve("qa/fixtures/source-hygiene43");
        List<String[]> mutants = new ArrayList<String[]>();
        for (String line : rows(text(dir.resolve("mutants.tsv")))) {
            mutants.add(line.split("\t", -1));
        }
        assertEquals("mutant count", 3, mutants.size());
        List<String> ids = new ArrayList<String>();
        for (String[] pattern : contract()) {
            ids.add(pattern[0]);
        }
        for (String[] mutant : mutants) {
            assertEquals("mutant fields", 3, mutant.length);
            assertTrue("mutant must use the runtime canary placeholder", mutant[1].contains("{CANARY}"));
            assertTrue("unknown pinned code " + mutant[2], ids.contains(mutant[2]));
        }
        for (String[] pattern : contract()) {
            if ("CANARY_MARKER".equals(pattern[0])) {
                assertTrue("derived canary is not matched by the contract",
                    Pattern.compile(pattern[1]).matcher(derivedCanary()).find());
            }
        }
        assertTrue("scratch substrate is not clean",
            findings(dir.resolve("clean-sample.kt.txt"), contract()).isEmpty());
    }

    @Test
    public void gateScriptIsOfflineAndDataDriven() throws Exception {
        String script = text(root().resolve("scripts/qa43/source_hygiene_gate.py"));
        List<String> required = Arrays.asList(
            "def mode_scan", "def mode_probe", "load_contract()", "aggregate_digest()",
            "ls-files", "choices=(\"scan\", \"probe\")", "COMMITTED_BYTES");
        for (String token : required) {
            assertTrue("gate script missing " + token, script.contains(token));
        }
        assertFalse("provider address literal in gate script", script.contains("http://") || script.contains("https://"));
        assertFalse("android dependency in JVM test", text(root().resolve(
            "app/src/test/java/com/devtunasoft/tuneros/qa43/SourceHygieneGateContractTest.java"))
            .contains("import" + " android."));
    }
}
