package org.unofficial.telegramfeed.feeds;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The unread counts of feeds without the posts a feed's filter hides. Telegram knows only how
 * many posts of a channel are unread, so for a channel in a feed with a filter the unread posts
 * themselves are fetched ({@code messages.getHistory} above the read mark) and judged by each
 * feed's filter; a read drops posts locally and a new post fetches only what came after the
 * newest known one. A feed without a filter counts as Telegram does. Until a channel's posts are
 * in, its count is Telegram's. Lives on the UI thread; posts {@link NotificationCenter#tgfeedCountsChanged}.
 */
public class FeedCounts {

    private static final FeedCounts[] Instance = new FeedCounts[UserConfig.MAX_ACCOUNT_COUNT];

    public static FeedCounts getInstance(int account) {
        if (Instance[account] == null) {
            Instance[account] = new FeedCounts(account);
        }
        return Instance[account];
    }

    /** How many unread posts of one channel are looked at; posts past this count as shown. */
    private static final int MAX_CHECKED = 500;
    private static final int PAGE = 100;

    /** The unread posts of one channel as the filters see them, newest first. */
    private static final class ChannelUnread {
        int readMax;
        int topMessage;
        final List<Integer> ids = new ArrayList<>();
        final List<FeedFilter.Post> posts = new ArrayList<>();
        boolean loading;
        boolean loadedOnce;
        /** The state a full fetch was last made for, so a count that never adds up does not fetch again and again. */
        String fullFetchKey;
    }

    private final int currentAccount;
    private final HashMap<Long, ChannelUnread> channels = new HashMap<>();
    private boolean notifyScheduled;

    private FeedCounts(int account) {
        currentAccount = account;
    }

    private MessagesController messages() {
        return MessagesController.getInstance(currentAccount);
    }

    /** The unread posts of a channel that the feed shows. */
    public int visibleUnread(Feed feed, long channelId) {
        TLRPC.Dialog dialog = messages().getDialog(-channelId);
        if (dialog == null) {
            return 0;
        }
        if (feed.filter.isEmpty() || dialog.unread_count <= 0) {
            return Math.max(0, dialog.unread_count);
        }
        ChannelUnread state = refresh(channelId, dialog);
        if (state == null || !state.loadedOnce) {
            return dialog.unread_count;
        }
        int shown = 0;
        LinkedHashMap<Long, List<FeedFilter.Post>> albums = new LinkedHashMap<>();
        for (int i = 0; i < state.posts.size(); i++) {
            FeedFilter.Post post = state.posts.get(i);
            if (post.albumId != 0) {
                List<FeedFilter.Post> parts = albums.get(post.albumId);
                if (parts == null) {
                    parts = new ArrayList<>();
                    albums.put(post.albumId, parts);
                }
                parts.add(post);
            } else {
                List<FeedFilter.Post> single = new ArrayList<>(1);
                single.add(post);
                shown += feed.filter.shownParts(single, feed.showWholePost).size();
            }
        }
        for (List<FeedFilter.Post> parts : albums.values()) {
            shown += feed.filter.shownParts(parts, feed.showWholePost).size();
        }
        int unchecked = Math.max(0, dialog.unread_count - state.posts.size());
        return shown + unchecked;
    }

    public int unreadPosts(Feed feed) {
        int count = 0;
        for (long channelId : feed.channelIds) {
            count += visibleUnread(feed, channelId);
        }
        return count;
    }

    public int channelsWithNewPosts(Feed feed) {
        int count = 0;
        for (long channelId : feed.channelIds) {
            TLRPC.Dialog dialog = messages().getDialog(-channelId);
            if (dialog != null && (visibleUnread(feed, channelId) > 0 || dialog.unread_count == 0 && dialog.unread_mark)) {
                count++;
            }
        }
        return count;
    }

    /** The unread posts of every channel in any feed, each channel once, a post counting when any of its feeds shows it. */
    public int allUnreadPosts(List<Feed> feeds) {
        Map<Long, Integer> best = bestPerChannel(feeds);
        int count = 0;
        for (int value : best.values()) {
            count += value;
        }
        return count;
    }

    /** The channels in any feed with unread posts that some feed of theirs shows. */
    public int allChannelsWithNewPosts(List<Feed> feeds) {
        Map<Long, Integer> best = bestPerChannel(feeds);
        int count = 0;
        for (Map.Entry<Long, Integer> e : best.entrySet()) {
            TLRPC.Dialog dialog = messages().getDialog(-e.getKey());
            if (e.getValue() > 0 || dialog != null && dialog.unread_count == 0 && dialog.unread_mark) {
                count++;
            }
        }
        return count;
    }

    private Map<Long, Integer> bestPerChannel(List<Feed> feeds) {
        Map<Long, Integer> best = new HashMap<>();
        for (Feed feed : feeds) {
            for (long channelId : feed.channelIds) {
                int value = visibleUnread(feed, channelId);
                Integer current = best.get(channelId);
                if (current == null || value > current) {
                    best.put(channelId, value);
                }
            }
        }
        return best;
    }

    /** Brings a channel's unread posts up to the dialog's state, fetching what is missing. */
    private ChannelUnread refresh(long channelId, TLRPC.Dialog dialog) {
        ChannelUnread state = channels.get(channelId);
        if (state == null) {
            state = new ChannelUnread();
            channels.put(channelId, state);
        }
        if (state.loading) {
            return state;
        }
        if (!state.loadedOnce || dialog.read_inbox_max_id < state.readMax) {
            fetch(channelId, state, dialog, true);
            return state;
        }
        if (dialog.read_inbox_max_id > state.readMax) {
            for (int i = state.ids.size() - 1; i >= 0; i--) {
                if (state.ids.get(i) <= dialog.read_inbox_max_id) {
                    state.ids.remove(i);
                    state.posts.remove(i);
                }
            }
            state.readMax = dialog.read_inbox_max_id;
        }
        if (dialog.top_message > state.topMessage) {
            fetch(channelId, state, dialog, false);
            return state;
        }
        String key = dialog.read_inbox_max_id + "_" + dialog.top_message + "_" + dialog.unread_count;
        if (state.posts.size() < Math.min(dialog.unread_count, MAX_CHECKED) && !key.equals(state.fullFetchKey)) {
            fetch(channelId, state, dialog, true);
        }
        return state;
    }

    private void fetch(long channelId, ChannelUnread state, TLRPC.Dialog dialog, boolean full) {
        TLRPC.InputPeer peer = messages().getInputPeer(-channelId);
        if (peer == null) {
            return;
        }
        state.loading = true;
        final int readMax = dialog.read_inbox_max_id;
        final int top = dialog.top_message;
        final int minId = full ? readMax : Math.max(readMax, state.topMessage);
        final int wanted = Math.min(full ? dialog.unread_count : Math.max(1, top - minId), MAX_CHECKED);
        if (full) {
            state.fullFetchKey = dialog.read_inbox_max_id + "_" + dialog.top_message + "_" + dialog.unread_count;
        }
        List<TLRPC.Message> collected = new ArrayList<>();
        loadPage(peer, minId, 0, wanted, collected, () -> {
            state.loading = false;
            if (full) {
                state.ids.clear();
                state.posts.clear();
            }
            HashSet<Integer> known = new HashSet<>(state.ids);
            List<Integer> newIds = new ArrayList<>();
            List<FeedFilter.Post> newPosts = new ArrayList<>();
            for (TLRPC.Message message : collected) {
                if (message.id <= readMax || known.contains(message.id) || message instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                newIds.add(message.id);
                newPosts.add(PostFilter.describe(new MessageObject(currentAccount, message, false, false)));
            }
            state.ids.addAll(0, newIds);
            state.posts.addAll(0, newPosts);
            state.readMax = readMax;
            state.topMessage = Math.max(state.topMessage, top);
            state.loadedOnce = true;
            scheduleNotify();
        });
    }

    private void loadPage(TLRPC.InputPeer peer, int minId, int offsetId, int wanted, List<TLRPC.Message> collected, Runnable done) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.min_id = minId;
        req.offset_id = offsetId;
        req.limit = Math.min(PAGE, Math.max(1, wanted - collected.size()));
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages)) {
                if (error != null) {
                    FileLog.e("tgfeed counts: " + error.text);
                }
                done.run();
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
            messages().putUsers(res.users, false);
            messages().putChats(res.chats, false);
            int lowest = Integer.MAX_VALUE;
            for (TLRPC.Message message : res.messages) {
                collected.add(message);
                lowest = Math.min(lowest, message.id);
            }
            if (res.messages.size() >= req.limit && collected.size() < wanted && lowest > minId + 1 && lowest != Integer.MAX_VALUE) {
                loadPage(peer, minId, lowest, wanted, collected, done);
            } else {
                done.run();
            }
        }));
    }

    private void scheduleNotify() {
        if (notifyScheduled) {
            return;
        }
        notifyScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            notifyScheduled = false;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.tgfeedCountsChanged);
        }, 300);
    }

    /** The account logged out. */
    public void cleanup() {
        channels.clear();
    }
}
