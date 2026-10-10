package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The banner that says every rule and the speech are paused, with "Resume". It follows the pause
 * by itself: it shows or hides through {@code onShownChanged} when a host animates it (a panel of
 * the chat list or of a chat), and through its own visibility otherwise (under an action bar).
 */
public class PauseBanner extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final Utilities.Callback<Boolean> onShownChanged;
    private final ImageView icon;
    private final TextView text;
    private final TextView resume;
    private final boolean tinted;

    /**
     * @param onShownChanged gets whether the banner is to be shown; null to let the banner set its
     *                       own visibility
     * @param tinted         a red-tinted background of its own, for a banner under an action bar
     */
    public PauseBanner(Context context, Utilities.Callback<Boolean> onShownChanged, boolean tinted) {
        super(context);
        this.onShownChanged = onShownChanged;
        this.tinted = tinted;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.outline_profile_unmute_24);
        row.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        text.setText(LocaleController.getString(R.string.TgfeedPausedBanner));
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 8, 8, 8));

        resume = new TextView(context);
        resume.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        resume.setTypeface(AndroidUtilities.bold());
        resume.setGravity(Gravity.CENTER);
        resume.setPadding(dp(12), 0, dp(12), 0);
        resume.setText(LocaleController.getString(R.string.TgfeedResume));
        resume.setOnClickListener(v -> SharedConfig.setTgfeedRulesPaused(false));
        row.addView(resume, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        updateColors();
        apply(false);
    }

    public void updateColors() {
        int red = Theme.getColor(Theme.key_text_RedBold);
        icon.setColorFilter(new PorterDuffColorFilter(red, PorterDuff.Mode.SRC_IN));
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        resume.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        resume.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), 0.12f), 8, 8));
        if (tinted) {
            setBackgroundColor(Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.multAlpha(red, 0.10f)));
        }
    }

    private void apply(boolean fromEvent) {
        boolean shown = SharedConfig.tgfeedRulesPaused;
        if (onShownChanged != null) {
            if (fromEvent) {
                onShownChanged.run(shown);
            }
        } else {
            setVisibility(shown ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.tgfeedPauseChanged);
        apply(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.tgfeedPauseChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.tgfeedPauseChanged) {
            apply(true);
        }
    }

    /**
     * Puts a banner first in a screen's stack of top panels (the chat list's, a chat's), shown
     * while paused; {@code tinted} for panels without a background of their own.
     */
    public static PauseBanner addTo(org.telegram.ui.Components.AnimatedLinearLayout panels, boolean tinted) {
        PauseBanner[] banner = new PauseBanner[1];
        banner[0] = new PauseBanner(panels.getContext(), shown -> panels.setViewVisible(banner[0], shown), tinted);
        panels.addView(banner[0], 0);
        panels.setViewVisible(banner[0], SharedConfig.tgfeedRulesPaused, false);
        ReadingBanner[] reading = new ReadingBanner[1];
        reading[0] = new ReadingBanner(panels.getContext(), shown -> panels.setViewVisible(reading[0], shown), tinted);
        panels.addView(reading[0], 1);
        return banner[0];
    }

    /** The fork's banners one under the other, each shown only while it has something to say. */
    public static View createStack(Context context, boolean tinted) {
        LinearLayout stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(new PauseBanner(context, null, tinted), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        stack.addView(new ReadingBanner(context, null, tinted), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return stack;
    }

    /**
     * Makes an action bar item the pause button: a tap pauses or resumes every rule and the
     * speech, and the slashed bell is red while they are paused, whatever colour the bar gives it.
     */
    public static void bindPauseButton(ActionBarMenuItem item) {
        PauseIcon icon = new PauseIcon(item.getContext().getResources().getDrawable(R.drawable.outline_profile_unmute_24).mutate());
        item.setIcon(icon);
        Runnable update = () -> {
            item.getIconView().invalidate();
            item.setContentDescription(LocaleController.getString(SharedConfig.tgfeedRulesPaused ? R.string.TgfeedResumeNotifications : R.string.TgfeedPauseNotifications));
        };
        NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> update.run();
        item.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.tgfeedPauseChanged);
                update.run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                NotificationCenter.getGlobalInstance().removeObserver(observer, NotificationCenter.tgfeedPauseChanged);
            }
        });
        item.setOnClickListener(v -> SharedConfig.setTgfeedRulesPaused(!SharedConfig.tgfeedRulesPaused));
        update.run();
    }

    /** The slashed bell: drawn in the colour the bar sets, or red while paused. */
    private static final class PauseIcon extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.Drawable base;
        private android.graphics.ColorFilter normal;

        PauseIcon(android.graphics.drawable.Drawable base) {
            this.base = base;
        }

        @Override
        public void draw(@androidx.annotation.NonNull android.graphics.Canvas canvas) {
            if (SharedConfig.tgfeedRulesPaused) {
                base.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_text_RedBold), PorterDuff.Mode.SRC_IN));
            } else {
                base.setColorFilter(normal);
            }
            base.setBounds(getBounds());
            base.draw(canvas);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            normal = colorFilter;
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            base.setAlpha(alpha);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return base.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return base.getIntrinsicHeight();
        }
    }
}
