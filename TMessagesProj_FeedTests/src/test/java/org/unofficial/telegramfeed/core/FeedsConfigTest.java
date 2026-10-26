package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FeedsConfigTest {

    private static Feed feed(long id, String name, Long... channels) {
        Feed feed = new Feed(id, name, 0);
        for (long channel : channels) {
            feed.addChannel(channel);
        }
        return feed;
    }

    private static Rule rule(long id, long channelId, long feedId, String condition, long createdAt) {
        Rule rule = new Rule();
        rule.id = id;
        rule.channelId = channelId;
        rule.feedId = feedId;
        rule.condition = condition;
        rule.createdAt = createdAt;
        return rule;
    }

    private static FeedsConfig config(List<Feed> feeds, Rule... rules) {
        return new FeedsConfig(feeds, Arrays.asList(rules));
    }

    @Test
    public void copiesAreRenumberedAndSortedById() {
        List<Feed> feeds = Arrays.asList(feed(5, "B", 1L), feed(2, "A", 2L));
        FeedsConfig config = config(feeds, rule(3, 1, 0, "x", 10), rule(1, 2, 0, "y", 10));
        assertEquals(0, config.feeds.get(0).order);
        assertEquals(1, config.feeds.get(1).order);
        assertEquals(5, config.feeds.get(0).id);
        assertEquals(1, config.rules.get(0).id);
        assertEquals(3, config.rules.get(1).id);
        assertTrue(config.sameAs(config(feeds, rule(1, 2, 0, "y", 10), rule(3, 1, 0, "x", 10))));
    }

    @Test
    public void sameAsSeesEveryDifference() {
        List<Feed> feeds = Arrays.asList(feed(1, "News", 1L, 2L));
        FeedsConfig base = config(feeds, rule(1, 1, 1, "x", 10));
        assertFalse(base.sameAs(config(Arrays.asList(feed(1, "News", 2L, 1L)), rule(1, 1, 1, "x", 10))));
        assertFalse(base.sameAs(config(Arrays.asList(feed(2, "News", 1L, 2L)), rule(1, 1, 1, "x", 10))));
        assertFalse(base.sameAs(config(feeds, rule(1, 1, 1, "x", 11))));
        assertFalse(base.sameAs(config(feeds)));
        assertTrue(new FeedsConfig().isEmpty());
        assertFalse(base.isEmpty());
    }

    @Test
    public void mergeAddsTheOthersAfterItsOwnWithFreeIds() {
        FeedsConfig copy = config(Arrays.asList(feed(1, "News", 1L), feed(3, "Deals", 2L)), rule(1, 1, 0, "a", 10), rule(4, 2, 3, "b", 10));
        FeedsConfig local = config(Arrays.asList(feed(1, "Alerts", 5L)), rule(1, 5, 1, "c", 20), rule(2, 5, 0, "", 20));
        FeedsConfig merged = copy.merge(local);

        assertEquals(3, merged.feeds.size());
        Feed alerts = merged.feeds.get(2);
        assertEquals("Alerts", alerts.name);
        assertEquals(4, alerts.id);
        assertEquals(2, alerts.order);
        assertEquals(4, merged.rules.size());
        Rule scoped = merged.rules.get(2);
        assertEquals(5, scoped.id);
        assertEquals(4, scoped.feedId);
        assertEquals(20, scoped.createdAt);
        assertEquals(6, merged.rules.get(3).id);
        assertEquals(0, merged.rules.get(3).feedId);
    }

    @Test
    public void mergeLeavesOutTwinsAndScopesByTheTwinFeed() {
        Feed news = feed(1, "News", 1L, 2L);
        FeedsConfig copy = config(Arrays.asList(news), rule(1, 1, 1, "a", 10));
        Feed sameNews = feed(7, "News", 1L, 2L);
        FeedsConfig local = config(Arrays.asList(sameNews), rule(3, 1, 7, "a", 99), rule(4, 2, 7, "b", 99));
        FeedsConfig merged = copy.merge(local);

        assertEquals(1, merged.feeds.size());
        assertEquals(2, merged.rules.size());
        assertEquals(1, merged.rules.get(0).id);
        assertEquals(10, merged.rules.get(0).createdAt);
        assertEquals(2, merged.rules.get(1).id);
        assertEquals(1, merged.rules.get(1).feedId);
    }

    @Test
    public void mergeKeepsBothWhenOnlyTheFilterDiffers() {
        Feed media = feed(1, "News", 1L);
        media.filter.mode = FeedFilter.MODE_MEDIA_ONLY;
        FeedsConfig merged = config(Arrays.asList(media)).merge(config(Arrays.asList(feed(1, "News", 1L))));
        assertEquals(2, merged.feeds.size());
        assertEquals(new ArrayList<>(Arrays.asList(1L, 2L)), Arrays.asList(merged.feeds.get(0).id, merged.feeds.get(1).id));
    }
}
