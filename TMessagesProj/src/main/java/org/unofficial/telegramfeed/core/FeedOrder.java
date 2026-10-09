package org.unofficial.telegramfeed.core;

import java.util.Comparator;

/**
 * The order of posts in a feed timeline: by date, then by channel, then by message id, so that
 * posts of several channels merge into one list that is stable and the same on every run.
 * The {@code core} package has no Android dependencies; the plain JVM module
 * {@code TMessagesProj_FeedTests} compiles and tests it.
 */
public final class FeedOrder {

    private FeedOrder() {
    }

    /** A post's position in a feed: its channel, message id and date in seconds. */
    public static final class Key {
        public final long channelId;
        public final int messageId;
        public final int date;

        public Key(long channelId, int messageId, int date) {
            this.channelId = channelId;
            this.messageId = messageId;
            this.date = date;
        }
    }

    /** Oldest first. */
    public static final Comparator<Key> OLDEST_FIRST = (a, b) -> {
        if (a.date != b.date) {
            return Integer.compare(a.date, b.date);
        }
        if (a.channelId != b.channelId) {
            return Long.compare(a.channelId, b.channelId);
        }
        return Integer.compare(a.messageId, b.messageId);
    };
}
