package dev.whizzle.logic.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A Logic Block's rules, in two lists:
 * <ul>
 *   <li>{@code rules}, the <b>output</b> rules: the first rule whose conditions all hold decides the output (its first
 *       action is an output action; any further actions run too), otherwise {@code otherwise}. Like if / else if / else.</li>
 *   <li>{@code also}: every rule whose conditions all hold runs its actions, every check. None of them sets the output.</li>
 * </ul>
 * A rule with no conditions always holds.
 */
public record Program(List<Rule> rules, Action otherwise, List<Rule> also) {
    /** Stored format: 1 = v1 (output rules with one action each), 2 = v2 (actions lists, also rules, move). */
    public static final int FORMAT = 2;
    public static final int MAX_RULES = 8, MAX_ALSO = 8, MAX_CONDITIONS = 3, MAX_ACTIONS = 3, MAX_BYTES = 8192;

    public record Rule(List<Condition> conditions, List<Action> actions) {
        public Rule {
            conditions = List.copyOf(conditions);
            actions = List.copyOf(actions);
            if (actions.isEmpty()) throw new IllegalArgumentException("A rule needs an action");
        }
        public Rule(List<Condition> conditions, Action action) { this(conditions, List.of(action)); }

        /** The first action: for an output rule, the one that sets the output. */
        public Action action() { return actions.getFirst(); }

        public String describe() {
            StringBuilder sb = new StringBuilder(conditions.isEmpty() ? "always" : "when ");
            for (int i = 0; i < conditions.size(); i++) sb.append(i == 0 ? "" : " and ").append(conditions.get(i).describe());
            sb.append(" -> ");
            for (int i = 0; i < actions.size(); i++) sb.append(i == 0 ? "" : ", then ").append(actions.get(i).describe());
            return sb.toString();
        }
    }

    public static final Program EMPTY = new Program(List.of(), Action.set(0), List.of());

    public Program {
        rules = List.copyOf(rules);
        also = List.copyOf(also);
        Objects.requireNonNull(otherwise);
    }

    public Program(List<Rule> rules, Action otherwise) { this(rules, otherwise, List.of()); }

    public Program withAlso(List<Rule> also) { return new Program(rules, otherwise, also); }

    /** True when any rule can act on the world (so the plugin knows to run it even if the output doesn't change). */
    public boolean acts() {
        for (Rule r : also) for (Action a : r.actions()) if (!a.kind().output()) return true;
        for (Rule r : rules) for (Action a : r.actions()) if (!a.kind().output()) return true;
        return false;
    }

    /** Null when the program can run, otherwise the first problem, naming the rule. */
    public String problem() {
        if (rules.size() > MAX_RULES) return "At most " + MAX_RULES + " output rules";
        if (also.size() > MAX_ALSO) return "At most " + MAX_ALSO + " \"also\" rules";
        for (int r = 0; r < rules.size(); r++) {
            String p = ruleProblem(rules.get(r), true);
            if (p != null) return "Rule " + (r + 1) + ": " + p;
        }
        for (int r = 0; r < also.size(); r++) {
            String p = ruleProblem(also.get(r), false);
            if (p != null) return "Also " + (r + 1) + ": " + p;
        }
        if (otherwise.kind() != Action.Kind.SET && otherwise.kind() != Action.Kind.KEEP) return "Otherwise can only set an output or keep it";
        String p = otherwise.problem();
        return p == null ? null : "Otherwise: " + p;
    }

    private static String ruleProblem(Rule rule, boolean output) {
        if (rule.conditions().size() > MAX_CONDITIONS) return "at most " + MAX_CONDITIONS + " conditions";
        if (rule.actions().size() > MAX_ACTIONS) return "at most " + MAX_ACTIONS + " actions";
        for (Condition c : rule.conditions()) {
            String p = c.problem();
            if (p != null) return p;
        }
        for (int i = 0; i < rule.actions().size(); i++) {
            Action a = rule.actions().get(i);
            if (output && i == 0 && !a.kind().output()) return "the first action sets the output (output, pulse, toggle or keep)";
            if (a.kind().output() && (!output || i > 0)) return output ? "only the first action sets the output" : "\"also\" rules don't set the output; use the output rules";
            String p = a.problem();
            if (p != null) return p;
        }
        return null;
    }

    public byte[] encode() {
        Wire.Writer out = new Wire.Writer().varInt(FORMAT);
        writeRules(out, rules);
        otherwise.write(out);
        writeRules(out, also);
        return out.bytes();
    }

    private static void writeRules(Wire.Writer out, List<Rule> rules) {
        out.varInt(rules.size());
        for (Rule rule : rules) {
            out.varInt(rule.conditions().size());
            for (Condition c : rule.conditions()) c.write(out);
            out.varInt(rule.actions().size());
            for (Action a : rule.actions()) a.write(out);
        }
    }

    /** Reads format 2, and format 1 (v1 blocks and items), which becomes the same output rules with no "also" rules. */
    public static Program decode(byte[] data) {
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Program too large");
        Wire.Reader in = new Wire.Reader(data);
        int format = in.varInt();
        Program p = switch (format) {
            case 1 -> {
                List<Rule> rules = readRules(in, MAX_RULES, true);
                yield new Program(rules, Action.readV1(in));
            }
            case 2 -> {
                List<Rule> rules = readRules(in, MAX_RULES, false);
                Action otherwise = Action.read(in);
                yield new Program(rules, otherwise, readRules(in, MAX_ALSO, false));
            }
            default -> throw new IllegalArgumentException("Unknown program format " + format + " (a newer Logic Blocks?)");
        };
        in.end();
        return p;
    }

    private static List<Rule> readRules(Wire.Reader in, int max, boolean v1) {
        int n = in.varInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("Too many rules");
        List<Rule> rules = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int m = in.varInt();
            if (m < 0 || m > MAX_CONDITIONS) throw new IllegalArgumentException("Too many conditions");
            List<Condition> conditions = new ArrayList<>(m);
            for (int j = 0; j < m; j++) conditions.add(Condition.read(in));
            if (v1) { rules.add(new Rule(conditions, Action.readV1(in))); continue; }
            int k = in.varInt();
            if (k < 1 || k > MAX_ACTIONS) throw new IllegalArgumentException("Bad action count");
            List<Action> actions = new ArrayList<>(k);
            for (int j = 0; j < k; j++) actions.add(Action.read(in));
            rules.add(new Rule(conditions, actions));
        }
        return rules;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) lines.add((i + 1) + ". " + rules.get(i).describe());
        lines.add("otherwise -> " + otherwise.describe());
        for (int i = 0; i < also.size(); i++) lines.add("also " + (i + 1) + ". " + also.get(i).describe());
        return lines;
    }
}
