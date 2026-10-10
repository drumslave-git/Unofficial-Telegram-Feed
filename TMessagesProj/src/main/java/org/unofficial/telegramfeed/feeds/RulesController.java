package org.unofficial.telegramfeed.feeds;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedFilter;
import org.unofficial.telegramfeed.core.Rule;
import org.unofficial.telegramfeed.core.RuleMatcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The rules of one account, kept in memory on the UI thread and written through to the
 * fork's SQLite file ({@link FeedsStorage}, shared with the feeds). Every change posts
 * {@link NotificationCenter#tgfeedRulesChanged}.
 */
public class RulesController extends BaseController {

    private static final RulesController[] Instance = new RulesController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static RulesController getInstance(int num) {
        RulesController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new RulesController(num);
                }
            }
        }
        return localInstance;
    }

    private final ArrayList<Rule> rules = new ArrayList<>();
    private final RuleMatcher matcher = new RuleMatcher();
    private boolean loaded;
    private boolean loading;

    /** The rules and feeds as other threads see them: copies, replaced whole on every change. */
    public static final class Snapshot {
        public final List<Rule> rules;
        public final Map<Long, RuleMatcher.FeedScope> feeds;

        Snapshot(List<Rule> rules, Map<Long, RuleMatcher.FeedScope> feeds) {
            this.rules = rules;
            this.feeds = feeds;
        }

        public boolean hasEnabledRules(long channelId) {
            for (Rule rule : rules) {
                if (rule.enabled && rule.channelId == channelId) {
                    return true;
                }
            }
            return false;
        }
    }

    private volatile Snapshot snapshot;
    private final List<CountDownLatch> snapshotWaiters = new ArrayList<>();

    public RulesController(int account) {
        super(account);
    }

    private FeedsStorage storage() {
        return FeedsController.getInstance(currentAccount).storage;
    }

    /** Loads the rules once from the file; posts {@code tgfeedRulesChanged} when they are in. */
    public void loadRules() {
        if (loaded || loading) {
            return;
        }
        loading = true;
        storage().loadRules(result -> {
            rules.clear();
            rules.addAll(result);
            loaded = true;
            loading = false;
            changed();
        });
    }

    /**
     * Builds the snapshot for other threads once both the rules and the feeds are in. Called on
     * the UI thread after every change of either.
     */
    public void publish() {
        FeedsController feedsController = FeedsController.getInstance(currentAccount);
        if (!loaded || !feedsController.isLoaded()) {
            return;
        }
        List<Rule> copies = new ArrayList<>();
        for (Rule rule : rules) {
            copies.add(new Rule(rule));
        }
        Map<Long, RuleMatcher.FeedScope> scopes = new HashMap<>();
        for (Feed feed : feedsController.getFeeds()) {
            scopes.put(feed.id, new RuleMatcher.FeedScope(new ArrayList<>(feed.channelIds), new FeedFilter(feed.filter), feed.showWholePost));
        }
        snapshot = new Snapshot(Collections.unmodifiableList(copies), Collections.unmodifiableMap(scopes));
        synchronized (snapshotWaiters) {
            for (CountDownLatch latch : snapshotWaiters) {
                latch.countDown();
            }
            snapshotWaiters.clear();
        }
    }

    /**
     * The snapshot for a background thread; loads the rules and feeds when needed and waits for
     * them up to {@code timeoutMs}. Null when they did not come in time. Never call it on the UI thread.
     */
    public Snapshot awaitSnapshot(long timeoutMs) {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (snapshotWaiters) {
            snapshotWaiters.add(latch);
        }
        AndroidUtilities.runOnUIThread(() -> {
            FeedsController.getInstance(currentAccount).loadFeeds();
            loadRules();
            publish();
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
        }
        return snapshot;
    }

    /** Matches a Telegram message of a channel against a snapshot; null when no rule matches. */
    public RuleMatcher.Match match(Snapshot snapshot, long channelId, MessageObject message) {
        long date = message.messageOwner.date;
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(date * 1000L);
        return matcher.evaluate(snapshot.rules, snapshot.feeds, channelId, PostFilter.describe(message), date, when);
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** Every rule, oldest first. The list is the controller's own; do not change it. */
    public List<Rule> getRules() {
        loadRules();
        return rules;
    }

    public Rule getRule(long id) {
        for (Rule rule : rules) {
            if (rule.id == id) {
                return rule;
            }
        }
        return null;
    }

    public List<Rule> getRulesOfChannel(long channelId) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : getRules()) {
            if (rule.channelId == channelId) {
                out.add(rule);
            }
        }
        return out;
    }

    public List<Rule> getRulesOfFeed(long feedId) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : getRules()) {
            if (rule.feedId == feedId) {
                out.add(rule);
            }
        }
        return out;
    }

    /** True when the channel has at least one enabled rule: then only matching posts notify. */
    public boolean hasEnabledRules(long channelId) {
        for (Rule rule : getRules()) {
            if (rule.enabled && rule.channelId == channelId) {
                return true;
            }
        }
        return false;
    }

    /** Adds the rule with the next free id and the current time as its creation, and returns it. */
    public Rule createRule(Rule template) {
        long id = 1;
        for (Rule rule : rules) {
            id = Math.max(id, rule.id + 1);
        }
        Rule rule = new Rule(template);
        rule.id = id;
        rule.createdAt = System.currentTimeMillis() / 1000;
        rules.add(rule);
        storage().saveRule(rule);
        changed();
        return rule;
    }

    /** Writes the rule as it is now. */
    public void updateRule(Rule rule) {
        Rule current = getRule(rule.id);
        if (current == null) {
            return;
        }
        if (current != rule) {
            rules.set(rules.indexOf(current), rule);
        }
        storage().saveRule(rule);
        changed();
    }

    public void deleteRule(long id) {
        Rule rule = getRule(id);
        if (rule == null) {
            return;
        }
        rules.remove(rule);
        List<Long> ids = new ArrayList<>();
        ids.add(id);
        storage().deleteRules(ids);
        changed();
    }

    /** Deletes the rules a feed scopes (the feed is being deleted); returns how many went. */
    public int deleteRulesOfFeed(long feedId) {
        List<Long> ids = new ArrayList<>();
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).feedId == feedId) {
                ids.add(rules.remove(i).id);
            }
        }
        if (!ids.isEmpty()) {
            storage().deleteRules(ids);
            changed();
        }
        return ids.size();
    }

    /** The feeds as their rules see them, by id. */
    public Map<Long, RuleMatcher.FeedScope> feedScopes() {
        Map<Long, RuleMatcher.FeedScope> scopes = new HashMap<>();
        for (Feed feed : FeedsController.getInstance(currentAccount).getFeeds()) {
            scopes.put(feed.id, RuleMatcher.FeedScope.of(feed));
        }
        return scopes;
    }

    /** Matches one post of a channel; null when no rule matches. */
    public RuleMatcher.Match match(long channelId, FeedFilter.Post post, long postDate) {
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(postDate * 1000L);
        return matcher.evaluate(getRules(), feedScopes(), channelId, post, postDate, when);
    }

    /** Matches an album of a channel as one post; null when no rule matches. */
    public RuleMatcher.Match matchAlbum(long channelId, List<FeedFilter.Post> parts, List<Integer> messageIds, long postDate) {
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(postDate * 1000L);
        return matcher.evaluateAlbum(getRules(), feedScopes(), channelId, parts, messageIds, postDate, when);
    }

    private void changed() {
        publish();
        getNotificationCenter().postNotificationName(NotificationCenter.tgfeedRulesChanged);
    }

    /** The account logged out: forgets the rules (the file goes with the feeds'). */
    public void cleanup() {
        rules.clear();
        loaded = false;
        loading = false;
        snapshot = null;
        changed();
    }
}
