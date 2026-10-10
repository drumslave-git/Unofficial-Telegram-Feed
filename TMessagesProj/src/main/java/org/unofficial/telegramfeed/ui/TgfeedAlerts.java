package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.unofficial.telegramfeed.core.RuleBuilder;
import org.unofficial.telegramfeed.core.RuleParser;

/** Dialogs the fork's screens share. */
public final class TgfeedAlerts {

    private TgfeedAlerts() {
    }

    /** Asks for a name; {@code whenDone} gets the trimmed, non-empty text. */
    public static void promptName(BaseFragment fragment, String title, String initial, Utilities.Callback<String> whenDone) {
        promptText(fragment, title, LocaleController.getString(R.string.TgfeedFeedName), initial, whenDone);
    }

    /** Asks for one line of text; {@code whenDone} gets the trimmed, non-empty text. */
    public static void promptText(BaseFragment fragment, String title, String hint, String initial, Utilities.Callback<String> whenDone) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resourcesProvider));
        editText.setHint(hint);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setSingleLine(true);
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        editText.setText(initial);
        editText.setSelection(editText.length());
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(dp(22));
        fieldBackground.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider), 0.06f));
        editText.setBackground(fieldBackground);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));
        builder.setView(container);

        final AlertDialog[] dialog = new AlertDialog[1];
        Runnable done = () -> {
            String name = editText.getText().toString().trim();
            if (name.isEmpty()) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            whenDone.run(name);
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
        };
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                done.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        dialog[0] = builder.create();
        dialog[0].setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
            View button = dialog[0].getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setOnClickListener(v -> done.run());
            }
        });
        fragment.showDialog(dialog[0]);
    }

    /** One of several options, as radio rows; {@code whenChosen} gets the index of the tapped one. */
    public static void choose(BaseFragment fragment, String title, CharSequence[] labels, int selected, Utilities.Callback<Integer> whenChosen) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(container);
        builder.setView(scroll);
        for (int i = 0; i < labels.length; i++) {
            RadioColorCell cell = new RadioColorCell(context, resourcesProvider);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setTag(i);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cell.setTextAndValue(labels[i], i == selected);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ALL));
            container.addView(cell);
            cell.setOnClickListener(v -> {
                whenChosen.run((Integer) v.getTag());
                builder.getDismissRunnable().run();
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** Several options with check boxes and a Save button; {@code whenDone} gets the final checks. */
    public static void chooseMany(BaseFragment fragment, String title, CharSequence[] labels, boolean[] checked, Utilities.Callback<boolean[]> whenDone) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        builder.setView(container);
        CheckBoxCell[] cells = new CheckBoxCell[labels.length];
        for (int i = 0; i < labels.length; i++) {
            cells[i] = new CheckBoxCell(context, 1, resourcesProvider);
            cells[i].setBackground(Theme.getSelectorDrawable(false));
            cells[i].setTag(i);
            cells[i].setText(labels[i], "", checked[i], false);
            cells[i].setPadding(LocaleController.isRTL ? dp(16) : dp(8), 0, LocaleController.isRTL ? dp(8) : dp(16), 0);
            container.addView(cells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            cells[i].setOnClickListener(v -> {
                CheckBoxCell cell = cells[(Integer) v.getTag()];
                cell.setChecked(!cell.isChecked(), true);
            });
        }
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            boolean[] result = new boolean[cells.length];
            for (int i = 0; i < cells.length; i++) {
                result[i] = cells[i].isChecked();
            }
            whenDone.run(result);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** The message for a condition that does not parse, in the reader's language. */
    public static String describe(RuleParser.SyntaxException e) {
        switch (e.problem) {
            case UNEXPECTED:
                return LocaleController.formatString(R.string.TgfeedRuleErrorUnexpected, e.detail, e.position);
            case EXPECTED_CLOSING_PAREN:
                return LocaleController.formatString(R.string.TgfeedRuleErrorClosingParen, e.position);
            case UNTERMINATED_QUOTE:
                return LocaleController.getString(R.string.TgfeedRuleErrorQuote);
            case DANGLING_ESCAPE:
                return LocaleController.getString(R.string.TgfeedRuleErrorEscape);
            case EMPTY_TERM:
                return LocaleController.formatString(R.string.TgfeedRuleErrorEmpty, e.position);
            case KEYWORD:
                return LocaleController.formatString(R.string.TgfeedRuleErrorKeyword, e.detail);
            case EXPECTED_TERM:
            default:
                return LocaleController.formatString(R.string.TgfeedRuleErrorExpectedTerm, e.position);
        }
    }

    /** Asks for a word condition in the rule syntax; the dialog stays open with the error until the text parses or is empty. */
    public static void promptCondition(BaseFragment fragment, String title, String initial, Utilities.Callback<String> whenDone) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resourcesProvider));
        editText.setHint(LocaleController.getString(R.string.TgfeedWordsHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setMaxLines(4);
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        editText.setText(initial);
        editText.setSelection(editText.length());
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(dp(16));
        fieldBackground.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider), 0.06f));
        editText.setBackground(fieldBackground);

        TextView errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        errorView.setVisibility(View.GONE);

        TextView helpView = new TextView(context);
        helpView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        helpView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        helpView.setText(LocaleController.getString(R.string.TgfeedWordsInfo));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 4));
        container.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 4));
        container.addView(helpView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 9));
        builder.setView(container);

        final AlertDialog[] dialog = new AlertDialog[1];
        Runnable done = () -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) {
                try {
                    RuleParser.parse(text);
                } catch (RuleParser.SyntaxException e) {
                    errorView.setText(describe(e));
                    errorView.setVisibility(View.VISIBLE);
                    AndroidUtilities.shakeView(editText);
                    return;
                }
            }
            whenDone.run(text);
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
        };
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        dialog[0] = builder.create();
        dialog[0].setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
            View button = dialog[0].getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setOnClickListener(v -> done.run());
            }
        });
        fragment.showDialog(dialog[0]);
    }

    /**
     * Edits one term of the rule builder: the word or phrase and whether the post must not
     * contain it, whether it matches whole words only, and whether it matches the case.
     * {@code whenDone} gets the term, or null when the term is to be removed.
     */
    public static void editTerm(BaseFragment fragment, RuleBuilder.BuilderTerm initial, boolean removable, Utilities.Callback<RuleBuilder.BuilderTerm> whenDone) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.TgfeedTerm));

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resourcesProvider));
        editText.setHint(LocaleController.getString(initial.negated ? R.string.TgfeedTermHintNegated : R.string.TgfeedTermHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setSingleLine(true);
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        editText.setText(initial.text);
        editText.setSelection(editText.length());
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(dp(22));
        fieldBackground.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider), 0.06f));
        editText.setBackground(fieldBackground);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 6));
        int[] labels = {R.string.TgfeedTermMustNotContain, R.string.TgfeedTermWholeWord, R.string.TgfeedTermMatchCase};
        boolean[] checks = {initial.negated, initial.wholeWord, initial.caseSensitive};
        CheckBoxCell[] cells = new CheckBoxCell[labels.length];
        for (int i = 0; i < labels.length; i++) {
            CheckBoxCell cell = cells[i] = new CheckBoxCell(context, 1, resourcesProvider);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(LocaleController.getString(labels[i]), "", checks[i], false);
            cell.setPadding(LocaleController.isRTL ? dp(16) : dp(8), 0, LocaleController.isRTL ? dp(8) : dp(16), 0);
            cell.setOnClickListener(v -> {
                cell.setChecked(!cell.isChecked(), true);
                if (cell == cells[0]) {
                    editText.setHint(LocaleController.getString(cell.isChecked() ? R.string.TgfeedTermHintNegated : R.string.TgfeedTermHint));
                }
            });
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        builder.setView(container);

        final AlertDialog[] dialog = new AlertDialog[1];
        Runnable done = () -> {
            String text = editText.getText().toString().trim();
            if (text.isEmpty()) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            whenDone.run(new RuleBuilder.BuilderTerm(text, cells[1].isChecked(), cells[2].isChecked(), cells[0].isChecked()));
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
        };
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (removable) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) -> whenDone.run(null));
        }
        dialog[0] = builder.create();
        dialog[0].setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
            View button = dialog[0].getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setOnClickListener(v -> done.run());
            }
            View remove = dialog[0].getButton(DialogInterface.BUTTON_NEUTRAL);
            if (remove instanceof TextView) {
                ((TextView) remove).setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
            }
        });
        fragment.showDialog(dialog[0]);
    }

    /** How to write a condition as text, an example and what it does per line. */
    public static void showSyntax(BaseFragment fragment) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.TgfeedSyntaxTitle));
        String[][] lines = {
                {"rates", LocaleController.getString(R.string.TgfeedSyntaxWord)},
                {"\"rate cut\"", LocaleController.getString(R.string.TgfeedSyntaxPhrase)},
                {"rates AND bank", LocaleController.getString(R.string.TgfeedSyntaxAnd)},
                {"rates OR inflation", LocaleController.getString(R.string.TgfeedSyntaxOr)},
                {"NOT ad", LocaleController.getString(R.string.TgfeedSyntaxNot)},
                {"(a OR b) AND c", LocaleController.getString(R.string.TgfeedSyntaxBrackets)},
                {"~rate", LocaleController.getString(R.string.TgfeedSyntaxSubstring)},
                {"=ECB", LocaleController.getString(R.string.TgfeedSyntaxCase)},
        };
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(4), dp(24), dp(8));
        for (String[] line : lines) {
            TextView example = new TextView(context);
            example.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            example.setTypeface(android.graphics.Typeface.MONOSPACE);
            example.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            example.setText(line[0]);
            container.addView(example, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            TextView info = new TextView(context);
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
            info.setText(line[1]);
            container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(container);
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }
}
