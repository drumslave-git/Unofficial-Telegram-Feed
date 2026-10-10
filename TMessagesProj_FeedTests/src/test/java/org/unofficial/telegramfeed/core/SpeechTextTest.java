package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

public class SpeechTextTest {

    private static final Function<String, String> INTRO = c -> "New post in " + c + ".";

    private static String prepare(String text) {
        return SpeechText.prepare(text, null, SpeechText.DEFAULT_MAX_CHARS, "link", "… and more", INTRO);
    }

    @Test
    public void linksBecomeAWordMentionsAndEmojiGoWhitespaceCollapses() {
        assertEquals("Big news see link and link now",
                prepare("Big news 🚀🚀 see https://example.com/a?b=1 and www.x.org now\n\n @someone"));
    }

    @Test
    public void formattingMarkersGo() {
        assertEquals("Bold it code quote tag", prepare("**Bold** _it_ `code` > quote #tag"));
    }

    @Test
    public void channelIntroductionAndEmptyResults() {
        assertEquals("New post in News. hello", SpeechText.prepare("hello", "News 📰", 600, "link", "… and more", INTRO));
        assertEquals("", SpeechText.prepare("🚀🚀", "x", 600, "link", "… and more", INTRO));
        assertEquals("", prepare("   "));
        assertEquals("", prepare(null));
        assertEquals("Новий допис у каналі Новини. так",
                SpeechText.prepare("так", "Новини", 600, "посилання", "… і далі", c -> "Новий допис у каналі " + c + "."));
    }

    @Test
    public void cutPrefersASentenceEndThenAWord() {
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sentences.add("Sentence number " + i + " is here.");
        }
        String out = SpeechText.prepare(String.join(" ", sentences), null, 100, "link", "… and more", INTRO);
        assertTrue(out.length() <= 100 + "… and more".length() + 1);
        assertTrue(out, out.endsWith(".… and more"));
        String words = String.join(" ", Collections.nCopies(50, "word"));
        assertEquals("word word word word word word… and more", SpeechText.prepare(words, null, 30, "link", "… and more", INTRO));
    }

    @Test
    public void cyrillicAndCjkStay() {
        assertEquals("Курс рубля вырос", prepare("Курс рубля 📉 вырос"));
        assertEquals("比特币涨了", prepare("比特币涨了"));
        assertEquals("Ukraine", prepare("🇺🇦 Ukraine 👍🏽"));
    }
}
