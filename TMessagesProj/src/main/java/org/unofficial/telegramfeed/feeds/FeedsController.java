package org.unofficial.telegramfeed.feeds;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.unofficial.telegramfeed.core.Feed;

import java.util.ArrayList;
import java.util.List;

/**
 * The feeds of one account, kept in memory on the UI thread and written through to
 * {@link FeedsStorage}. Every change posts {@link NotificationCenter#tgfeedFeedsChanged}.
 */
public class FeedsController extends BaseController {

    private static final FeedsController[] Instance = new FeedsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static FeedsController getInstance(int num) {
        FeedsController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new FeedsController(num);
                }
            }
        }
        return localInstance;
    }

    private final FeedsStorage storage;
    private final ArrayList<Feed> feeds = new ArrayList<>();
    private boolean loaded;
    private boolean loading;

    public FeedsController(int account) {
        super(account);
        storage = new FeedsStorage(account);
    }

    /** Loads the feeds once from the file; posts {@code tgfeedFeedsChanged} when they are in. */
    public void loadFeeds() {
        if (loaded || loading) {
            return;
        }
        loading = true;
        storage.loadFeeds(result -> {
            feeds.clear();
            feeds.addAll(result);
            loaded = true;
            loading = false;
            getNotificationCenter().postNotificationName(NotificationCenter.tgfeedFeedsChanged);
        });
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** The feeds in their order. The list is the controller's own; do not change it. */
    public List<Feed> getFeeds() {
        loadFeeds();
        return feeds;
    }

    public Feed getFeed(long feedId) {
        for (Feed feed : feeds) {
            if (feed.id == feedId) {
                return feed;
            }
        }
        return null;
    }

    public List<Feed> getFeedsOfChannel(long channelId) {
        return Feed.containing(feeds, channelId);
    }

    /** True when any feed has the channel. */
    public boolean isChannelInAnyFeed(long channelId) {
        for (Feed feed : feeds) {
            if (feed.hasChannel(channelId)) {
                return true;
            }
        }
        return false;
    }

    public Feed createFeed(String name, List<Long> channelIds) {
        long id = 1;
        for (Feed feed : feeds) {
            id = Math.max(id, feed.id + 1);
        }
        Feed feed = new Feed(id, name, feeds.size());
        for (long channelId : channelIds) {
            feed.addChannel(channelId);
        }
        feeds.add(feed);
        storage.saveFeed(feed);
        changed();
        return feed;
    }

    /** Writes the feed as it is now (name, channels, filter, switches). */
    public void updateFeed(Feed feed) {
        if (getFeed(feed.id) == null) {
            return;
        }
        storage.saveFeed(feed);
        changed();
    }

    public void deleteFeed(long feedId) {
        Feed feed = getFeed(feedId);
        if (feed == null) {
            return;
        }
        feeds.remove(feed);
        Feed.renumber(feeds);
        storage.deleteFeed(feedId);
        storage.saveOrder(feeds);
        changed();
    }

    public void moveFeed(int from, int to) {
        Feed.move(feeds, from, to);
        storage.saveOrder(feeds);
        changed();
    }

    private void changed() {
        getNotificationCenter().postNotificationName(NotificationCenter.tgfeedFeedsChanged);
    }

    /** The account logged out: forgets the feeds and deletes the file. */
    public void cleanup() {
        feeds.clear();
        loaded = false;
        loading = false;
        storage.cleanup();
        changed();
    }
}
