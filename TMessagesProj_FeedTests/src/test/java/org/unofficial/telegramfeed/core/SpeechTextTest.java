package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
    public void styledLettersBecomePlain() {
        assertEquals("Students Sex Campus link", prepare("𝐒𝐭𝐮𝐝𝐞𝐧𝐭𝐬 𝐒𝐞𝐱 𝐂𝐚𝐦𝐩𝐮𝐬 https://t.me/+abc"));
        assertEquals("News: Київ сьогодні", prepare("𝐍𝐞𝐰𝐬: Київ сьогодні"));
        assertEquals("New post in Retro. hi", SpeechText.prepare("hi", "𝓡𝓮𝓽𝓻𝓸", 600, "link", "… and more", INTRO));
        assertEquals("Hello", SpeechText.plain("Ｈｅｌｌｏ"));
        assertEquals("only for the brave 21+ link", prepare("🍑 ᴏɴʟʏ ꜰᴏʀ ᴛʜᴇ ʙʀᴀᴠᴇ 𝟤𝟣+  🍑 https://t.me/+abc"));
        assertEquals("abcdefghijklmnopqrstuvwyz", SpeechText.plain("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘꞯʀꜱᴛᴜᴠᴡʏᴢ"));
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

    private static final String SIGNATURE = "\n\n👉 Kyiv News | Subscribe\nSend us news";

    @Test
    public void theSignatureMostOtherPostsEndWithGoes() {
        List<String> others = Arrays.asList("Rain tomorrow." + SIGNATURE, "The bridge is closed.\nUse the tunnel." + SIGNATURE,
                "", "A forwarded post without it", "Metro runs again.\n\n⚡ kyiv news | subscribe \n**Send us news**\n");
        assertEquals("Power is back.\nIn every district.", SpeechText.withoutSignature("Power is back.\nIn every district." + SIGNATURE, others));
        assertEquals("Power is back.", SpeechText.withoutSignature("Power is back.\n\nSend us news", others));
        assertEquals("No signature here.\nAt all.", SpeechText.withoutSignature("No signature here.\nAt all.", others));
        assertEquals("One line", SpeechText.withoutSignature("One line", others));
        assertEquals(null, SpeechText.withoutSignature(null, others));
        assertEquals("a\nb", SpeechText.withoutSignature("a\nb", null));
    }

    @Test
    public void aSignatureNeedsThreePostsAndThreeInFive() {
        String post = "Power is back." + SIGNATURE;
        assertEquals(post, SpeechText.withoutSignature(post, Arrays.asList("Rain." + SIGNATURE, "Snow." + SIGNATURE)));
        assertEquals(post, SpeechText.withoutSignature(post, Arrays.asList("Rain." + SIGNATURE, "Snow." + SIGNATURE, "Fog." + SIGNATURE,
                "one", "two", "three")));
        assertEquals("Power is back.", SpeechText.withoutSignature(post, Arrays.asList("Rain." + SIGNATURE, "Snow." + SIGNATURE, "Fog." + SIGNATURE,
                "one", "two")));
    }

    @Test
    public void aPostRepeatedWholeIsStillRead() {
        String alert = "🔴 Air raid alert!\nGo to the shelter." + SIGNATURE;
        String clear = "🟢 All clear." + SIGNATURE;
        List<String> others = Arrays.asList(alert, clear, alert, clear, alert, clear);
        assertEquals("🔴 Air raid alert!\nGo to the shelter.", SpeechText.withoutSignature(alert, others));
        String bare = "Air raid alert!\nGo to the shelter.";
        assertEquals("Air raid alert!", SpeechText.withoutSignature(bare, Arrays.asList(bare, bare, bare)));
        assertEquals("Subscribe", SpeechText.withoutSignature("Subscribe", Arrays.asList("a\nSubscribe", "b\nSubscribe", "c\nSubscribe")));
    }

    @Test
    public void cyrillicAndCjkStay() {
        assertEquals("Курс рубля вырос", prepare("Курс рубля 📉 вырос"));
        assertEquals("比特币涨了", prepare("比特币涨了"));
        assertEquals("Ukraine", prepare("🇺🇦 Ukraine 👍🏽"));
    }
}
