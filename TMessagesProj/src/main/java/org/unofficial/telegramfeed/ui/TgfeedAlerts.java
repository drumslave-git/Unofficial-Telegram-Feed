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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

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
}
