package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The feeds and rules of one account as one value: what the copy in Saved Messages holds.
 * Feeds are in their order, rules by id.
 */
public final class FeedsConfig {

    public final List<Feed> feeds = new ArrayList<>();
    public final List<Rule> rules = new ArrayList<>();

    public FeedsConfig() {
    }

    /** Copies of the feeds and rules, the feeds renumbered in their order and the rules sorted by id. */
    public FeedsConfig(List<Feed> feeds, List<Rule> rules) {
        for (Feed feed : feeds) {
            this.feeds.add(new Feed(feed));
        }
        Feed.renumber(this.feeds);
        for (Rule rule : rules) {
            this.rules.add(new Rule(rule));
        }
        Collections.sort(this.rules, Comparator.comparingLong(r -> r.id));
    }

    public boolean isEmpty() {
        return feeds.isEmpty() && rules.isEmpty();
    }

    /** True when both hold the same feeds and rules, ids included. */
    public boolean sameAs(FeedsConfig other) {
        if (feeds.size() != other.feeds.size() || !rules.equals(other.rules)) {
            return false;
        }
        for (int i = 0; i < feeds.size(); i++) {
            Feed a = feeds.get(i);
            if (a.id != other.feeds.get(i).id || !sameFeed(a, other.feeds.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * This config with the other's feeds and rules added after its own: the other's feeds and
     * rules get the next free ids, and those identical in content to one of this config's are
     * left out. A rule the other scopes by a feed left out is scoped by its twin here.
     */
    public FeedsConfig merge(FeedsConfig other) {
        FeedsConfig out = new FeedsConfig(feeds, rules);
        long nextFeedId = 1;
        for (Feed feed : out.feeds) {
            nextFeedId = Math.max(nextFeedId, feed.id + 1);
        }
        List<long[]> feedIds = new ArrayList<>();
        for (Feed feed : other.feeds) {
            Feed twin = null;
            for (Feed mine : out.feeds) {
                if (sameFeed(mine, feed)) {
                    twin = mine;
                    break;
                }
            }
            if (twin == null) {
                twin = new Feed(feed);
                twin.id = nextFeedId++;
                out.feeds.add(twin);
            }
            feedIds.add(new long[]{feed.id, twin.id});
        }
        Feed.renumber(out.feeds);
        long nextRuleId = 1;
        for (Rule rule : out.rules) {
            nextRuleId = Math.max(nextRuleId, rule.id + 1);
        }
        for (Rule rule : other.rules) {
            Rule copy = new Rule(rule);
            if (copy.feedId != 0) {
                for (long[] ids : feedIds) {
                    if (ids[0] == copy.feedId) {
                        copy.feedId = ids[1];
                        break;
                    }
                }
            }
            boolean twin = false;
            for (Rule mine : out.rules) {
                if (sameRule(mine, copy)) {
                    twin = true;
                    break;
                }
            }
            if (!twin) {
                copy.id = nextRuleId++;
                out.rules.add(copy);
            }
        }
        return out;
    }

    /** Equal in everything but id and position. */
    static boolean sameFeed(Feed a, Feed b) {
        return Objects.equals(a.name, b.name) && a.channelIds.equals(b.channelIds) && a.filter.equals(b.filter)
                && a.showMinimized == b.showMinimized && a.showWholePost == b.showWholePost;
    }

    /** Equal in everything but id and creation time. */
    static boolean sameRule(Rule a, Rule b) {
        return a.enabled == b.enabled && a.channelId == b.channelId && a.feedId == b.feedId && a.priority == b.priority
                && a.readAloud == b.readAloud && Objects.equals(a.name, b.name)
                && Objects.equals(a.condition, b.condition) && Objects.equals(a.schedule, b.schedule);
    }
}
