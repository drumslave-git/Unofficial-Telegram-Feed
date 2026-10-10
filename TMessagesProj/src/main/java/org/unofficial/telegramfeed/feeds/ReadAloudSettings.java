package org.unofficial.telegramfeed.feeds;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.unofficial.telegramfeed.core.SpeechText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The read-aloud settings, device-wide in the preferences file {@code tgfeed_read_aloud_settings}:
 * speed, pitch, maximum length, the language for posts whose language is unknown, and a chosen
 * voice per language. A language without a chosen voice is read with the engine's default voice
 * for it. The speech controller reads them before every post, so changes apply to the next one.
 */
public final class ReadAloudSettings {

    public static final float[] RATES = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    public static final float[] PITCHES = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    public static final int[] MAX_LENGTHS = {300, 600, 1200, 3000};

    private static final String VOICE_PREFIX = "voice_";

    private ReadAloudSettings() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgfeed_read_aloud_settings", Context.MODE_PRIVATE);
    }

    public static float getRate() {
        return preferences().getFloat("rate", 1f);
    }

    public static void setRate(float rate) {
        preferences().edit().putFloat("rate", rate).apply();
    }

    public static float getPitch() {
        return preferences().getFloat("pitch", 1f);
    }

    public static void setPitch(float pitch) {
        preferences().edit().putFloat("pitch", pitch).apply();
    }

    public static int getMaxChars() {
        return preferences().getInt("maxChars", SpeechText.DEFAULT_MAX_CHARS);
    }

    public static void setMaxChars(int maxChars) {
        preferences().edit().putInt("maxChars", maxChars).apply();
    }

    /** The language code for posts whose language is unknown; empty for the interface language. */
    public static String getFallbackLanguage() {
        return preferences().getString("fallbackLanguage", "");
    }

    public static void setFallbackLanguage(String language) {
        preferences().edit().putString("fallbackLanguage", language == null ? "" : language).apply();
    }

    /** The chosen voice's name for a language code, or null for the engine's default voice. */
    public static String getVoice(String language) {
        return preferences().getString(VOICE_PREFIX + language, null);
    }

    /** Chooses a voice for a language; null goes back to the engine's default voice. */
    public static void setVoice(String language, String voiceName) {
        SharedPreferences.Editor editor = preferences().edit();
        if (voiceName == null) {
            editor.remove(VOICE_PREFIX + language);
        } else {
            editor.putString(VOICE_PREFIX + language, voiceName);
        }
        editor.apply();
    }

    /** The languages with a chosen voice, sorted by code. */
    public static List<String> getVoiceLanguages() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : preferences().getAll().entrySet()) {
            if (e.getKey().startsWith(VOICE_PREFIX)) {
                out.add(e.getKey().substring(VOICE_PREFIX.length()));
            }
        }
        Collections.sort(out);
        return out;
    }
}
