package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FeedTest {

    @Test
    public void addChannelKeepsOrderAndRefusesDuplicates() {
        Feed feed = new Feed(1, "News", 0);
        assertTrue(feed.addChannel(10));
        assertTrue(feed.addChannel(20));
        assertFalse(feed.addChannel(10));
        assertEquals(Arrays.asList(10L, 20L), feed.channelIds);
    }

    @Test
    public void removeReturnsPositionAndUndoPutsItBack() {
        Feed feed = new Feed(1, "News", 0);
        feed.addChannel(10);
        feed.addChannel(20);
        feed.addChannel(30);
        int position = feed.removeChannel(20);
        assertEquals(1, position);
        assertEquals(Arrays.asList(10L, 30L), feed.channelIds);
        assertEquals(-1, feed.removeChannel(99));
        assertTrue(feed.addChannel(20, position));
        assertEquals(Arrays.asList(10L, 20L, 30L), feed.channelIds);
    }

    @Test
    public void moveChannelWithinBounds() {
        Feed feed = new Feed(1, "News", 0);
        feed.addChannel(10);
        feed.addChannel(20);
        feed.addChannel(30);
        feed.moveChannel(0, 2);
        assertEquals(Arrays.asList(20L, 30L, 10L), feed.channelIds);
        feed.moveChannel(5, 0);
        assertEquals(Arrays.asList(20L, 30L, 10L), feed.channelIds);
    }

    @Test
    public void moveFeedRenumbersOrders() {
        List<Feed> feeds = new ArrayList<>(Arrays.asList(new Feed(1, "a", 0), new Feed(2, "b", 1), new Feed(3, "c", 2)));
        Feed.move(feeds, 2, 0);
        assertEquals(Arrays.asList(3L, 1L, 2L), ids(feeds));
        assertEquals(Arrays.asList(0, 1, 2), orders(feeds));
    }

    @Test
    public void sortByOrderAndContaining() {
        Feed a = new Feed(1, "a", 2);
        Feed b = new Feed(2, "b", 0);
        Feed c = new Feed(3, "c", 1);
        a.addChannel(7);
        c.addChannel(7);
        List<Feed> feeds = new ArrayList<>(Arrays.asList(a, b, c));
        Feed.sortByOrder(feeds);
        assertEquals(Arrays.asList(2L, 3L, 1L), ids(feeds));
        assertEquals(Arrays.asList(3L, 1L), ids(Feed.containing(feeds, 7)));
        assertTrue(Feed.containing(feeds, 8).isEmpty());
    }

    @Test
    public void copyIsIndependent() {
        Feed a = new Feed(1, "a", 0);
        a.addChannel(1);
        a.filter.minTextLength = 5;
        Feed b = new Feed(a);
        b.addChannel(2);
        b.filter.minTextLength = 9;
        assertEquals(Arrays.asList(1L), a.channelIds);
        assertEquals(5, a.filter.minTextLength);
    }

    private static List<Long> ids(List<Feed> feeds) {
        List<Long> out = new ArrayList<>();
        for (Feed f : feeds) {
            out.add(f.id);
        }
        return out;
    }

    private static List<Integer> orders(List<Feed> feeds) {
        List<Integer> out = new ArrayList<>();
        for (Feed f : feeds) {
            out.add(f.order);
        }
        return out;
    }
}
