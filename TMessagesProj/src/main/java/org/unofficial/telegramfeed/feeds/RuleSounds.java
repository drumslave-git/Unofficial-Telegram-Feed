package org.unofficial.telegramfeed.feeds;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.unofficial.telegramfeed.core.Rule;

/**
 * The sound and vibration of rule notifications, device-wide, and the notification channels that
 * carry them. Android fixes a channel's sound and vibration once it exists, so a change replaces
 * the channel of that priority with a new one under the next id ({@code tgfeed_rules_normal},
 * then {@code tgfeed_rules_normal_1}, ...).
 */
public final class RuleSounds {

    public static final int VIBRATE_DEFAULT = 0;
    public static final int VIBRATE_SHORT = 1;
    public static final int VIBRATE_LONG = 2;
    public static final int VIBRATE_OFF = 3;

    private static final String GROUP = "tgfeed_rules";
    private static final String PREFS = "tgfeed_rule_sounds";
    private static final String NO_SOUND = "none";
    private static final long[] SHORT_PATTERN = {0, 100, 100, 100};
    private static final long[] LONG_PATTERN = {0, 400, 200, 400, 200, 400};

    private static String createdKey;

    private RuleSounds() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String name(int priority) {
        switch (priority) {
            case Rule.PRIORITY_SILENT:
                return "silent";
            case Rule.PRIORITY_URGENT:
                return "urgent";
            default:
                return "normal";
        }
    }

    /** The sound of a priority: null for none; the default notification sound unless changed. */
    public static Uri getSound(int priority) {
        String value = preferences().getString("sound_" + name(priority), "");
        if (NO_SOUND.equals(value)) {
            return null;
        }
        if (value.isEmpty()) {
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        }
        return Uri.parse(value);
    }

    /** One of the {@code VIBRATE_} values; urgent vibrates long unless changed. */
    public static int getVibrate(int priority) {
        return preferences().getInt("vibrate_" + name(priority), priority == Rule.PRIORITY_URGENT ? VIBRATE_LONG : VIBRATE_DEFAULT);
    }

    /** The sound's name for a settings row. */
    public static String soundLabel(Context context, int priority) {
        Uri sound = getSound(priority);
        if (sound == null) {
            return LocaleController.getString(R.string.NoSound);
        }
        if (sound.equals(Settings.System.DEFAULT_NOTIFICATION_URI)) {
            return LocaleController.getString(R.string.SoundDefault);
        }
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(context, sound);
            if (ringtone != null) {
                return ringtone.getTitle(context);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return LocaleController.getString(R.string.SoundDefault);
    }

    public static String vibrateLabel(int value) {
        switch (value) {
            case VIBRATE_SHORT:
                return LocaleController.getString(R.string.Short);
            case VIBRATE_LONG:
                return LocaleController.getString(R.string.Long);
            case VIBRATE_OFF:
                return LocaleController.getString(R.string.VibrationDisabled);
            default:
                return LocaleController.getString(R.string.VibrationDefault);
        }
    }

    public static String[] vibrateLabels() {
        return new String[]{vibrateLabel(VIBRATE_DEFAULT), vibrateLabel(VIBRATE_SHORT), vibrateLabel(VIBRATE_LONG), vibrateLabel(VIBRATE_OFF)};
    }

    /**
     * Sets a priority's sound, null for none, and replaces its channel. Returns true when Android
     * had let the old channel through Do Not Disturb, which the new channel has to be granted again.
     */
    public static boolean setSound(int priority, Uri sound) {
        String value = sound == null ? NO_SOUND : sound.equals(Settings.System.DEFAULT_NOTIFICATION_URI) ? "" : sound.toString();
        preferences().edit().putString("sound_" + name(priority), value).apply();
        return replaceChannel(priority);
    }

    /** Sets a priority's vibration and replaces its channel; returns as {@link #setSound}. */
    public static boolean setVibrate(int priority, int value) {
        preferences().edit().putInt("vibrate_" + name(priority), value).apply();
        return replaceChannel(priority);
    }

    private static int version(int priority) {
        return preferences().getInt("version_" + name(priority), 0);
    }

    /** The channel id of a priority, its channels created first. */
    public static synchronized String channelFor(int priority) {
        ensureChannels();
        return channelId(priority);
    }

    private static String channelId(int priority) {
        int version = version(priority);
        return "tgfeed_rules_" + name(priority) + (version == 0 ? "" : "_" + version);
    }

    private static synchronized boolean replaceChannel(int priority) {
        boolean hadDndOverride = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager manager = manager();
                NotificationChannel old = manager.getNotificationChannel(channelId(priority));
                if (old != null) {
                    hadDndOverride = old.canBypassDnd();
                    manager.deleteNotificationChannel(old.getId());
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        preferences().edit().putInt("version_" + name(priority), version(priority) + 1).apply();
        createdKey = null;
        ensureChannels();
        return hadDndOverride;
    }

    private static NotificationManager manager() {
        return (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        String key = channelId(Rule.PRIORITY_SILENT) + channelId(Rule.PRIORITY_NORMAL) + channelId(Rule.PRIORITY_URGENT);
        if (key.equals(createdKey)) {
            return;
        }
        try {
            NotificationManager manager = manager();
            manager.createNotificationChannelGroup(new NotificationChannelGroup(GROUP, LocaleController.getString(R.string.TgfeedRuleChannels)));

            NotificationChannel silent = new NotificationChannel(channelId(Rule.PRIORITY_SILENT), LocaleController.getString(R.string.TgfeedRuleChannelSilent), NotificationManager.IMPORTANCE_LOW);
            silent.setGroup(GROUP);
            silent.setSound(null, null);
            silent.enableVibration(false);
            manager.createNotificationChannel(silent);

            manager.createNotificationChannel(alerting(Rule.PRIORITY_NORMAL, R.string.TgfeedRuleChannelNormal));
            NotificationChannel urgent = alerting(Rule.PRIORITY_URGENT, R.string.TgfeedRuleChannelUrgent);
            urgent.setBypassDnd(true);
            manager.createNotificationChannel(urgent);
            createdKey = key;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static NotificationChannel alerting(int priority, int nameRes) {
        NotificationChannel channel = new NotificationChannel(channelId(priority), LocaleController.getString(nameRes), NotificationManager.IMPORTANCE_HIGH);
        channel.setGroup(GROUP);
        Uri sound = getSound(priority);
        if (sound == null) {
            channel.setSound(null, null);
        } else {
            channel.setSound(sound, new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build());
        }
        int vibrate = getVibrate(priority);
        if (vibrate == VIBRATE_OFF) {
            channel.enableVibration(false);
        } else {
            channel.enableVibration(true);
            if (vibrate == VIBRATE_SHORT) {
                channel.setVibrationPattern(SHORT_PATTERN);
            } else if (vibrate == VIBRATE_LONG) {
                channel.setVibrationPattern(LONG_PATTERN);
            }
        }
        return channel;
    }
}
