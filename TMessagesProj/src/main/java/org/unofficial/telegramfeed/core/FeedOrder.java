package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

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

    /** The album a post belongs to, as a key unique across the feed's channels; 0 for a post outside any album. */
    public interface AlbumOf<T> {
        long albumOf(T post);
    }

    /**
     * The posts with every album's parts next to each other, at the place of the album's first
     * part in {@code posts}, its parts in their order there. A timeline lays an album out as one
     * block of rows, so another channel's post must never fall between its parts.
     */
    public static <T> List<T> albumsTogether(List<T> posts, AlbumOf<T> albumOf) {
        LinkedHashMap<Long, List<T>> albums = new LinkedHashMap<>();
        for (T post : posts) {
            long album = albumOf.albumOf(post);
            if (album != 0) {
                List<T> parts = albums.get(album);
                if (parts == null) {
                    parts = new ArrayList<>();
                    albums.put(album, parts);
                }
                parts.add(post);
            }
        }
        List<T> out = new ArrayList<>(posts.size());
        HashSet<Long> placed = new HashSet<>();
        for (T post : posts) {
            long album = albumOf.albumOf(post);
            if (album == 0) {
                out.add(post);
            } else if (placed.add(album)) {
                out.addAll(albums.get(album));
            }
        }
        return out;
    }
}
