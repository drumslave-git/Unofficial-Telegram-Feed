package org.unofficial.telegramfeed.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Matches conditions against plain post text (text or caption, nothing else). A word
 * character is any letter, digit or underscore in any script, so a whole-word term stops at
 * punctuation and spaces but not inside a Cyrillic or CJK word.
 */
public final class RuleEvaluator {

    private final Map<RuleExpr.Term, Pattern> cache = new HashMap<>();

    public boolean matches(RuleExpr expr, String text) {
        if (expr instanceof RuleExpr.And) {
            for (RuleExpr i : ((RuleExpr.And) expr).items) {
                if (!matches(i, text)) return false;
            }
            return true;
        }
        if (expr instanceof RuleExpr.Or) {
            for (RuleExpr i : ((RuleExpr.Or) expr).items) {
                if (matches(i, text)) return true;
            }
            return false;
        }
        if (expr instanceof RuleExpr.Not) {
            return !matches(((RuleExpr.Not) expr).inner, text);
        }
        return pattern((RuleExpr.Term) expr).matcher(text).find();
    }

    private Pattern pattern(RuleExpr.Term t) {
        Pattern p = cache.get(t);
        if (p == null) {
            String[] words = t.text.trim().split("\\s+");
            StringBuilder body = new StringBuilder();
            for (String w : words) {
                if (body.length() > 0) body.append("\\s+");
                body.append(Pattern.quote(w));
            }
            String regex = t.wholeWord ? "(?<![\\p{L}\\p{N}_])" + body + "(?![\\p{L}\\p{N}_])" : body.toString();
            int flags = t.caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            p = Pattern.compile(regex, flags);
            cache.put(t, p);
        }
        return p;
    }
}
