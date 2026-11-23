import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * QA-07 host-side gate. JDK-only: no libraries, no network, no Android SDK.
 *
 * Modes:
 *   generate <dir>   write the deterministic scratch fixtures into <dir>
 *   verify <dir>     regenerate in memory, compare with <dir>, run the gate, exit 0 only when
 *                    every positive check passes and every negative control fails as required.
 *
 * The PIN is derived at run time from a seed; no PIN literal is stored, printed or compared as text in output.
 * Only scratch identifiers are used. Reports contain check ids, counts and hashes, never a PIN or a URL.
 */
public final class Qa07Gate {
    private static final String STORE_HEADER = "qa07-store-v1";
    private static final int CHANNEL_COUNT = 25_000;   // matches PlaylistParser's bounded input cap
    private static final int FAVORITE_COUNT = 12_500;
    private static final int ROW_CAP = 24;             // recycled row objects, never one per channel
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int ITERATIONS = 120_000;     // matches the app's ParentalPin
    private static final int MAX_FAILURES = 4;         // the app locks on the fifth wrong entry
    private static final String[] FIXTURE_FILES = {"channels.tsv", "favorites.txt", "stores.txt", "sha256.txt"};
    private static final String TEST_PIN = derivePin(0);
    private static final String WRONG_PIN = derivePin(1);
    private static final byte[] SALT = deriveSalt();

    private Qa07Gate() { }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !(args[0].equals("generate") || args[0].equals("verify"))) {
                System.out.println("usage: Qa07Gate generate|verify <fixture-dir>");
                System.exit(2);
            }
            Path dir = Paths.get(args[1]);
            if (args[0].equals("generate")) {
                generate(dir);
            } else {
                verify(dir);
            }
        } catch (Exception error) {
            // Never print a message that could carry a PIN: only the exception class name.
            System.out.println("FAIL\tqa07-harness-exception\t" + error.getClass().getSimpleName());
            System.exit(3);
        }
    }

    // ---------------------------------------------------------------- fixture generation

    /** Writes all four fixtures from deterministic constants. Safe to call repeatedly. */
    private static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, byte[]> files = buildFixtures();
        for (String name : FIXTURE_FILES) {
            Files.write(dir.resolve(name), files.get(name));
        }
        System.out.println("GENERATED fixtures=" + files.size() + " channels=" + CHANNEL_COUNT +
            " favorites=" + FAVORITE_COUNT + " bytes=" + totalBytes(files));
        for (String name : FIXTURE_FILES) {
            System.out.println("SHA256\t" + name + "\t" + sha256Hex(files.get(name)));
        }
    }

    /** Regenerates the exact fixture bytes in memory. The verify path never writes into the repository. */
    private static Map<String, byte[]> buildFixtures() {
        ByteArrayOutputStream channels = new ByteArrayOutputStream();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            String line = String.format(Locale.ROOT, "c%05d\tQA07 C%05d\tg%03d\n", i, i, i / 250);
            channels.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream favorites = new ByteArrayOutputStream();
        for (int i = 0; i < FAVORITE_COUNT; i++) {
            String line = String.format(Locale.ROOT, "c%05d\n", i * 2);
            favorites.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream stores = new ByteArrayOutputStream();
        LinkedHashMap<String, String> records = buildStoreRecords();
        for (Map.Entry<String, String> entry : records.entrySet()) {
            stores.writeBytes(("# name=" + entry.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
            stores.writeBytes(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put(FIXTURE_FILES[0], channels.toByteArray());
        files.put(FIXTURE_FILES[1], favorites.toByteArray());
        files.put(FIXTURE_FILES[2], stores.toByteArray());

        StringBuilder manifest = new StringBuilder();
        for (String name : FIXTURE_FILES) {
            if (files.containsKey(name)) {
                manifest.append(sha256Hex(files.get(name))).append("  ").append(name).append('\n');
            }
        }
        files.put(FIXTURE_FILES[3], manifest.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static LinkedHashMap<String, String> buildStoreRecords() {
        String valid = record(STORE_HEADER, b64(SALT), b64(hash(TEST_PIN, SALT)), "0", "0");
        String[] fields = valid.split("\n", -1);
        String header = fields[0], saltLine = fields[1], hashLine = fields[2];
        String failuresLine = fields[3], lockLine = fields[4];
        LinkedHashMap<String, String> records = new LinkedHashMap<>();
        records.put("valid", valid);
        records.put("corrupt.bad-header", record("qa07-store-v0", saltLineValue(saltLine), hashLineValue(hashLine), "0", "0"));
        records.put("corrupt.bad-salt-b64", record(header, "!!!!", hashLineValue(hashLine), "0", "0"));
        records.put("corrupt.short-salt", record(header, b64(java.util.Arrays.copyOf(SALT, SALT_BYTES - 1)), hashLineValue(hashLine), "0", "0"));
        records.put("corrupt.bad-hash-b64", record(header, saltLineValue(saltLine), "@@@@", "0", "0"));
        records.put("corrupt.short-hash", record(header, saltLineValue(saltLine), b64(java.util.Arrays.copyOf(hash(TEST_PIN, SALT), HASH_BYTES - 1)), "0", "0"));
        records.put("corrupt.negative-failures", record(header, saltLineValue(saltLine), hashLineValue(hashLine), "-1", "0"));
        records.put("corrupt.too-many-failures", record(header, saltLineValue(saltLine), hashLineValue(hashLine), String.valueOf(MAX_FAILURES + 1), "0"));
        records.put("corrupt.negative-lock", record(header, saltLineValue(saltLine), hashLineValue(hashLine), "0", "-1"));
        records.put("corrupt.empty-lock", record(header, saltLineValue(saltLine), hashLineValue(hashLine), "0", ""));
        records.put("corrupt.wrong-hash", record(header, saltLineValue(saltLine), b64(hash(WRONG_PIN, SALT)), "0", "0"));
        records.put("corrupt.extra-field", record(header, saltLineValue(saltLine), hashLineValue(hashLine), "0 extra=1", "0"));
        records.put("corrupt.duplicate-key", record(header, hashLineValue(hashLine), hashLineValue(hashLine), "0", "0"));
        records.put("corrupt.swapped-keys", record(header, hashLineValue(hashLine), saltLineValue(saltLine), "0", "0"));
        return records;
    }

    private static String record(String header, String salt, String hash, String failures, String locked) {
        return header + "\nsalt=" + salt + "\nhash=" + hash + "\nfailures=" + failures + "\nlocked-until=" + locked + "\n";
    }

    private static String saltLineValue(String line) { return line.substring("salt=".length()); }
    private static String hashLineValue(String line) { return line.substring("hash=".length()); }

    // ---------------------------------------------------------------- verification

    private static void verify(Path dir) throws IOException {
        Map<String, byte[]> expected = buildFixtures();
        Map<String, byte[]> actual = new LinkedHashMap<>();
        for (String name : FIXTURE_FILES) {
            Path file = dir.resolve(name);
            actual.put(name, Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0]);
        }

        List<Check> positive = new ArrayList<>();
        List<Check> negative = new ArrayList<>();

        boolean bytesIdentical = true;
        for (String name : FIXTURE_FILES) {
            bytesIdentical &= java.util.Arrays.equals(expected.get(name), actual.get(name));
        }
        positive.add(check("fixture-regeneration-byte-identical", bytesIdentical,
            "files=" + FIXTURE_FILES.length + " regenerated bytes equal committed bytes"));

        boolean shaPinned = true;
        String manifest = new String(actual.get("sha256.txt"), StandardCharsets.UTF_8);
        for (String line : manifest.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("  ");
            if (parts.length != 2 || !expected.containsKey(parts[1])) { shaPinned = false; break; }
            shaPinned &= parts[0].equals(sha256Hex(expected.get(parts[1])));
        }
        shaPinned &= manifest.lines().count() == FIXTURE_FILES.length - 1;
        positive.add(check("fixture-sha256-pinned", shaPinned, "manifest lines match regenerated fixture hashes"));

        List<String> channelIds = parseChannelIds(new String(actual.get("channels.tsv"), StandardCharsets.UTF_8));
        Set<String> favoriteIds = parseFavorites(new String(actual.get("favorites.txt"), StandardCharsets.UTF_8));
        Map<String, String> storeRecords = parseStoreRecords(new String(actual.get("stores.txt"), StandardCharsets.UTF_8));

        positive.add(check("large-input-rows-bounded",
            channelIds.size() == CHANNEL_COUNT && favoriteIds.size() == FAVORITE_COUNT &&
                new HashSet<>(channelIds).size() == CHANNEL_COUNT && uiRowsBounded(channelIds.size(), ROW_CAP, ROW_CAP),
            "channels=" + channelIds.size() + " favorites=" + favoriteIds.size() + " rowCap=" + ROW_CAP));

        List<String> visible = new ArrayList<>(favoriteIds.size());
        for (String id : channelIds) if (favoriteIds.contains(id)) visible.add(id);
        boolean orderAndIndices = visible.size() == FAVORITE_COUNT;
        for (int i = 0; i < visible.size() && orderAndIndices; i++) {
            orderAndIndices = visible.get(i).equals(String.format(Locale.ROOT, "c%05d", i * 2)) && i < visible.size();
        }
        positive.add(check("favorites-filter-order-and-indices", orderAndIndices,
            "visible=" + visible.size() + " stable order preserved, each index in range"));

        String validRecord = storeRecords.get("valid");
        Decision accepted = evaluate(validRecord, TEST_PIN);
        positive.add(check("store-valid-accepted", accepted.accepted, "decision=" + accepted.reason));

        int denied = 0, corruptTotal = 0;
        for (Map.Entry<String, String> entry : storeRecords.entrySet()) {
            if (!entry.getKey().startsWith("corrupt.")) continue;
            corruptTotal++;
            if (!evaluate(entry.getValue(), TEST_PIN).accepted) denied++;
        }
        positive.add(check("store-corrupt-fail-closed", corruptTotal > 0 && denied == corruptTotal,
            "corrupt=" + corruptTotal + " denied=" + denied));

        boolean wrongRejected = !evaluate(validRecord, WRONG_PIN).accepted &&
            !evaluate(validRecord, "0000").accepted && !evaluate(validRecord, "99999999").accepted;
        positive.add(check("wrong-pin-rejected", wrongRejected, "wrong entries denied, no attempt count logged"));

        // Negative mutation controls. Each of these MUST produce FAIL; a PASS means a detector is broken.
        byte[] mutated = expected.get("favorites.txt").clone();
        mutated[0] ^= 0x01;
        negative.add(check("red-fixture-byte-mutation",
            java.util.Arrays.equals(mutated, actual.get("favorites.txt")), "one-byte mutation must break byte identity"));
        negative.add(check("red-fixture-sha-mutation",
            sha256Hex(mutated).equals(sha256Hex(expected.get("favorites.txt"))), "one-byte mutation must break the pinned hash"));
        negative.add(check("red-row-bound-mutation", uiRowsBounded(visible.size(), 1, ROW_CAP),
            "rowCap=1 cannot serve " + visible.size() + " visible rows"));
        negative.add(check("red-pin-leak-injection",
            !containsPin(renderReport(positive, negative, "") + "leak=" + TEST_PIN, TEST_PIN),
            "injected PIN must be detected by the leak check"));
        String badHeader = storeRecords.get("corrupt.bad-header");
        negative.add(check("red-store-corrupt-accepted-mutant", !permissiveHashOnly(badHeader, TEST_PIN),
            "permissive parser would accept a corrupt-shape record; strict gate must reject it"));
        negative.add(check("red-wrong-pin-accepted-mutant", evaluate(validRecord, WRONG_PIN).accepted,
            "wrong PIN must not be accepted by the store check"));

        List<String> ids = new ArrayList<>();
        for (Check c : positive) ids.add(c.id);
        for (Check c : negative) ids.add(c.id);
        positive.add(check("check-ids-unique", new HashSet<>(ids).size() == ids.size(),
            "ids=" + ids.size() + " distinct=" + new HashSet<>(ids).size()));

        String report = renderReport(positive, negative, "");
        boolean pinFree = !containsPin(report, TEST_PIN);
        for (byte[] bytes : expected.values()) pinFree &= !containsPin(new String(bytes, StandardCharsets.UTF_8), TEST_PIN);
        positive.add(check("diagnostics-pin-free", pinFree, "report and fixtures contain no PIN literal"));

        report = renderReport(positive, negative, "");
        System.out.print(report);
        int passCount = 0, failCount = 0;
        for (Check c : positive) if (c.pass) passCount++;
        for (Check c : negative) if (!c.pass) failCount++;
        boolean ok = passCount == positive.size() && failCount == negative.size();
        System.out.println("SUMMARY\tpositive=" + passCount + "/" + positive.size() +
            "\tnegative-red=" + failCount + "/" + negative.size() +
            "\ttotal=" + (positive.size() + negative.size()) +
            "\tresult=" + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }

    private static String renderReport(List<Check> positive, List<Check> negative, String ignored) {
        StringBuilder out = new StringBuilder();
        for (Check c : positive) out.append(c.pass ? "PASS" : "FAIL").append('\t').append(c.id).append('\t').append(c.detail).append('\n');
        for (Check c : negative) out.append(c.pass ? "UNEXPECTED-PASS" : "FAIL").append('\t').append(c.id).append('\t').append(c.detail).append('\n');
        return out.toString();
    }

    private static final class Check {
        final String id; final boolean pass; final String detail;
        Check(String id, boolean pass, String detail) { this.id = id; this.pass = pass; this.detail = detail; }
    }
    private static Check check(String id, boolean pass, String detail) { return new Check(id, pass, detail); }

    // ---------------------------------------------------------------- parser / models

    private static List<String> parseChannelIds(String text) {
        List<String> ids = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (parts.length == 3) ids.add(parts[0]);
        }
        return ids;
    }

    private static Set<String> parseFavorites(String text) {
        Set<String> ids = new HashSet<>();
        for (String line : text.split("\n", -1)) if (!line.isEmpty()) ids.add(line);
        return ids;
    }

    private static Map<String, String> parseStoreRecords(String text) {
        LinkedHashMap<String, String> records = new LinkedHashMap<>();
        String name = null;
        List<String> lines = new ArrayList<>(5);
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("# name=")) {
                if (name != null) records.put(name, String.join("\n", lines) + "\n");
                name = line.substring("# name=".length());
                lines = new ArrayList<>(5);
            } else if (name != null && lines.size() < 5) {
                lines.add(line);
            }
        }
        if (name != null) records.put(name, String.join("\n", lines) + "\n");
        return records;
    }

    /** Strict store-record decision. Mirrors the app's fail-closed format checks; never emits input values. */
    private static Decision evaluate(String text, String pin) {
        if (text == null) return new Decision(false, "rejected-missing");
        try {
            String[] lines = text.split("\n", -1);
            if (lines.length != 6 || !lines[5].isEmpty()) return new Decision(false, "rejected-shape");
            if (!STORE_HEADER.equals(lines[0])) return new Decision(false, "rejected-header");
            if (!lines[1].startsWith("salt=") || !lines[2].startsWith("hash=") ||
                !lines[3].startsWith("failures=") || !lines[4].startsWith("locked-until=")) {
                return new Decision(false, "rejected-keys");
            }
            byte[] salt = Base64.getDecoder().decode(lines[1].substring(5));
            byte[] expected = Base64.getDecoder().decode(lines[2].substring(5));
            if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) return new Decision(false, "rejected-length");
            int failures = Integer.parseInt(lines[3].substring(9));
            long locked = Long.parseLong(lines[4].substring(13));
            if (failures < 0 || failures > MAX_FAILURES || locked < 0) return new Decision(false, "rejected-range");
            boolean match = MessageDigest.isEqual(hash(pin, salt), expected);
            return new Decision(match, match ? "accepted" : "rejected-mismatch");
        } catch (RuntimeException error) {
            return new Decision(false, "rejected-" + error.getClass().getSimpleName());
        }
    }

    /** Deliberately lax mutant used only by the RED control: ignores shape, order and range. */
    private static boolean permissiveHashOnly(String text, String pin) {
        if (text == null) return false;
        try {
            byte[] salt = null, expected = null;
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("salt=")) salt = Base64.getDecoder().decode(line.substring(5));
                if (line.startsWith("hash=")) expected = Base64.getDecoder().decode(line.substring(5));
            }
            return salt != null && expected != null && MessageDigest.isEqual(hash(pin, salt), expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** Recycled-row simulation: only rowCap row objects exist, and every sampled index is in range. */
    private static boolean uiRowsBounded(int visibleSize, int rowCap, int minimumRows) {
        if (visibleSize <= 0 || rowCap <= 0 || minimumRows <= 0) return false;
        if (Math.min(rowCap, visibleSize) < minimumRows) return false;
        int[] rowObjects = new int[Math.min(rowCap, visibleSize)];
        int[] offsets = {0, 1, 7, 100, 1_000, visibleSize / 2, Math.max(0, visibleSize - 1)};
        for (int offset : offsets) {
            int first = Math.max(0, Math.min(offset, visibleSize - 1));
            int rows = Math.min(rowCap, visibleSize - first);
            if (rows > rowObjects.length) return false;
            for (int row = 0; row < rows; row++) {
                int index = first + row;
                if (index < 0 || index >= visibleSize) return false;
                rowObjects[row] = index;
            }
        }
        return true;
    }

    private static final class Decision {
        final boolean accepted; final String reason;
        Decision(boolean accepted, String reason) { this.accepted = accepted; this.reason = reason; }
    }

    // ---------------------------------------------------------------- primitives

    /** The scratch PIN is derived, never written as a literal in source, fixtures or diagnostics. */
    private static String derivePin(int offset) {
        long seed = 20_261_123L * 31L + 7L + offset;
        int n = (int) Math.floorMod(seed, 100_000_000L);
        return String.format(Locale.ROOT, "%08d", n);
    }

    private static byte[] deriveSalt() {
        byte[] salt = new byte[SALT_BYTES];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) ((i * 37 + 11) & 0xff);
        return salt;
    }

    private static byte[] hash(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error.getClass().getSimpleName());
        } finally {
            spec.clearPassword();
        }
    }

    private static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error.getClass().getSimpleName());
        }
    }

    private static boolean containsPin(String text, String pin) { return text != null && text.contains(pin); }

    private static long totalBytes(Map<String, byte[]> files) {
        long total = 0;
        for (byte[] bytes : files.values()) total += bytes.length;
        return total;
    }
}
