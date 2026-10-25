package dev.whizzle.logic.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A Logic Block's rules: the first rule whose conditions all hold decides the output; otherwise {@code otherwise}. */
public record Program(List<Rule> rules, Action otherwise) {
    public static final int FORMAT = 1, MAX_RULES = 8, MAX_CONDITIONS = 3, MAX_BYTES = 8192;

    public record Rule(List<Condition> conditions, Action action) {
        public Rule { conditions = List.copyOf(conditions); Objects.requireNonNull(action); }
        public String describe() {
            StringBuilder sb = new StringBuilder(conditions.isEmpty() ? "always" : "when ");
            for (int i = 0; i < conditions.size(); i++) sb.append(i == 0 ? "" : " and ").append(conditions.get(i).describe());
            return sb.append(" → ").append(action.describe()).toString();
        }
    }

    public static final Program EMPTY = new Program(List.of(), Action.set(0));

    public Program { rules = List.copyOf(rules); Objects.requireNonNull(otherwise); }

    /** Null when the program can run, otherwise the first problem, naming the rule. */
    public String problem() {
        if (rules.size() > MAX_RULES) return "At most " + MAX_RULES + " rules";
        for (int r = 0; r < rules.size(); r++) {
            Rule rule = rules.get(r);
            if (rule.conditions().size() > MAX_CONDITIONS) return "Rule " + (r + 1) + ": at most " + MAX_CONDITIONS + " conditions";
            for (Condition c : rule.conditions()) {
                String p = c.problem();
                if (p != null) return "Rule " + (r + 1) + ": " + p;
            }
            String p = rule.action().problem();
            if (p != null) return "Rule " + (r + 1) + ": " + p;
        }
        if (otherwise.kind() == Action.Kind.PULSE || otherwise.kind() == Action.Kind.TOGGLE) return "Otherwise can only set an output or keep it";
        String p = otherwise.problem();
        return p == null ? null : "Otherwise: " + p;
    }

    public byte[] encode() {
        Wire.Writer out = new Wire.Writer().varInt(FORMAT).varInt(rules.size());
        for (Rule rule : rules) {
            out.varInt(rule.conditions().size());
            for (Condition c : rule.conditions()) c.write(out);
            rule.action().write(out);
        }
        otherwise.write(out);
        return out.bytes();
    }

    public static Program decode(byte[] data) {
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Program too large");
        Wire.Reader in = new Wire.Reader(data);
        int format = in.varInt();
        if (format != FORMAT) throw new IllegalArgumentException("Unknown program format " + format);
        int n = in.varInt();
        if (n < 0 || n > MAX_RULES) throw new IllegalArgumentException("Too many rules");
        List<Rule> rules = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int m = in.varInt();
            if (m < 0 || m > MAX_CONDITIONS) throw new IllegalArgumentException("Too many conditions");
            List<Condition> conditions = new ArrayList<>(m);
            for (int j = 0; j < m; j++) conditions.add(Condition.read(in));
            rules.add(new Rule(conditions, Action.read(in)));
        }
        Action otherwise = Action.read(in);
        in.end();
        return new Program(rules, otherwise);
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) lines.add((i + 1) + ". " + rules.get(i).describe());
        lines.add("otherwise → " + otherwise.describe());
        return lines;
    }
}
