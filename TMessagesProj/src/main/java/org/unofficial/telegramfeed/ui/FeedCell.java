package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.unofficial.telegramfeed.core.CountFormat;

/** One feed in the Feeds tab: name, channel counts, the unread counter and a drag handle. */
public class FeedCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView nameView;
    private final TextView subtitleView;
    private final TextView counterView;
    public final ImageView reorderView;
    private boolean needDivider;

    public FeedCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        nameView.setLines(1);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, 20, 11, 108, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        subtitleView.setLines(1);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, 20, 36, 108, 0));

        // The chat list's unread counter, with the count shortened (73K) as the Feeds tab shows it.
        counterView = new TextView(context);
        counterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        counterView.setTypeface(AndroidUtilities.bold());
        counterView.setGravity(Gravity.CENTER);
        counterView.setSingleLine(true);
        counterView.setMinWidth(dp(23));
        counterView.setPadding(dp(7), 0, dp(7), 0);
        addView(counterView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 23, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 54, 0));

        reorderView = new ImageView(context);
        reorderView.setImageResource(R.drawable.list_reorder);
        reorderView.setScaleType(ImageView.ScaleType.CENTER);
        reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        reorderView.setContentDescription(LocaleController.getString(R.string.FilterReorder));
        addView(reorderView, LayoutHelper.createFrame(48, LayoutHelper.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
    }

    public void set(String name, int channels, int channelsWithNewPosts, int unread, boolean divider) {
        nameView.setText(name);
        String subtitle = LocaleController.formatPluralString("TgfeedChannels", channels);
        if (channelsWithNewPosts > 0) {
            subtitle += " · " + LocaleController.formatPluralString("TgfeedChannelsWithNewPosts", channelsWithNewPosts);
        }
        subtitleView.setText(subtitle);
        counterView.setText(CountFormat.shortCount(unread));
        counterView.setTextColor(Theme.getColor(Theme.key_chats_unreadCounterText, resourcesProvider));
        counterView.setBackground(Theme.createRoundRectDrawable(dp(11.5f), Theme.getColor(Theme.key_chats_unreadCounter, resourcesProvider)));
        counterView.setVisibility(unread > 0 ? VISIBLE : GONE);
        needDivider = divider;
        setWillNotDraw(!divider);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(dp(20), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
        }
    }
}
