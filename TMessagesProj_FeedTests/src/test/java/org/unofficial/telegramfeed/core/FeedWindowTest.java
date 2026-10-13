package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class FeedWindowTest {

    private static FeedWindow.Channel run(int minDate, int maxDate, boolean olderEnd, boolean hasNewer) {
        FeedWindow.Channel channel = new FeedWindow.Channel();
        channel.loaded = true;
        channel.include(minDate);
        channel.include(maxDate);
        channel.olderEnd = olderEnd;
        channel.hasNewer = hasNewer;
        return channel;
    }

    @Test
    public void floorIsTheLatestStartWithOlderHistory() {
        FeedWindow.Channel a = run(100, 500, false, false);
        FeedWindow.Channel b = run(300, 400, false, true);
        FeedWindow.Channel ended = run(450, 600, true, false);
        assertEquals(300, FeedWindow.floor(Arrays.asList(a, b, ended)));
    }

    @Test
    public void ceilingIsTheEarliestEndWithNewerHistory() {
        FeedWindow.Channel a = run(100, 500, false, true);
        FeedWindow.Channel b = run(300, 400, false, true);
        FeedWindow.Channel newest = run(50, 350, false, false);
        assertEquals(400, FeedWindow.ceiling(Arrays.asList(a, b, newest)));
        assertEquals(Integer.MAX_VALUE, FeedWindow.ceiling(Collections.singletonList(newest)));
    }

    @Test
    public void channelsStillLoadingDoNotHoldTheListBack() {
        FeedWindow.Channel loading = new FeedWindow.Channel();
        FeedWindow.Channel a = run(100, 500, false, true);
        assertEquals(100, FeedWindow.floor(Arrays.asList(loading, a)));
        assertEquals(500, FeedWindow.ceiling(Arrays.asList(loading, a)));
    }

    @Test
    public void anEmptyRunHasNothingBeyondIt() {
        FeedWindow.Channel empty = new FeedWindow.Channel();
        empty.loaded = true;
        empty.hasNewer = true;
        empty.settle();
        assertTrue(empty.olderEnd);
        assertFalse(empty.hasNewer);
        assertEquals(0, FeedWindow.floor(Collections.singletonList(empty)));
        assertTrue(empty.covers(1234));
    }

    @Test
    public void coversNeedsTheRunToReachTheDateOnBothSides() {
        assertTrue(run(100, 500, false, true).covers(300));
        assertFalse(run(400, 500, false, true).covers(300));
        assertTrue(run(400, 500, true, true).covers(300));
        assertFalse(run(100, 200, false, true).covers(300));
        assertTrue(run(100, 200, false, false).covers(300));
        FeedWindow.Channel notLoaded = new FeedWindow.Channel();
        assertFalse(notLoaded.covers(300));
    }

    @Test
    public void forgetStartsOver() {
        FeedWindow.Channel a = run(100, 500, true, true);
        a.forget();
        assertFalse(a.loaded);
        assertFalse(a.olderEnd);
        assertFalse(a.hasNewer);
        assertEquals(Integer.MAX_VALUE, a.minDate);
        assertEquals(0, a.maxDate);
    }
}
