package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The visual builder's shape of a condition: groups joined by OR, each group an AND of terms,
 * each term optionally negated. Any expression of that shape converts both ways without loss;
 * deeper nesting is edited as text only ({@link #fromExpr} returns null). No Android dependencies.
 */
public final class RuleBuilder {

    /** One word or phrase of the builder. */
    public static final class BuilderTerm {
        public String text;
        public boolean wholeWord = true;
        public boolean caseSensitive;
        public boolean negated;

        public BuilderTerm() {
            text = "";
        }

        public BuilderTerm(String text, boolean wholeWord, boolean caseSensitive, boolean negated) {
            this.text = text;
            this.wholeWord = wholeWord;
            this.caseSensitive = caseSensitive;
            this.negated = negated;
        }

        public BuilderTerm(BuilderTerm other) {
            this(other.text, other.wholeWord, other.caseSensitive, other.negated);
        }

        public boolean isBlank() {
            return text == null || text.trim().isEmpty();
        }

        RuleExpr toExpr() {
            RuleExpr.Term term = new RuleExpr.Term(text.trim(), wholeWord, caseSensitive);
            return negated ? new RuleExpr.Not(term) : term;
        }

        static BuilderTerm fromExpr(RuleExpr e) {
            if (e instanceof RuleExpr.Term) {
                RuleExpr.Term t = (RuleExpr.Term) e;
                return new BuilderTerm(t.text, t.wholeWord, t.caseSensitive, false);
            }
            if (e instanceof RuleExpr.Not && ((RuleExpr.Not) e).inner instanceof RuleExpr.Term) {
                RuleExpr.Term t = (RuleExpr.Term) ((RuleExpr.Not) e).inner;
                return new BuilderTerm(t.text, t.wholeWord, t.caseSensitive, true);
            }
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BuilderTerm)) return false;
            BuilderTerm t = (BuilderTerm) o;
            return wholeWord == t.wholeWord && caseSensitive == t.caseSensitive && negated == t.negated && Objects.equals(text, t.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, wholeWord, caseSensitive, negated);
        }
    }

    /** OR of groups; each group is an AND of terms. Empty for a rule with no condition. */
    public final List<List<BuilderTerm>> groups = new ArrayList<>();

    public RuleBuilder() {
    }

    public RuleBuilder(RuleBuilder other) {
        for (List<BuilderTerm> group : other.groups) {
            List<BuilderTerm> copy = new ArrayList<>();
            for (BuilderTerm t : group) copy.add(new BuilderTerm(t));
            groups.add(copy);
        }
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** True when every group has terms and every term has a word. */
    public boolean isValid() {
        if (groups.isEmpty()) return false;
        for (List<BuilderTerm> g : groups) {
            if (g.isEmpty()) return false;
            for (BuilderTerm t : g) {
                if (t.isBlank()) return false;
            }
        }
        return true;
    }

    /** The same groups without terms that have no word yet and without groups left empty by that. */
    public RuleBuilder withoutBlankTerms() {
        RuleBuilder out = new RuleBuilder();
        for (List<BuilderTerm> g : groups) {
            List<BuilderTerm> kept = new ArrayList<>();
            for (BuilderTerm t : g) {
                if (!t.isBlank()) kept.add(new BuilderTerm(t));
            }
            if (!kept.isEmpty()) out.groups.add(kept);
        }
        return out;
    }

    /** The expression of a valid builder; null for an empty one. */
    public RuleExpr toExpr() {
        if (groups.isEmpty()) return null;
        List<RuleExpr> ands = new ArrayList<>();
        for (List<BuilderTerm> g : groups) {
            if (g.size() == 1) {
                ands.add(g.get(0).toExpr());
            } else {
                List<RuleExpr> items = new ArrayList<>();
                for (BuilderTerm t : g) items.add(t.toExpr());
                ands.add(new RuleExpr.And(items));
            }
        }
        return ands.size() == 1 ? ands.get(0) : new RuleExpr.Or(ands);
    }

    /** The condition in the rule syntax; empty for an empty builder. Blank terms are left out. */
    public String toText() {
        RuleExpr e = withoutBlankTerms().toExpr();
        return e == null ? "" : RuleParser.format(e);
    }

    /** The builder of an expression, or null when it does not fit the builder's shape. */
    public static RuleBuilder fromExpr(RuleExpr e) {
        RuleBuilder out = new RuleBuilder();
        if (e == null) return out;
        List<RuleExpr> alternatives = new ArrayList<>();
        if (e instanceof RuleExpr.Or) {
            alternatives.addAll(((RuleExpr.Or) e).items);
        } else {
            alternatives.add(e);
        }
        for (RuleExpr a : alternatives) {
            List<RuleExpr> items = new ArrayList<>();
            if (a instanceof RuleExpr.And) {
                items.addAll(((RuleExpr.And) a).items);
            } else {
                items.add(a);
            }
            List<BuilderTerm> group = new ArrayList<>();
            for (RuleExpr i : items) {
                BuilderTerm t = BuilderTerm.fromExpr(i);
                if (t == null) return null;
                group.add(t);
            }
            out.groups.add(group);
        }
        return out;
    }

    /** The builder of a condition's text: empty for no text, null for text that does not parse or does not fit. */
    public static RuleBuilder fromText(String text) {
        if (text == null || text.trim().isEmpty()) return new RuleBuilder();
        try {
            return fromExpr(RuleParser.parse(text.trim()));
        } catch (RuleParser.SyntaxException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuleBuilder && groups.equals(((RuleBuilder) o).groups);
    }

    @Override
    public int hashCode() {
        return groups.hashCode();
    }
}
