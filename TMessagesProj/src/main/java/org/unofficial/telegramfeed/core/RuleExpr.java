package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A rule condition: {@code And(items) | Or(items) | Not(inner) | Term}. A term is a word or
 * a phrase (several words match across any whitespace run), whole-word and case-insensitive
 * unless told otherwise. The text form is {@link RuleParser}'s. No Android dependencies.
 */
public abstract class RuleExpr {

    private RuleExpr() {
    }

    public static final class And extends RuleExpr {
        public final List<RuleExpr> items;

        public And(List<RuleExpr> items) {
            this.items = new ArrayList<>(items);
        }

        public And(RuleExpr... items) {
            this(Arrays.asList(items));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof And && items.equals(((And) o).items);
        }

        @Override
        public int hashCode() {
            return Objects.hash("and", items);
        }
    }

    public static final class Or extends RuleExpr {
        public final List<RuleExpr> items;

        public Or(List<RuleExpr> items) {
            this.items = new ArrayList<>(items);
        }

        public Or(RuleExpr... items) {
            this(Arrays.asList(items));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Or && items.equals(((Or) o).items);
        }

        @Override
        public int hashCode() {
            return Objects.hash("or", items);
        }
    }

    public static final class Not extends RuleExpr {
        public final RuleExpr inner;

        public Not(RuleExpr inner) {
            this.inner = inner;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Not && inner.equals(((Not) o).inner);
        }

        @Override
        public int hashCode() {
            return Objects.hash("not", inner);
        }
    }

    public static final class Term extends RuleExpr {
        public final String text;
        public final boolean wholeWord;
        public final boolean caseSensitive;

        public Term(String text) {
            this(text, true, false);
        }

        public Term(String text, boolean wholeWord, boolean caseSensitive) {
            this.text = text;
            this.wholeWord = wholeWord;
            this.caseSensitive = caseSensitive;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Term)) return false;
            Term t = (Term) o;
            return text.equals(t.text) && wholeWord == t.wholeWord && caseSensitive == t.caseSensitive;
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, wholeWord, caseSensitive);
        }
    }

    @Override
    public String toString() {
        return RuleParser.format(this);
    }
}
