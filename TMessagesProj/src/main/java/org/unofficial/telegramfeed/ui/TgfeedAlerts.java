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
import org.unofficial.telegramfeed.core.RuleParser;

/** Dialogs the fork's screens share. */
public final class TgfeedAlerts {

    private TgfeedAlerts() {
    }

    /** Asks for a name; {@code whenDone} gets the trimmed, non-empty text. */
    public static void promptName(BaseFragment fragment, String title, String initial, Utilities.Callback<String> whenDone) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resourcesProvider));
        editText.setHint(LocaleController.getString(R.string.TgfeedFeedName));
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
        builder.setView(container);
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
}
