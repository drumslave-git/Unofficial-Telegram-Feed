package org.unofficial.telegramfeed.core;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a post is read aloud as: links become a word for "link", mentions, emoji and
 * formatting marks go, whitespace collapses, a long post is cut at a sentence end or a word and
 * marked as cut, and an introduction naming the channel goes first. The words it adds are given
 * by the caller in the language of the voice. No Android dependencies.
 */
public final class SpeechText {

    public static final int DEFAULT_MAX_CHARS = 600;

    private static final Pattern URL = Pattern.compile("(https?://|www\\.)[^\\s<>()]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_])@[A-Za-z0-9_]{3,}");
    private static final Pattern MARKERS = Pattern.compile("[*_~`#>|]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]\\s");

    private SpeechText() {
    }

    /**
     * The text to hand to the speech engine, or an empty string when nothing is worth reading.
     *
     * @param intro the introduction for a channel title, such as "New post in %s."; null for none
     */
    public static String prepare(String text, String channelTitle, int maxChars, String linkWord, String moreSuffix, Function<String, String> intro) {
        if (text == null) {
            return "";
        }
        String t = URL.matcher(text).replaceAll(Matcher.quoteReplacement(" " + linkWord + " "));
        t = MENTION.matcher(t).replaceAll(" ");
        t = withoutEmoji(t);
        t = MARKERS.matcher(t).replaceAll(" ");
        t = SPACE.matcher(t).replaceAll(" ").trim();
        if (t.isEmpty()) {
            return "";
        }
        if (t.length() > maxChars) {
            int cut = -1;
            Matcher m = SENTENCE_END.matcher(t);
            while (m.find() && m.start() <= maxChars) {
                cut = m.start();
            }
            if (cut < maxChars / 2) {
                cut = t.lastIndexOf(' ', maxChars);
            }
            if (cut < maxChars / 2) {
                cut = maxChars;
            }
            t = stripEnd(t.substring(0, Math.min(cut + 1, t.length()))) + moreSuffix;
        }
        if (channelTitle != null && intro != null) {
            String title = SPACE.matcher(withoutEmoji(channelTitle)).replaceAll(" ").trim();
            if (!title.isEmpty()) {
                return intro.apply(title) + " " + t;
            }
        }
        return t;
    }

    /** Replaces every emoji, skin-tone modifier, joiner and variation selector with a space. */
    static String withoutEmoji(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out.append(isEmoji(cp) ? " " : new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isEmoji(int cp) {
        return cp >= 0x1F000 && cp <= 0x1FAFF // pictographs, emoticons, transport, symbols, flags, modifiers
                || cp >= 0x2600 && cp <= 0x27BF // miscellaneous symbols and dingbats
                || cp >= 0x2300 && cp <= 0x23FF // technical symbols such as watches and hourglasses
                || cp >= 0x2B00 && cp <= 0x2BFF // arrows and stars
                || cp >= 0x2190 && cp <= 0x21FF // arrows
                || cp >= 0xE0020 && cp <= 0xE007F // tag characters of flags
                || cp == 0x200D || cp == 0xFE0F || cp == 0x20E3
                || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
                || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
    }

    private static String stripEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
