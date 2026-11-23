import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * MV-04: deterministic, zero-dependency JVM generator and verifier for the multiview lifecycle
 * churn contract. The trace is entirely synthetic: tile numbers, bounded operations and expected
 * state. No provider URL, credential, DRM key, media payload or real recording is involved.
 *
 * Modes:
 *   --generate FILE   write the canonical trace
 *   --verify FILE     verify a trace (exit 0 only for PASS)
 *   --all [FILE]      fixture match + positive verification + three negative mutation controls
 */
public final class Mv04Scenario {
    private static final int CAPACITY = 2;
    private static final int MAX_STEPS = 64;
    private static final int MAX_CREATED = 24;
    private static final int RUN_STEPS = 48;
    private static final long SEED = 0x4D563034L;
    private static final int FIELDS = 9;
    private static final String DEFAULT_FIXTURE = "qa/fixtures/mv04/generated/churn.trace";

    private Mv04Scenario() {
    }

    private enum Op { ACQUIRE, RELEASE, FOCUS, TEARDOWN }

    private static final class Step {
        final int seq;
        final Op op;
        final int arg;

        Step(int seq, Op op, int arg) {
            this.seq = seq;
            this.op = op;
            this.arg = arg;
        }
    }

    /** Identical policy to the platform harness: bounded tiles, one audio owner, full release. */
    private static final class Model {
        final TreeMap<Integer, Integer> active = new TreeMap<>();
        Integer audible;
        int players;
        int threads;
        int refusals;
        int created;
        int released;
        int focused;

        String apply(Step step) {
            switch (step.op) {
                case ACQUIRE: {
                    if (active.size() >= CAPACITY) {
                        refusals++;
                        // Fallback: focus an existing tile. Never create a third player or thread.
                        if (!active.isEmpty()) audible = active.firstKey();
                        return "REFUSED";
                    }
                    int slot = 1;
                    while (slot <= CAPACITY && active.containsValue(slot)) slot++;
                    if (slot > CAPACITY) throw new IllegalStateException("no free tile slot");
                    active.put(step.arg, slot);
                    players++;
                    threads++;
                    created++;
                    if (audible == null) audible = step.arg;
                    return "CREATED";
                }
                case RELEASE: {
                    if (!active.containsKey(step.arg)) {
                        refusals++;
                        return "REFUSED";
                    }
                    active.remove(step.arg);
                    players--;
                    threads--;
                    released++;
                    if (audible != null && audible == step.arg) {
                        audible = active.isEmpty() ? null : active.firstKey();
                    }
                    return "RELEASED";
                }
                case FOCUS: {
                    if (!active.containsKey(step.arg)) {
                        refusals++;
                        return "REFUSED";
                    }
                    audible = step.arg;
                    focused++;
                    return "FOCUSED";
                }
                case TEARDOWN: {
                    for (Integer id : new ArrayList<>(active.keySet())) {
                        active.remove(id);
                        players--;
                        threads--;
                        released++;
                    }
                    audible = null;
                    return "CLEAN";
                }
                default:
                    throw new IllegalStateException("unknown op " + step.op);
            }
        }

        String[] fields(String result) {
            return new String[]{
                    result,
                    join(active.keySet()),
                    audible == null ? "-" : Integer.toString(audible),
                    Integer.toString(players),
                    Integer.toString(threads),
                    Integer.toString(refusals),
            };
        }
    }

    private static final class Result {
        int checks;
        int failures;
        String firstCode = "";
        final List<String> detail = new ArrayList<>();

        boolean pass() {
            return failures == 0;
        }
    }

    private static void check(Result result, boolean ok, String code, String detail) {
        result.checks++;
        if (!ok) {
            result.failures++;
            if (result.firstCode.isEmpty()) result.firstCode = code;
            if (result.detail.size() < 6) result.detail.add(code + " " + detail);
        }
    }

    private static String join(Iterable<Integer> ids) {
        StringBuilder text = new StringBuilder();
        for (Integer id : ids) {
            if (text.length() > 0) text.append(',');
            text.append(id);
        }
        return text.length() == 0 ? "-" : text.toString();
    }

    private static List<Integer> parseIds(String text) {
        List<Integer> ids = new ArrayList<>();
        if (text.equals("-")) return ids;
        for (String part : text.split(",", -1)) {
            int value = parse(part, -1);
            if (value > 0) ids.add(value);
        }
        return ids;
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static int next(long[] state, int bound) {
        state[0] = (state[0] * 6364136223846793005L + 1442695040888963407L) & Long.MAX_VALUE;
        return (int) ((state[0] >>> 24) % bound);
    }

    private static int pick(long[] state, TreeMap<Integer, Integer> active) {
        return new ArrayList<>(active.keySet()).get(next(state, active.size()));
    }

    /** Fixed-seed, bounded churn: create/release cycles, audio switches and capacity refusals. */
    private static List<Step> generate() {
        long[] state = {SEED};
        TreeMap<Integer, Integer> active = new TreeMap<>();
        List<Step> steps = new ArrayList<>();
        int nextId = 1;
        while (steps.size() < RUN_STEPS) {
            if (active.isEmpty() && nextId > MAX_CREATED) break;
            int choice;
            if (active.isEmpty()) {
                choice = 0;
            } else if (nextId > MAX_CREATED) {
                choice = next(state, 2) == 0 ? 1 : 2;
            } else if (active.size() < CAPACITY) {
                choice = next(state, 3);
            } else {
                // At capacity: 1 releases, 2 focuses, 3/4 are refused acquires with fallback.
                choice = 1 + next(state, 4);
            }
            int seq = steps.size() + 1;
            if (choice == 0) {
                int slot = 1;
                while (active.containsValue(slot)) slot++;
                if (slot > CAPACITY || nextId > MAX_CREATED) {
                    throw new IllegalStateException("generator exceeded tile or capacity bound: slot="
                            + slot + " nextId=" + nextId + " active=" + join(active.keySet())
                            + " slots=" + active.values() + " steps=" + steps.size());
                }
                active.put(nextId, slot);
                steps.add(new Step(seq, Op.ACQUIRE, nextId));
                nextId++;
            } else if (choice == 1) {
                steps.add(new Step(seq, Op.RELEASE, pick(state, active)));
                active.remove(steps.get(steps.size() - 1).arg);
            } else if (choice == 2) {
                steps.add(new Step(seq, Op.FOCUS, pick(state, active)));
            } else {
                steps.add(new Step(seq, Op.ACQUIRE, nextId));
            }
        }
        steps.add(new Step(steps.size() + 1, Op.TEARDOWN, -1));
        if (steps.size() > MAX_STEPS) throw new IllegalStateException("step bound exceeded");
        return steps;
    }

    private static String generateTrace() {
        StringBuilder text = new StringBuilder();
        Model model = new Model();
        for (Step step : generate()) {
            String result = model.apply(step);
            String[] fields = model.fields(result);
            text.append(step.seq).append('\t').append(step.op).append('\t')
                    .append(step.arg < 0 ? "-" : Integer.toString(step.arg)).append('\t')
                    .append(String.join("\t", fields)).append('\n');
        }
        return text.toString();
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.replace("\r", "").strip();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static Result verify(List<String> lines) {
        Result result = new Result();
        Model model = new Model();
        int expectedSeq = 1;
        for (String raw : lines) {
            String[] field = raw.split("\t", -1);
            check(result, field.length == FIELDS, "FORMAT", "expected " + FIELDS + " fields, got " + field.length);
            if (field.length != FIELDS) return result;
            int seq = parse(field[0], -1);
            Op op;
            try {
                op = Op.valueOf(field[1]);
            } catch (IllegalArgumentException error) {
                check(result, false, "FORMAT", "unknown op " + field[1]);
                return result;
            }
            int arg = parse(field[2], -1);
            check(result, seq == expectedSeq, "SEQUENCE", "seq=" + seq + " expected=" + expectedSeq);
            expectedSeq++;
            check(result, lines.size() <= MAX_STEPS, "BOUND_EXCEEDED", "steps=" + lines.size());

            int activeBefore = model.active.size();
            int playersBefore = model.players;
            int threadsBefore = model.threads;
            String outcome = model.apply(new Step(seq, op, arg));
            String[] expected = model.fields(outcome);

            check(result, expected[0].equals(field[3]), "RESULT_MISMATCH", "seq=" + seq);
            List<Integer> traceActive = parseIds(field[4]);
            check(result, traceActive.size() <= CAPACITY, "CAPACITY_EXCEEDED", "seq=" + seq + " active=" + field[4]);
            check(result, traceActive.equals(new ArrayList<>(model.active.keySet())), "ACTIVE_MISMATCH", "seq=" + seq);
            List<Integer> traceAudible = parseIds(field[5]);
            check(result, traceAudible.size() <= 1, "AUDIBLE_COUNT", "seq=" + seq + " audible=" + field[5]);
            check(result, field[5].equals(expected[2]), "AUDIBLE_MISMATCH", "seq=" + seq);
            int tracePlayers = parse(field[6], -1);
            check(result, tracePlayers <= model.players, "LEAKED_PLAYER", "seq=" + seq + " players=" + field[6]);
            check(result, tracePlayers == model.players, "PLAYER_MISMATCH", "seq=" + seq);
            int traceThreads = parse(field[7], -1);
            check(result, traceThreads <= model.threads, "LEAKED_HANDLER_THREAD", "seq=" + seq + " threads=" + field[7]);
            check(result, traceThreads == model.threads, "THREAD_MISMATCH", "seq=" + seq);
            check(result, parse(field[8], -1) == model.refusals, "REFUSAL_MISMATCH", "seq=" + seq);

            check(result, model.active.size() <= CAPACITY, "CAPACITY_INVARIANT", "seq=" + seq);
            boolean audioOk = model.audible == null
                    ? model.active.isEmpty()
                    : model.active.containsKey(model.audible);
            check(result, audioOk, "AUDIBLE_INVARIANT", "seq=" + seq);
            check(result, model.players == model.active.size(), "PLAYER_LEAK_INVARIANT", "seq=" + seq);
            check(result, model.threads == model.active.size(), "THREAD_LEAK_INVARIANT", "seq=" + seq);
            if (op == Op.TEARDOWN) {
                check(result, model.active.isEmpty() && model.audible == null
                                && model.players == 0 && model.threads == 0,
                        "TEARDOWN_INVARIANT", "seq=" + seq);
            }
            if ("REFUSED".equals(outcome)) {
                check(result, activeBefore == model.active.size()
                                && playersBefore == model.players && threadsBefore == model.threads,
                        "REFUSAL_MUTATION", "seq=" + seq);
            }
        }
        check(result, model.created <= MAX_CREATED, "BOUND_EXCEEDED", "created=" + model.created);
        check(result, model.created >= 8 && model.released >= 8, "COVERAGE_CYCLES",
                "created=" + model.created + " released=" + model.released);
        check(result, model.refusals >= 2, "COVERAGE_REFUSAL", "refusals=" + model.refusals);
        check(result, model.focused >= 2, "COVERAGE_AUDIO_SWITCH", "focused=" + model.focused);
        check(result, model.released == model.created && model.active.isEmpty(),
                "TEARDOWN_INVARIANT", "released=" + model.released + " created=" + model.created);
        return result;
    }

    private static String[] copy(String[] source) {
        return source.clone();
    }

    /** Two audible owners at a populated step: must be rejected with AUDIBLE_COUNT. */
    private static String[] mutateAudible(String[] good) {
        Model model = new Model();
        for (int index = 0; index < good.length; index++) {
            String[] field = good[index].split("\t", -1);
            model.apply(new Step(parse(field[0], 0), Op.valueOf(field[1]), parse(field[2], -1)));
            if (model.active.size() == 2) {
                String[] mutated = copy(good);
                String[] target = mutated[index].split("\t", -1);
                List<Integer> ids = new ArrayList<>(model.active.keySet());
                target[5] = ids.get(0) + "," + ids.get(1);
                mutated[index] = String.join("\t", target);
                return mutated;
            }
        }
        throw new IllegalStateException("no two-tile step available for negative control");
    }

    /** A surviving player at teardown: must be rejected with LEAKED_PLAYER. */
    private static String[] mutateLeak(String[] good) {
        String[] mutated = copy(good);
        int last = mutated.length - 1;
        String[] target = mutated[last].split("\t", -1);
        if (!"TEARDOWN".equals(target[1])) throw new IllegalStateException("last step is not teardown");
        target[6] = "1";
        mutated[last] = String.join("\t", target);
        return mutated;
    }

    /** A third tile surviving a refused acquire: must be rejected with CAPACITY_EXCEEDED. */
    private static String[] mutateCapacity(String[] good) {
        for (int index = 0; index < good.length; index++) {
            String[] target = good[index].split("\t", -1);
            if ("ACQUIRE".equals(target[1]) && "REFUSED".equals(target[3])) {
                String[] mutated = copy(good);
                target[4] = target[4] + ",90";
                mutated[index] = String.join("\t", target);
                return mutated;
            }
        }
        throw new IllegalStateException("no refusal step available for negative control");
    }

    private static String digest(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "--all" : args[0];
        String trace = generateTrace();
        if ("--generate".equals(mode)) {
            if (args.length != 2) throw new IllegalArgumentException("usage: --generate FILE");
            Files.write(Paths.get(args[1]), trace.getBytes(StandardCharsets.UTF_8));
            System.out.println("PASS mv04-generator steps=" + lines(trace).size() + " sha256=" + digest(trace));
            return;
        }
        if ("--verify".equals(mode)) {
            if (args.length != 2) throw new IllegalArgumentException("usage: --verify FILE");
            Result result = verify(lines(read(Paths.get(args[1]))));
            report("mv04-verify", result);
            if (!result.pass()) System.exit(1);
            return;
        }
        if (!"--all".equals(mode)) throw new IllegalArgumentException("unknown mode " + mode);

        String fixturePath = args.length >= 2 ? args[1] : DEFAULT_FIXTURE;
        Path fixture = Paths.get(fixturePath);
        boolean fixtureOk = Files.isRegularFile(fixture) && read(fixture).equals(trace);
        System.out.println((fixtureOk ? "PASS" : "FAIL") + "\tmv04-fixture-match\t" + fixturePath
                + " sha256=" + digest(trace));

        Result positive = verify(lines(trace));
        report("mv04-positive-trace", positive);

        String[] good = lines(trace).toArray(new String[0]);
        int negativeFailures = 0;
        int negativeChecks = 0;
        String[][] controls = {
                {"AUDIBLE_COUNT", "two-audible-owners"},
                {"LEAKED_PLAYER", "surviving-player-at-teardown"},
                {"CAPACITY_EXCEEDED", "third-tile-on-refusal"},
        };
        for (int index = 0; index < controls.length; index++) {
            String[] mutated;
            if (index == 0) mutated = mutateAudible(good);
            else if (index == 1) mutated = mutateLeak(good);
            else mutated = mutateCapacity(good);
            Result negative = verify(lines(String.join("\n", mutated)));
            negativeChecks += negative.checks;
            boolean rejected = !negative.pass() && controls[index][0].equals(negative.firstCode);
            if (!rejected) negativeFailures++;
            System.out.println((rejected ? "PASS" : "FAIL") + "\tmv04-negative-mutation\t"
                    + controls[index][1] + " expected=" + controls[index][0]
                    + " actual=" + (negative.firstCode.isEmpty() ? "NONE" : negative.firstCode));
        }
        int totalChecks = positive.checks + negativeChecks + 4;
        boolean ok = fixtureOk && positive.pass() && negativeFailures == 0;
        System.out.println((ok ? "PASS" : "FAIL") + "\tmv04-jvm-gate\tchecks=" + totalChecks
                + " positive=" + positive.checks + " negative=" + negativeChecks
                + " negative_failures=" + negativeFailures + " steps=" + lines(trace).size()
                + " sha256=" + digest(trace));
        if (!ok) System.exit(1);
    }

    private static void report(String name, Result result) {
        System.out.println((result.pass() ? "PASS" : "FAIL") + "\t" + name
                + "\tchecks=" + result.checks + " failures=" + result.failures
                + (result.firstCode.isEmpty() ? "" : " first=" + result.firstCode));
        for (String line : result.detail) System.out.println("DETAIL\t" + name + "\t" + line);
    }
}
