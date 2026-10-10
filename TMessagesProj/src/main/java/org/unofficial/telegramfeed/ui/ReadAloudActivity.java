package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.feeds.ReadAloudController;
import org.unofficial.telegramfeed.feeds.ReadAloudSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The "Read aloud" settings: speed, pitch, maximum length, the language for posts whose language
 * is unknown, a preview, and a chosen voice per language, each heard before it is chosen. Every
 * other language is read with the phone's default voice for it.
 */
public class ReadAloudActivity extends BaseFragment {

    private static final int T_RATE = 0;
    private static final int T_PITCH = 1;
    private static final int T_MAX = 2;
    private static final int T_FALLBACK = 3;
    private static final int T_PREVIEW = 4;
    private static final int T_INFO = 5;
    private static final int T_SHADOW = 6;
    private static final int T_HEADER = 7;
    private static final int T_VOICE = 8;
    private static final int T_ADD = 9;

    private static final class Row {
        final int type;
        final String language;
        final CharSequence text;

        Row(int type, String language, CharSequence text) {
            this.type = type;
            this.language = language;
            this.text = text;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<Voice> voices = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        tts = new TextToSpeech(ApplicationLoader.applicationContext, status -> AndroidUtilities.runOnUIThread(() -> {
            if (tts == null) {
                return;
            }
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                try {
                    for (Voice voice : tts.getVoices()) {
                        if (!voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                            voices.add(voice);
                        }
                    }
                } catch (Exception ignore) {
                }
                Collections.sort(voices, (a, b) -> a.getName().compareTo(b.getName()));
            }
            updateRows();
        }));
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.TgfeedReadAloudTitle));
        actionBar.createMenu().addItem(1, R.drawable.msg_voice_speaker).setContentDescription(LocaleController.getString(R.string.TgfeedPreview));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    preview(ReadAloudController.interfaceLanguage(), null, false);
                }
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setItemAnimator(null);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                onRowClick(rows.get(position));
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateRows();
        return fragmentView;
    }

    // ---------------------------------------------------------------- rows

    private void updateRows() {
        rows.clear();
        rows.add(new Row(T_RATE, null, null));
        rows.add(new Row(T_PITCH, null, null));
        rows.add(new Row(T_MAX, null, null));
        rows.add(new Row(T_INFO, null, LocaleController.formatPluralString("TgfeedMaxLengthInfo", ReadAloudSettings.getMaxChars())));
        rows.add(new Row(T_FALLBACK, null, null));
        rows.add(new Row(T_INFO, null, LocaleController.getString(R.string.TgfeedFallbackLanguageInfo)));
        rows.add(new Row(T_HEADER, null, LocaleController.getString(R.string.TgfeedVoices)));
        if (ttsReady && voices.isEmpty()) {
            rows.add(new Row(T_INFO, null, LocaleController.getString(R.string.TgfeedNoVoices)));
        } else {
            for (String language : ReadAloudSettings.getVoiceLanguages()) {
                rows.add(new Row(T_VOICE, language, null));
            }
            rows.add(new Row(T_ADD, null, null));
            rows.add(new Row(T_INFO, null, LocaleController.getString(R.string.TgfeedOtherLanguages)));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(Row row) {
        switch (row.type) {
            case T_RATE: {
                int selected = indexOf(ReadAloudSettings.RATES, ReadAloudSettings.getRate());
                CharSequence[] labels = new CharSequence[ReadAloudSettings.RATES.length];
                for (int i = 0; i < labels.length; i++) labels[i] = number(ReadAloudSettings.RATES[i]) + "×";
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedSpeed), labels, selected, i -> {
                    ReadAloudSettings.setRate(ReadAloudSettings.RATES[i]);
                    updateRows();
                });
                break;
            }
            case T_PITCH: {
                int selected = indexOf(ReadAloudSettings.PITCHES, ReadAloudSettings.getPitch());
                CharSequence[] labels = new CharSequence[ReadAloudSettings.PITCHES.length];
                for (int i = 0; i < labels.length; i++) labels[i] = number(ReadAloudSettings.PITCHES[i]);
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedPitch), labels, selected, i -> {
                    ReadAloudSettings.setPitch(ReadAloudSettings.PITCHES[i]);
                    updateRows();
                });
                break;
            }
            case T_MAX: {
                int selected = -1;
                CharSequence[] labels = new CharSequence[ReadAloudSettings.MAX_LENGTHS.length];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = String.valueOf(ReadAloudSettings.MAX_LENGTHS[i]);
                    if (ReadAloudSettings.MAX_LENGTHS[i] == ReadAloudSettings.getMaxChars()) selected = i;
                }
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedMaxLength), labels, selected, i -> {
                    ReadAloudSettings.setMaxChars(ReadAloudSettings.MAX_LENGTHS[i]);
                    updateRows();
                });
                break;
            }
            case T_FALLBACK: {
                List<String> languages = new ArrayList<>(voiceLanguages());
                languages.add(0, "");
                CharSequence[] labels = new CharSequence[languages.size()];
                int selected = 0;
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = languages.get(i).isEmpty() ? LocaleController.getString(R.string.TgfeedInterfaceLanguage) : languageName(languages.get(i));
                    if (languages.get(i).equals(ReadAloudSettings.getFallbackLanguage())) selected = i;
                }
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedFallbackLanguage), labels, selected, i -> {
                    ReadAloudSettings.setFallbackLanguage(languages.get(i));
                    updateRows();
                });
                break;
            }
            case T_VOICE:
                pickVoice(row.language);
                break;
            case T_ADD:
                addLanguage();
                break;
            default:
                break;
        }
    }

    private static int indexOf(float[] values, float value) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - value) < 0.01f) return i;
        }
        return -1;
    }

    private static String number(float value) {
        return value == (int) value ? String.valueOf((int) value) : String.valueOf(value);
    }

    // ---------------------------------------------------------------- voices

    /** The language codes the engine has voices for, sorted by name. */
    private List<String> voiceLanguages() {
        TreeSet<String> codes = new TreeSet<>();
        for (Voice voice : voices) {
            codes.add(voice.getLocale().getLanguage());
        }
        List<String> out = new ArrayList<>(codes);
        Collections.sort(out, (a, b) -> languageName(a).compareToIgnoreCase(languageName(b)));
        return out;
    }

    private List<Voice> voicesOf(String language) {
        List<Voice> out = new ArrayList<>();
        for (Voice voice : voices) {
            if (voice.getLocale().getLanguage().equals(language)) out.add(voice);
        }
        return out;
    }

    private Voice voiceNamed(String name) {
        for (Voice voice : voices) {
            if (voice.getName().equals(name)) return voice;
        }
        return null;
    }

    static String languageName(String code) {
        Locale ui = LocaleController.getInstance().getCurrentLocale();
        String name = Locale.forLanguageTag(code).getDisplayLanguage(ui != null ? ui : Locale.getDefault());
        if (name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(ui != null ? ui : Locale.getDefault()) + name.substring(1);
    }

    /** A voice as a person reads it: "Female 1 · US", "Voice SFG · GB · online". */
    static String voiceLabel(Voice voice) {
        String name = voice.getName();
        String region = voice.getLocale().getCountry();
        String tail = name.contains("-x-") ? name.substring(name.lastIndexOf("-x-") + 3) : name;
        String who;
        int hash = tail.indexOf('#');
        if (hash >= 0) {
            String[] g = tail.substring(hash + 1).split("-")[0].split("_");
            String kind;
            if ("female".equals(g[0])) {
                kind = LocaleController.getString(R.string.TgfeedVoiceFemale);
            } else if ("male".equals(g[0])) {
                kind = LocaleController.getString(R.string.TgfeedVoiceMale);
            } else {
                kind = g[0].isEmpty() ? "" : g[0].substring(0, 1).toUpperCase(Locale.ROOT) + g[0].substring(1);
            }
            who = kind + (g.length > 1 ? " " + g[1] : "");
        } else {
            String id = tail.split("-")[0];
            who = id.isEmpty() ? name : LocaleController.formatString(R.string.TgfeedVoiceId, id.toUpperCase(Locale.ROOT));
        }
        StringBuilder b = new StringBuilder(who);
        if (!region.isEmpty()) b.append(" · ").append(region);
        if (voice.isNetworkConnectionRequired()) b.append(" · ").append(LocaleController.getString(R.string.TgfeedVoiceOnline));
        return b.toString();
    }

    /**
     * Speaks the sample sentence: with a given voice, with the phone's default voice for the
     * language ({@code phoneDefault}), or as posts will sound in the language.
     */
    private void preview(String language, Voice voice, boolean phoneDefault) {
        if (!ttsReady) {
            return;
        }
        tts.stop();
        if (voice != null) {
            tts.setVoice(voice);
        } else if (phoneDefault) {
            tts.setLanguage(Locale.forLanguageTag(language));
        } else {
            ReadAloudController.applyVoice(tts, language);
        }
        tts.setSpeechRate(ReadAloudSettings.getRate());
        tts.setPitch(ReadAloudSettings.getPitch());
        String words = "en".equals(language) || "uk".equals(language) ? language : ReadAloudController.interfaceLanguage();
        Configuration config = new Configuration(ApplicationLoader.applicationContext.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(words));
        String text = ApplicationLoader.applicationContext.createConfigurationContext(config).getString(R.string.TgfeedPreviewText);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tgfeed_preview");
    }

    /** Picks the voice of a language from its voices, each heard before it is chosen. */
    private void pickVoice(String language) {
        Context context = getParentActivity();
        if (context == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(languageName(language));
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        builder.setView(scroll);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        String current = ReadAloudSettings.getVoice(language);
        list.addView(voiceRow(context, LocaleController.getString(R.string.TgfeedPhoneDefaultVoice), current == null,
                () -> preview(language, null, true), () -> {
                    ReadAloudSettings.setVoice(language, null);
                    dialog.dismiss();
                    updateRows();
                }));
        for (Voice voice : voicesOf(language)) {
            list.addView(voiceRow(context, voiceLabel(voice), voice.getName().equals(current),
                    () -> preview(language, voice, false), () -> {
                        ReadAloudSettings.setVoice(language, voice.getName());
                        dialog.dismiss();
                        updateRows();
                    }));
        }
        showDialog(dialog);
    }

    private View voiceRow(Context context, String label, boolean checked, Runnable onPlay, Runnable onPick) {
        FrameLayout row = new FrameLayout(context);
        RadioColorCell cell = new RadioColorCell(context, getResourceProvider());
        cell.setPadding(dp(4), 0, dp(52), 0);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
        cell.setTextAndValue(label, checked);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> onPick.run());
        row.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        row.addView(playButton(context, onPlay), LayoutHelper.createFrame(48, 48, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 8, 0, 8, 0));
        return row;
    }

    private ImageView playButton(Context context, Runnable onPlay) {
        ImageView play = new ImageView(context);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setImageResource(R.drawable.msg_voice_speaker);
        play.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), PorterDuff.Mode.SRC_IN));
        play.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        play.setContentDescription(LocaleController.getString(R.string.TgfeedPreview));
        play.setOnClickListener(v -> onPlay.run());
        return play;
    }

    /** A language without a chosen voice yet, from a searchable list, then its voice. */
    private void addLanguage() {
        Context context = getParentActivity();
        if (context == null) return;
        List<String> chosen = ReadAloudSettings.getVoiceLanguages();
        List<String> options = new ArrayList<>();
        for (String language : voiceLanguages()) {
            if (!chosen.contains(language)) options.add(language);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.TgfeedAddLanguage));
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor search = new EditTextBoldCursor(context);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        search.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText));
        search.setHint(LocaleController.getString(R.string.TgfeedSearchLanguages));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setSingleLine(true);
        search.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        search.setPadding(dp(16), dp(11), dp(16), dp(11));
        android.graphics.drawable.GradientDrawable field = new android.graphics.drawable.GradientDrawable();
        field.setCornerRadius(dp(22));
        field.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack), 0.06f));
        search.setBackground(field);
        box.addView(search, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 6));
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        box.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 360));
        builder.setView(box);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        Runnable fill = () -> {
            list.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String language : options) {
                String name = languageName(language);
                if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query) && !language.startsWith(query)) continue;
                TextView item = new TextView(context);
                item.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                item.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                item.setPadding(dp(24), dp(12), dp(24), dp(12));
                item.setText(name);
                item.setBackground(Theme.getSelectorDrawable(false));
                item.setOnClickListener(v -> {
                    dialog.dismiss();
                    pickVoice(language);
                });
                list.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fill.run();
            }
        });
        fill.run();
        showDialog(dialog);
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            int type = rows.get(position).type;
            if (type == T_PITCH || type == T_MAX || type == T_FALLBACK) return T_RATE;
            if (type == T_ADD) return T_PREVIEW;
            return type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == T_RATE || type == T_PREVIEW || type == T_VOICE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case T_RATE: {
                    TextSettingsCell cell = new TextSettingsCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case T_PREVIEW: {
                    TextCell cell = new TextCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    view = cell;
                    break;
                }
                case T_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case T_VOICE: {
                    FrameLayout frame = new FrameLayout(context);
                    frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    TextSettingsCell cell = new TextSettingsCell(context);
                    cell.setPadding(0, 0, dp(48), 0);
                    frame.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                    view = frame;
                    break;
                }
                case T_INFO: {
                    view = new TextInfoPrivacyCell(context);
                    break;
                }
                case T_SHADOW:
                default:
                    view = new ShadowSectionCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            View view = holder.itemView;
            switch (row.type) {
                case T_RATE:
                    ((TextSettingsCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedSpeed), number(ReadAloudSettings.getRate()) + "×", true);
                    break;
                case T_PITCH:
                    ((TextSettingsCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedPitch), number(ReadAloudSettings.getPitch()), true);
                    break;
                case T_MAX:
                    ((TextSettingsCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedMaxLength), String.valueOf(ReadAloudSettings.getMaxChars()), false);
                    break;
                case T_FALLBACK: {
                    String code = ReadAloudSettings.getFallbackLanguage();
                    ((TextSettingsCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedFallbackLanguage), code.isEmpty() ? LocaleController.getString(R.string.TgfeedInterfaceLanguage) : languageName(code), false);
                    break;
                }
                case T_ADD:
                    ((TextCell) view).setTextAndIcon(LocaleController.getString(R.string.TgfeedAddLanguage), R.drawable.msg_add, false);
                    break;
                case T_HEADER:
                    ((HeaderCell) view).setText(row.text);
                    break;
                case T_VOICE: {
                    FrameLayout frame = (FrameLayout) view;
                    TextSettingsCell cell = (TextSettingsCell) frame.getChildAt(0);
                    Voice voice = voiceNamed(ReadAloudSettings.getVoice(row.language));
                    cell.setTextAndValue(languageName(row.language), voice != null ? voiceLabel(voice) : "", true);
                    if (frame.getChildCount() > 1) frame.removeViewAt(1);
                    frame.addView(playButton(frame.getContext(), () -> preview(row.language, null, false)),
                            LayoutHelper.createFrame(48, 48, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 0, 0, 4, 0));
                    break;
                }
                case T_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) view;
                    cell.setText(row.text);
                    boolean last = position + 1 >= rows.size() || rows.get(position + 1).type == T_HEADER;
                    cell.setBackground(Theme.getThemedDrawableByKey(view.getContext(), last ? R.drawable.greydivider_bottom : R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                default:
                    break;
            }
        }
    }
}
