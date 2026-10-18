package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * The app's own marks where Telegram draws its logo: the launcher icon's colours, the launcher
 * label as the chat list's title, and the launcher icon's circle on the intro page.
 */
public final class Brand {

    /** The launcher icon's gradient runs from this blue at the top left... */
    public static final int ICON_BLUE = 0xff2f6fe4;
    /** ...to this teal at the bottom right ({@code drawable/tgfeed_icon_background.xml}). */
    public static final int ICON_TEAL = 0xff17b3c4;

    private Brand() {
    }

    /**
     * The launcher label in the chat list title's bold 20dp type, in white for a colour filter to
     * tint. It has the box of Telegram's wordmark: 22dp tall, with the baseline where the
     * wordmark's letters stand.
     */
    public static Drawable titleDrawable() {
        return new TitleDrawable(LocaleController.getString(R.string.AppLauncherLabel));
    }

    /** The intro page's circle, {@code size} pixels across, in the launcher icon's gradient. */
    public static Bitmap introCircle(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, size, size, ICON_BLUE, ICON_TEAL, Shader.TileMode.CLAMP));
        new Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return bitmap;
    }

    private static class TitleDrawable extends Drawable {

        private final String text;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final int width;

        TitleDrawable(String text) {
            this.text = text;
            paint.setTypeface(AndroidUtilities.bold());
            paint.setTextSize(dp(20));
            paint.setColor(0xffffffff);
            width = (int) Math.ceil(paint.measureText(text));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawText(text, bounds.left, bounds.top + dp(16), paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(22);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
