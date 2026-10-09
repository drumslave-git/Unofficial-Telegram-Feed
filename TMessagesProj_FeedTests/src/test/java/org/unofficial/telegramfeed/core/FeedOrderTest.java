package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class FeedOrderTest {

    private static FeedOrder.Key key(long channel, int id, int date) {
        return new FeedOrder.Key(channel, id, date);
    }

    @Test
    public void ordersByDateThenChannelThenId() {
        List<FeedOrder.Key> posts = new ArrayList<>(Arrays.asList(
                key(2, 10, 300),
                key(1, 5, 200),
                key(1, 7, 200),
                key(3, 1, 100),
                key(2, 9, 200)));
        Collections.sort(posts, FeedOrder.OLDEST_FIRST);
        assertEquals(Arrays.asList("3:1", "1:5", "1:7", "2:9", "2:10"), labels(posts));
    }

    @Test
    public void sameChannelKeepsMessageIdOrderWithinOneSecond() {
        List<FeedOrder.Key> posts = new ArrayList<>(Arrays.asList(key(1, 3, 50), key(1, 2, 50), key(1, 1, 50)));
        Collections.sort(posts, FeedOrder.OLDEST_FIRST);
        assertEquals(Arrays.asList("1:1", "1:2", "1:3"), labels(posts));
    }

    @Test
    public void equalKeysCompareAsEqual() {
        assertEquals(0, FeedOrder.OLDEST_FIRST.compare(key(4, 8, 15), key(4, 8, 15)));
    }

    private static List<String> labels(List<FeedOrder.Key> posts) {
        List<String> out = new ArrayList<>();
        for (FeedOrder.Key k : posts) {
            out.add(k.channelId + ":" + k.messageId);
        }
        return out;
    }
}
