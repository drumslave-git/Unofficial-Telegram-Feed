package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * A feed: an ordered list of channels read as one timeline, with its filter. Feeds belong to
 * one account and are stored in the fork's own SQLite file. No Android dependencies.
 */
public final class Feed {

    public long id;
    public String name;
    /** Creation order, 0 first: the Feeds tab's order among feeds without posts. */
    public int order;
    /** Channel ids (positive, as Telegram's chat ids) in the order the user set; no duplicates. */
    public final List<Long> channelIds = new ArrayList<>();
    public FeedFilter filter = new FeedFilter();
    /** Posts the filter leaves out stay as one folded line each. */
    public boolean showMinimized = false;
    /** A post passes whole when any part of its album passes. */
    public boolean showWholePost = true;

    public Feed() {
    }

    public Feed(long id, String name, int order) {
        this.id = id;
        this.name = name;
        this.order = order;
    }

    public Feed(Feed other) {
        id = other.id;
        name = other.name;
        order = other.order;
        channelIds.addAll(other.channelIds);
        filter = new FeedFilter(other.filter);
        showMinimized = other.showMinimized;
        showWholePost = other.showWholePost;
    }

    public boolean hasChannel(long channelId) {
        return channelIds.contains(channelId);
    }

    /** Appends the channel; false when it is already there. */
    public boolean addChannel(long channelId) {
        if (channelIds.contains(channelId)) {
            return false;
        }
        channelIds.add(channelId);
        return true;
    }

    /** Inserts the channel at the position, for Undo; false when it is already there. */
    public boolean addChannel(long channelId, int position) {
        if (channelIds.contains(channelId)) {
            return false;
        }
        channelIds.add(Math.max(0, Math.min(position, channelIds.size())), channelId);
        return true;
    }

    /** Removes the channel and returns its former position, or -1. */
    public int removeChannel(long channelId) {
        int index = channelIds.indexOf(channelId);
        if (index >= 0) {
            channelIds.remove(index);
        }
        return index;
    }

    public void moveChannel(int from, int to) {
        if (from < 0 || from >= channelIds.size() || to < 0 || to >= channelIds.size() || from == to) {
            return;
        }
        channelIds.add(to, channelIds.remove(from));
    }

    /**
     * The feeds as the Feeds tab lists them, as the chat list lists chats: the feed with the
     * newest post first; feeds without posts last, in their {@link #order}.
     */
    public static List<Feed> byNewestPost(List<Feed> feeds, ToIntFunction<Feed> newestPostDate) {
        List<Feed> out = new ArrayList<>(feeds);
        Collections.sort(out, Comparator.comparingInt((Feed f) -> -newestPostDate.applyAsInt(f)).thenComparingInt(f -> f.order));
        return out;
    }

    /** Makes the orders 0, 1, 2, ... in the list's order. */
    public static void renumber(List<Feed> feeds) {
        for (int i = 0; i < feeds.size(); i++) {
            feeds.get(i).order = i;
        }
    }

    public static void sortByOrder(List<Feed> feeds) {
        Collections.sort(feeds, Comparator.comparingInt(f -> f.order));
    }

    /** The feeds that contain the channel, in their order. */
    public static List<Feed> containing(List<Feed> feeds, long channelId) {
        List<Feed> out = new ArrayList<>();
        for (Feed feed : feeds) {
            if (feed.hasChannel(channelId)) {
                out.add(feed);
            }
        }
        return out;
    }
}
