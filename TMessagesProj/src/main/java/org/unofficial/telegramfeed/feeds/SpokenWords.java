package org.unofficial.telegramfeed.feeds;

import android.content.Context;

import androidx.annotation.StringRes;

import org.telegram.localization.LocalizationUtils;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.utils.localization.Localization;

import java.util.HashMap;
import java.util.Locale;

/**
 * The fork's strings in a language other than the interface's, for the words read aloud. Release
 * builds keep no string resources (Telegram's build packs them into assets and the shrinker drops
 * them), so a string is read from the packed asset of its language, as {@link LocaleController}
 * reads the interface's.
 */
public final class SpokenWords {

    private static final HashMap<String, Localization> byLanguage = new HashMap<>();

    private SpokenWords() {
    }

    public static String get(String language, @StringRes int res, Object... args) {
        String value = null;
        Locale current = LocaleController.getInstance().getCurrentLocale();
        if (current == null || !language.equals(current.getLanguage())) {
            Context context = ApplicationLoader.applicationContext;
            value = localization(context, language).getByResId(context, res);
        }
        if (value == null) {
            value = LocaleController.getString(res);
        }
        return args.length == 0 ? value : String.format(Locale.forLanguageTag(language), value, args);
    }

    private static Localization localization(Context context, String language) {
        synchronized (byLanguage) {
            Localization localization = byLanguage.get(language);
            if (localization == null) {
                Localization.Builder builder = new Localization.Builder().addResLocalization(context, LocalizationUtils.DEFAULT_LOCALIZATION);
                String asset = LocalizationUtils.getLocalizationAsset(Locale.forLanguageTag(language));
                if (asset != null && !asset.equals(LocalizationUtils.DEFAULT_LOCALIZATION)) {
                    builder.addResLocalization(context, asset);
                }
                localization = builder.build();
                byLanguage.put(language, localization);
            }
            return localization;
        }
    }
}
