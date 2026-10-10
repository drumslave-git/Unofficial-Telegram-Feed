package org.unofficial.telegramfeed.feeds;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.unofficial.telegramfeed.core.Rule;
import org.unofficial.telegramfeed.core.RuleMatcher;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The rules' say in Telegram's notifications, for {@code NotificationsController}. A channel
 * with at least one enabled rule notifies only for posts a rule matches, also when Telegram
 * has it muted; every other dialog is left to Telegram. A match is remembered per message
 * (priority, rule names, feed) and kept across restarts, so a notification rebuilt later shows
 * the same. Everything here runs on Telegram's notifications queue.
 */
public class RuleNotifications {

    private static final RuleNotifications[] Instance = new RuleNotifications[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized RuleNotifications getInstance(int account) {
        if (Instance[account] == null) {
            Instance[account] = new RuleNotifications(account);
        }
        return Instance[account];
    }

    /** How long a decision may wait for the rules to load or for a pushed post to arrive. */
    private static final long SNAPSHOT_WAIT_MS = 3000;
    private static final long FETCH_WAIT_MS = 8000;
    private static final int REMEMBERED = 300;

    public static final String CHANNEL_SILENT = "tgfeed_rules_silent";
    public static final String CHANNEL_NORMAL = "tgfeed_rules_normal";
    public static final String CHANNEL_URGENT = "tgfeed_rules_urgent";
    private static final String CHANNEL_GROUP = "tgfeed_rules";

    /** What a match left for the notification. */
    public static final class Notified {
        public final int priority;
        public final long feedId;
        public final String names;

        Notified(int priority, long feedId, String names) {
            this.priority = priority;
            this.feedId = feedId;
            this.names = names;
        }

        String encode() {
            return priority + "|" + feedId + "|" + names;
        }

        static Notified decode(String s) {
            try {
                String[] parts = s.split("\\|", 3);
                return new Notified(Integer.parseInt(parts[0]), Long.parseLong(parts[1]), parts.length > 2 ? parts[2] : "");
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** The decision about one message. */
    public static final class Decision {
        /** True when the message is to notify. */
        public final boolean show;
        public final Notified notified;

        Decision(boolean show, Notified notified) {
            this.show = show;
            this.notified = notified;
        }
    }

    private final int currentAccount;
    private final LinkedHashMap<String, Notified> notified = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> matchedAlbums = new LinkedHashMap<>();
    private boolean restored;
    private boolean channelsCreated;

    private RuleNotifications(int account) {
        currentAccount = account;
    }

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgfeed_rule_notifications" + currentAccount, Activity.MODE_PRIVATE);
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    private void restore() {
        if (restored) {
            return;
        }
        restored = true;
        String all = preferences().getString("notified", "");
        if (all.isEmpty()) {
            return;
        }
        for (String line : all.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            Notified n = Notified.decode(line.substring(eq + 1));
            if (n != null) notified.put(line.substring(0, eq), n);
        }
    }

    private void save() {
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, Notified> e : notified.entrySet()) {
            if (all.length() > 0) all.append('\n');
            all.append(e.getKey()).append('=').append(e.getValue().encode());
        }
        preferences().edit().putString("notified", all.toString()).apply();
    }

    private void remember(long dialogId, int messageId, Notified n) {
        restore();
        notified.put(key(dialogId, messageId), n);
        Iterator<String> it = notified.keySet().iterator();
        while (notified.size() > REMEMBERED && it.hasNext()) {
            it.next();
            it.remove();
        }
        save();
    }

    /** The match a message notified for; null when it did not notify through a rule. */
    public Notified get(long dialogId, int messageId) {
        restore();
        return notified.get(key(dialogId, messageId));
    }

    /** Whether the rules decide about this dialog: a channel with at least one enabled rule. */
    public boolean governs(long dialogId) {
        if (dialogId >= 0) {
            return false;
        }
        RulesController.Snapshot snapshot = RulesController.getInstance(currentAccount).awaitSnapshot(SNAPSHOT_WAIT_MS);
        return snapshot != null && snapshot.hasEnabledRules(-dialogId);
    }

    /**
     * Decides about a new message; null when the rules have no say and Telegram decides. A
     * pushed message carries only the text of a text post, so the post itself is fetched first.
     */
    public Decision decide(MessageObject message, boolean isChannel, boolean isFcm) {
        if (message == null || message.messageOwner == null || !isChannel || message.messageOwner.mentioned
                || message.isReactionPush || message.isStoryReactionPush || message.isStoryPush || message.isStoryMentionPush) {
            return null;
        }
        long dialogId = message.getDialogId();
        if (dialogId >= 0) {
            return null;
        }
        long channelId = -dialogId;
        RulesController controller = RulesController.getInstance(currentAccount);
        RulesController.Snapshot snapshot = controller.awaitSnapshot(SNAPSHOT_WAIT_MS);
        if (snapshot == null || !snapshot.hasEnabledRules(channelId)) {
            return null;
        }
        if (SharedConfig.tgfeedRulesPaused) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("tgfeed rules: channel " + channelId + " message " + message.getId() + " -> paused");
            }
            return new Decision(false, null);
        }
        MessageObject post = message;
        if (isFcm || message.isFcmMessage()) {
            MessageObject fetched = fetch(channelId, message.getId());
            if (fetched != null) {
                post = fetched;
            }
        }
        if (post.hasValidGroupId()) {
            String album = dialogId + "_" + post.getGroupId();
            if (matchedAlbums.containsKey(album)) {
                return new Decision(false, null); // one notification per album
            }
        }
        RuleMatcher.Match match = controller.match(snapshot, channelId, post);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("tgfeed rules: channel " + channelId + " message " + message.getId() + (isFcm ? " (push)" : "") + " -> " + (match == null ? "no match" : "match " + match.ruleNames() + " priority " + match.priority));
        }
        if (match == null) {
            return new Decision(false, null);
        }
        if (post.hasValidGroupId()) {
            matchedAlbums.put(dialogId + "_" + post.getGroupId(), true);
            Iterator<String> it = matchedAlbums.keySet().iterator();
            while (matchedAlbums.size() > 100 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        Notified n = new Notified(match.priority, match.feedId(), String.join(", ", match.ruleNames()));
        remember(dialogId, message.getId(), n);
        return new Decision(true, n);
    }

    /** Fetches a channel post by id, waiting up to {@link #FETCH_WAIT_MS}; null when it did not come. */
    private MessageObject fetch(long channelId, int messageId) {
        if (messageId <= 0) {
            return null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
        if (chat == null) {
            chat = MessagesStorage.getInstance(currentAccount).getChatSync(channelId);
        }
        if (chat == null || !ChatObject.isChannel(chat)) {
            return null;
        }
        TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = MessagesController.getInputChannel(chat);
        req.id.add(messageId);
        final MessageObject[] result = new MessageObject[1];
        CountDownLatch latch = new CountDownLatch(1);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                    if (m.id == messageId && !(m instanceof TLRPC.TL_messageEmpty)) {
                        result[0] = new MessageObject(currentAccount, m, false, false);
                    }
                }
            }
            latch.countDown();
        });
        try {
            latch.await(FETCH_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
        }
        return result[0];
    }

    /** The notification channel of a priority, created on first use. */
    public String channelFor(int priority) {
        createChannels();
        switch (priority) {
            case Rule.PRIORITY_SILENT:
                return CHANNEL_SILENT;
            case Rule.PRIORITY_URGENT:
                return CHANNEL_URGENT;
            default:
                return CHANNEL_NORMAL;
        }
    }

    private synchronized void createChannels() {
        if (channelsCreated || Build.VERSION.SDK_INT < 26) {
            channelsCreated = true;
            return;
        }
        channelsCreated = true;
        try {
            NotificationManager manager = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannelGroup(new android.app.NotificationChannelGroup(CHANNEL_GROUP, LocaleController.getString(R.string.TgfeedRuleChannels)));
            AudioAttributes audio = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            NotificationChannel silent = new NotificationChannel(CHANNEL_SILENT, LocaleController.getString(R.string.TgfeedRuleChannelSilent), NotificationManager.IMPORTANCE_LOW);
            silent.setGroup(CHANNEL_GROUP);
            silent.setSound(null, null);
            silent.enableVibration(false);
            manager.createNotificationChannel(silent);

            NotificationChannel normal = new NotificationChannel(CHANNEL_NORMAL, LocaleController.getString(R.string.TgfeedRuleChannelNormal), NotificationManager.IMPORTANCE_HIGH);
            normal.setGroup(CHANNEL_GROUP);
            normal.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio);
            normal.enableVibration(true);
            manager.createNotificationChannel(normal);

            NotificationChannel urgent = new NotificationChannel(CHANNEL_URGENT, LocaleController.getString(R.string.TgfeedRuleChannelUrgent), NotificationManager.IMPORTANCE_HIGH);
            urgent.setGroup(CHANNEL_GROUP);
            urgent.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio);
            urgent.enableVibration(true);
            urgent.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
            urgent.setBypassDnd(true);
            manager.createNotificationChannel(urgent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
