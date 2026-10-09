package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import org.telegram.ui.Components.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** A post the feed's filter left out, folded to one line: the channel's name and the first line of the post. */
public class MinimizedPostCell extends FrameLayout {

    private final TextView textView;
    private MessageObject message;

    public MinimizedPostCell(Context context) {
        super(context);
        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        textView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(Theme.getColor(Theme.key_chat_serviceBackground));
        textView.setBackground(background);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 3, 24, 3));
    }

    public void set(MessageObject message, String channelName, CharSequence summary) {
        this.message = message;
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(channelName)) {
            text.append(channelName);
            text.setSpan(new TypefaceSpan(AndroidUtilities.bold()), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!TextUtils.isEmpty(summary)) {
            if (text.length() > 0) {
                text.append(": ");
            }
            text.append(summary);
        }
        textView.setText(text);
    }

    public MessageObject getMessageObject() {
        return message;
    }
}
