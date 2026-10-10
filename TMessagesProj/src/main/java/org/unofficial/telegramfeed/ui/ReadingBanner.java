package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.unofficial.telegramfeed.feeds.ReadAloudController;

/**
 * The banner shown while a post is read aloud: the channel, how many posts wait, "Stop" and,
 * when posts wait, "Stop and clear queue". Like {@link PauseBanner} it follows the read-aloud
 * state by itself, through {@code onShownChanged} or through its own visibility.
 */
public class ReadingBanner extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final Utilities.Callback<Boolean> onShownChanged;
    private final boolean tinted;
    private final ImageView icon;
    private final TextView title;
    private final TextView subtitle;
    private final TextView stop;
    private final TextView stopAll;
    private boolean shown;

    public ReadingBanner(Context context, Utilities.Callback<Boolean> onShownChanged, boolean tinted) {
        super(context);
        this.onShownChanged = onShownChanged;
        this.tinted = tinted;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg_voice_speaker);
        row.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 8, 12, 0));

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 6));

        stopAll = button(context, R.string.TgfeedStopAll);
        stopAll.setOnClickListener(v -> ReadAloudController.getInstance().stopAll());
        buttons.addView(stopAll, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 4, 0));

        stop = button(context, R.string.TgfeedStop);
        stop.setOnClickListener(v -> ReadAloudController.getInstance().skipCurrent());
        buttons.addView(stop, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));

        updateColors();
        apply(false);
    }

    private static TextView button(Context context, int textRes) {
        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setText(LocaleController.getString(textRes));
        return button;
    }

    public void updateColors() {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
        icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        for (TextView button : new TextView[]{stop, stopAll}) {
            button.setTextColor(accent);
            button.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(accent, 0.12f), 8, 8));
        }
        if (tinted) {
            setBackgroundColor(Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.multAlpha(accent, 0.10f)));
        }
    }

    private void apply(boolean fromEvent) {
        ReadAloudController controller = ReadAloudController.getInstance();
        ReadAloudController.Item item = controller.getCurrent();
        int waiting = controller.getWaitingCount();
        boolean nowShown = item != null || waiting > 0;
        if (nowShown) {
            String channel = item != null ? item.channelTitle : null;
            title.setText(TextUtils.isEmpty(channel)
                    ? LocaleController.getString(R.string.TgfeedReadingAloudShort)
                    : LocaleController.formatString(R.string.TgfeedReadingAloudChannel, channel));
            subtitle.setText(waiting > 0 ? LocaleController.formatPluralString("TgfeedReadingQueue", waiting) : "");
            subtitle.setVisibility(waiting > 0 ? View.VISIBLE : View.GONE);
            stopAll.setVisibility(waiting > 0 ? View.VISIBLE : View.GONE);
        }
        if (onShownChanged != null) {
            if (fromEvent && nowShown != shown) {
                onShownChanged.run(nowShown);
            }
        } else {
            setVisibility(nowShown ? View.VISIBLE : View.GONE);
        }
        shown = nowShown;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.tgfeedReadAloudChanged);
        shown = !shown;
        apply(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.tgfeedReadAloudChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.tgfeedReadAloudChanged) {
            apply(true);
        }
    }
}
