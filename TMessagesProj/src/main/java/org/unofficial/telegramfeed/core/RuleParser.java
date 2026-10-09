package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The text form of a condition, parsed to the same tree the visual builder produces:
 *
 * <pre>("bitcoin" OR btc) AND NOT airdrop</pre>
 *
 * Terms are bare words or "quoted phrases", whole-word and case-insensitive by default.
 * A {@code ~} prefix makes a term a substring match, {@code =} makes it case-sensitive
 * ({@code ~=} both). {@code AND}, {@code OR}, {@code NOT} in any case and parentheses;
 * AND binds tighter than OR, NOT tightest. Inside quotes {@code \"} and {@code \\} are escapes.
 */
public final class RuleParser {

    /** What is wrong with a condition's text, for a message in the reader's language. */
    public enum Problem {
        UNEXPECTED, EXPECTED_TERM, EXPECTED_CLOSING_PAREN, UNTERMINATED_QUOTE, DANGLING_ESCAPE, EMPTY_TERM, KEYWORD
    }

    public static final class SyntaxException extends Exception {
        public final Problem problem;
        /** The character or word the problem is about; empty for the others. */
        public final String detail;
        /** The 1-based position in the text. */
        public final int position;

        SyntaxException(Problem problem, String message, int offset, String detail) {
            super("rule syntax: " + message + " at " + (offset + 1));
            this.problem = problem;
            this.detail = detail;
            this.position = offset + 1;
        }
    }

    private static final String[] KEYWORDS = {"AND", "OR", "NOT"};
    private static final Pattern BARE = Pattern.compile("^[^\\s()\"~=\\\\]+$");

    private final String src;
    private int pos;

    private RuleParser(String src) {
        this.src = src;
    }

    public static RuleExpr parse(String source) throws SyntaxException {
        RuleParser p = new RuleParser(source);
        RuleExpr e = p.or();
        p.skipWs();
        if (!p.atEnd()) {
            String c = String.valueOf(p.src.charAt(p.pos));
            throw p.fail(Problem.UNEXPECTED, "unexpected \"" + c + "\"", c);
        }
        return e;
    }

    /** Renders an expression back to the text form; {@link #parse} gives the same tree again. */
    public static String format(RuleExpr e) {
        if (e instanceof RuleExpr.Or) {
            StringBuilder b = new StringBuilder();
            for (RuleExpr i : ((RuleExpr.Or) e).items) {
                if (b.length() > 0) b.append(" OR ");
                b.append(format(i));
            }
            return b.toString();
        }
        if (e instanceof RuleExpr.And) {
            StringBuilder b = new StringBuilder();
            for (RuleExpr i : ((RuleExpr.And) e).items) {
                if (b.length() > 0) b.append(" AND ");
                b.append(i instanceof RuleExpr.Or ? "(" + format(i) + ")" : format(i));
            }
            return b.toString();
        }
        if (e instanceof RuleExpr.Not) {
            RuleExpr inner = ((RuleExpr.Not) e).inner;
            return "NOT " + (inner instanceof RuleExpr.Term ? format(inner) : "(" + format(inner) + ")");
        }
        RuleExpr.Term t = (RuleExpr.Term) e;
        return (t.wholeWord ? "" : "~") + (t.caseSensitive ? "=" : "") + quote(t.text);
    }

    private static String quote(String text) {
        boolean bare = BARE.matcher(text).matches() && !isKeyword(text);
        if (bare) return text;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isKeyword(String text) {
        for (String k : KEYWORDS) {
            if (k.equalsIgnoreCase(text)) return true;
        }
        return false;
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private SyntaxException fail(Problem problem, String message, String detail) {
        return new SyntaxException(problem, message, pos, detail);
    }

    private void skipWs() {
        while (!atEnd() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean keyword(String kw) {
        skipWs();
        int end = pos + kw.length();
        if (end > src.length()) return false;
        if (!src.substring(pos, end).equalsIgnoreCase(kw)) return false;
        if (end < src.length() && isWordChar(src.charAt(end))) return false;
        pos = end;
        return true;
    }

    private static boolean isWordChar(char c) {
        return !(Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == '~' || c == '=' || c == '\\');
    }

    private RuleExpr or() throws SyntaxException {
        List<RuleExpr> items = new ArrayList<>();
        items.add(and());
        while (keyword("OR")) {
            items.add(and());
        }
        return items.size() == 1 ? items.get(0) : new RuleExpr.Or(items);
    }

    private RuleExpr and() throws SyntaxException {
        List<RuleExpr> items = new ArrayList<>();
        items.add(not());
        while (keyword("AND")) {
            items.add(not());
        }
        return items.size() == 1 ? items.get(0) : new RuleExpr.And(items);
    }

    private RuleExpr not() throws SyntaxException {
        if (keyword("NOT")) return new RuleExpr.Not(not());
        return primary();
    }

    private RuleExpr primary() throws SyntaxException {
        skipWs();
        if (atEnd()) throw fail(Problem.EXPECTED_TERM, "expected a term", "");
        if (src.charAt(pos) == '(') {
            pos++;
            RuleExpr e = or();
            skipWs();
            if (atEnd() || src.charAt(pos) != ')') {
                throw fail(Problem.EXPECTED_CLOSING_PAREN, "expected \")\"", "");
            }
            pos++;
            return e;
        }
        boolean wholeWord = true;
        boolean caseSensitive = false;
        while (!atEnd() && (src.charAt(pos) == '~' || src.charAt(pos) == '=')) {
            if (src.charAt(pos) == '~') wholeWord = false;
            if (src.charAt(pos) == '=') caseSensitive = true;
            pos++;
        }
        if (atEnd()) throw fail(Problem.EXPECTED_TERM, "expected a term", "");
        String text = src.charAt(pos) == '"' ? quoted() : bare();
        return new RuleExpr.Term(text, wholeWord, caseSensitive);
    }

    private String quoted() throws SyntaxException {
        pos++;
        StringBuilder buf = new StringBuilder();
        while (true) {
            if (atEnd()) throw fail(Problem.UNTERMINATED_QUOTE, "unterminated quote", "");
            char c = src.charAt(pos++);
            if (c == '\\') {
                if (atEnd()) throw fail(Problem.DANGLING_ESCAPE, "dangling escape", "");
                buf.append(src.charAt(pos++));
            } else if (c == '"') {
                break;
            } else {
                buf.append(c);
            }
        }
        String text = buf.toString().trim();
        if (text.isEmpty()) throw fail(Problem.EMPTY_TERM, "empty term", "");
        return text;
    }

    private String bare() throws SyntaxException {
        int start = pos;
        while (!atEnd() && isWordChar(src.charAt(pos))) {
            pos++;
        }
        String text = src.substring(start, pos);
        if (text.isEmpty()) throw fail(Problem.EXPECTED_TERM, "expected a term", "");
        if (isKeyword(text)) {
            throw fail(Problem.KEYWORD, "\"" + text + "\" is a keyword; quote it to match the word", text);
        }
        return text;
    }
}
