package org.unofficial.telegramfeed.feeds;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Searches a feed's channels with {@code messages.search}, one request per channel, and merges
 * the pages newest first. As the timeline does for history, a channel that still has older
 * matches holds the merged list back at its oldest loaded match, so the order never has a gap.
 */
public class FeedSearch {

    public interface Listener {
        /** The merged results changed, or a page finished loading. */
        void onResults();
    }

    private static final int PAGE = 20;

    private static class ChannelSearch {
        final long channelId;
        int offsetId;
        int minDate = Integer.MAX_VALUE;
        int total;
        boolean endReached;
        boolean loading;
        int reqId;

        ChannelSearch(long channelId) {
            this.channelId = channelId;
        }
    }

    private final int account;
    private final Listener listener;
    private final HashMap<Long, ChannelSearch> channels = new HashMap<>();
    private final ArrayList<MessageObject> all = new ArrayList<>();
    /** The merged matches, newest first, down to the floor. */
    public final ArrayList<MessageObject> results = new ArrayList<>();
    /** The channels' own counts of matches added up. */
    public int total;
    public String query = "";
    public TLRPC.MessagesFilter filter;
    private int generation;

    public FeedSearch(int account, Listener listener) {
        this.account = account;
        this.listener = listener;
    }

    public boolean isActive() {
        return !channels.isEmpty();
    }

    public boolean isLoading() {
        for (ChannelSearch c : channels.values()) {
            if (c.loading) {
                return true;
            }
        }
        return false;
    }

    public boolean hasMore() {
        for (ChannelSearch c : channels.values()) {
            if (!c.endReached) {
                return true;
            }
        }
        return false;
    }

    public void cancel() {
        generation++;
        for (ChannelSearch c : channels.values()) {
            if (c.reqId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(c.reqId, true);
            }
        }
        channels.clear();
        all.clear();
        results.clear();
        total = 0;
    }

    /** Starts over with the words and the kind of message; an empty query with a kind lists that kind. */
    public void search(List<Long> channelIds, String query, TLRPC.MessagesFilter filter) {
        cancel();
        this.query = query == null ? "" : query;
        this.filter = filter;
        for (long channelId : channelIds) {
            ChannelSearch c = new ChannelSearch(channelId);
            channels.put(channelId, c);
            request(c);
        }
    }

    /** Loads the next page of every channel that holds the list back. */
    public void loadMore() {
        int floor = floor();
        for (ChannelSearch c : channels.values()) {
            if (!c.loading && !c.endReached && c.minDate <= floor) {
                request(c);
            }
        }
    }

    private int floor() {
        int floor = 0;
        for (ChannelSearch c : channels.values()) {
            if (!c.endReached) {
                floor = Math.max(floor, c.minDate == Integer.MAX_VALUE ? 0 : c.minDate);
            }
        }
        return floor;
    }

    private void request(ChannelSearch c) {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(-c.channelId);
        req.q = query;
        req.filter = filter != null ? filter : new TLRPC.TL_inputMessagesFilterEmpty();
        req.limit = PAGE;
        req.offset_id = c.offsetId;
        c.loading = true;
        int gen = generation;
        String q = query;
        c.reqId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            ArrayList<MessageObject> objects = new ArrayList<>();
            TLRPC.messages_Messages res = response instanceof TLRPC.messages_Messages ? (TLRPC.messages_Messages) response : null;
            if (res != null) {
                for (TLRPC.Message message : res.messages) {
                    if (message instanceof TLRPC.TL_messageEmpty || message.id <= 0) {
                        continue;
                    }
                    MessageObject object = new MessageObject(account, message, true, true);
                    if (object.hasValidGroupId()) {
                        object.isPrimaryGroupMessage = true;
                    }
                    object.setQuery(q);
                    objects.add(object);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                c.reqId = 0;
                c.loading = false;
                if (res != null) {
                    MessagesStorage.getInstance(account).putUsersAndChats(res.users, res.chats, true, true);
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    c.total = res instanceof TLRPC.TL_messages_messages ? res.messages.size() : res.count;
                    for (MessageObject object : objects) {
                        c.offsetId = c.offsetId == 0 ? object.getId() : Math.min(c.offsetId, object.getId());
                        c.minDate = Math.min(c.minDate, object.messageOwner.date);
                        all.add(object);
                    }
                    if (res.messages.size() < PAGE) {
                        c.endReached = true;
                    }
                } else {
                    c.endReached = true;
                }
                merge();
                listener.onResults();
            });
        });
    }

    private void merge() {
        Collections.sort(all, (a, b) -> {
            if (a.messageOwner.date != b.messageOwner.date) {
                return b.messageOwner.date - a.messageOwner.date;
            }
            if (a.getDialogId() != b.getDialogId()) {
                return Long.compare(b.getDialogId(), a.getDialogId());
            }
            return b.getId() - a.getId();
        });
        results.clear();
        int floor = hasMore() ? floor() : 0;
        for (MessageObject object : all) {
            if (object.messageOwner.date >= floor) {
                results.add(object);
            }
        }
        total = 0;
        for (ChannelSearch c : channels.values()) {
            total += c.total;
        }
    }
}
