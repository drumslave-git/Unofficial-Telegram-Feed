package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedFilterTest {

    @Test
    public void defaultFilterIsEmpty() {
        FeedFilter f = new FeedFilter();
        assertTrue(f.isEmpty());
        assertTrue(f.allowsMedia(FeedFilter.MEDIA_PHOTO));
        assertTrue(f.allowsMedia(FeedFilter.MEDIA_GIF));
    }

    @Test
    public void anyChangeMakesItNonEmpty() {
        FeedFilter f = new FeedFilter();
        f.minTextLength = 1;
        assertFalse(f.isEmpty());
        f = new FeedFilter();
        f.words = "news";
        assertFalse(f.isEmpty());
        f = new FeedFilter().withMedia(FeedFilter.MEDIA_VOICE, false);
        assertFalse(f.isEmpty());
        assertFalse(f.allowsMedia(FeedFilter.MEDIA_VOICE));
        assertTrue(f.allowsMedia(FeedFilter.MEDIA_PHOTO));
        assertTrue(f.withMedia(FeedFilter.MEDIA_VOICE, true).isEmpty());
    }

    @Test
    public void equalityCoversEveryField() {
        FeedFilter a = new FeedFilter();
        FeedFilter b = new FeedFilter(a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.mode = FeedFilter.MODE_TEXT_ONLY;
        assertNotEquals(a, b);
    }
}
