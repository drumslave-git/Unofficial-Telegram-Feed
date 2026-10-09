package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.unofficial.telegramfeed.core.FeedFilter.Post;

public class FeedFilterTest {

    private static final Post TEXT = Post.text("a long enough text");
    private static final Post PHOTO = Post.media(FeedFilter.MEDIA_PHOTO, "");
    private static final Post CLIP = Post.video(20, "");
    private static final Post FILM = Post.video(600, "");
    private static final Post GIF = Post.media(FeedFilter.MEDIA_GIF, "");
    private static final Post VOICE = Post.media(FeedFilter.MEDIA_VOICE, "");

    @Test
    public void defaultFilterIsEmptyAndShowsEverything() {
        FeedFilter f = new FeedFilter();
        assertTrue(f.isEmpty());
        for (Post p : Arrays.asList(TEXT, PHOTO, CLIP, GIF, VOICE)) {
            assertTrue(f.allows(p));
        }
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
        assertTrue(f.withMedia(FeedFilter.MEDIA_VOICE, true).isEmpty());
    }

    @Test
    public void aServiceLineShowsOnlyInAFeedThatShowsEverything() {
        Post pin = new Post("", 0, true, 0, 0);
        assertEquals(1, new FeedFilter().shownParts(Arrays.asList(pin), true).size());
        FeedFilter media = new FeedFilter();
        media.mode = FeedFilter.MODE_MEDIA_ONLY;
        FeedFilter text = new FeedFilter();
        text.mode = FeedFilter.MODE_TEXT_ONLY;
        FeedFilter kinds = new FeedFilter();
        kinds.mediaTypes = FeedFilter.MEDIA_OTHER;
        FeedFilter length = new FeedFilter();
        length.minTextLength = 1;
        for (FeedFilter f : Arrays.asList(media, text, kinds, length)) {
            assertTrue(f.shownParts(Arrays.asList(pin), true).isEmpty());
        }
    }

    @Test
    public void mediaPresence() {
        FeedFilter media = new FeedFilter();
        media.mode = FeedFilter.MODE_MEDIA_ONLY;
        assertFalse(media.allows(TEXT));
        assertTrue(media.allows(PHOTO));
        FeedFilter text = new FeedFilter();
        text.mode = FeedFilter.MODE_TEXT_ONLY;
        assertTrue(text.allows(TEXT));
        assertFalse(text.allows(PHOTO));
    }

    @Test
    public void mediaKindsRestrictMediaPostsNotTextPosts() {
        FeedFilter f = new FeedFilter();
        f.mediaTypes = FeedFilter.MEDIA_VIDEO | FeedFilter.MEDIA_GIF;
        assertTrue(f.allows(CLIP));
        assertTrue(f.allows(GIF));
        assertFalse(f.allows(PHOTO));
        assertFalse(f.allows(VOICE));
        assertTrue(f.allows(TEXT));
    }

    @Test
    public void minimumVideoLengthHidesShortVideosOnly() {
        FeedFilter f = new FeedFilter();
        f.minVideoSeconds = 60;
        assertFalse(f.allows(CLIP));
        assertTrue(f.allows(FILM));
        assertTrue(f.allows(GIF));
        assertTrue(f.allows(PHOTO));
    }

    @Test
    public void minimumTextLengthAppliesToPostsWithoutMedia() {
        FeedFilter f = new FeedFilter();
        f.minTextLength = 50;
        assertFalse(f.allows(TEXT));
        StringBuilder x = new StringBuilder();
        for (int i = 0; i < 50; i++) x.append('x');
        assertTrue(f.allows(Post.text(x.toString())));
        assertTrue(f.allows(PHOTO));
    }

    @Test
    public void wholePostsAnAlbumPartRidesAlong() {
        Post albumPhoto = new Post("", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        Post albumClip = new Post("", FeedFilter.MEDIA_VIDEO, false, 20, 7);
        FeedFilter videos = new FeedFilter();
        videos.mediaTypes = FeedFilter.MEDIA_VIDEO;
        assertFalse(videos.allows(albumPhoto));
        List<Post> whole = videos.shownParts(Arrays.asList(albumPhoto, albumClip), true);
        assertEquals(2, whole.size());
        List<Post> parts = videos.shownParts(Arrays.asList(albumPhoto, albumClip), false);
        assertEquals(1, parts.size());
        assertTrue(parts.get(0) == albumClip);
        assertTrue(videos.shownParts(Arrays.asList(albumPhoto), true).isEmpty());
    }

    @Test
    public void textConditionJudgesAllCaptionsTogether() {
        FeedFilter bitcoin = new FeedFilter();
        bitcoin.words = "bitcoin";
        Post part1 = new Post("", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        Post part2 = new Post("Bitcoin is up", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        assertEquals(2, bitcoin.shownParts(Arrays.asList(part1, part2), true).size());
        assertTrue(bitcoin.shownParts(Arrays.asList(Post.text("no coins here")), true).isEmpty());
        assertTrue(bitcoin.allows(part1));
        assertFalse(bitcoin.allows(Post.text("gold")));
        FeedFilter noAds = new FeedFilter();
        noAds.words = "NOT ~#ad";
        assertTrue(noAds.allows(Post.text("plain news")));
        assertFalse(noAds.allows(Post.text("buy now #ad")));
        FeedFilter broken = new FeedFilter();
        broken.words = "(a OR";
        assertTrue(broken.condition() == null);
        assertTrue(broken.isEmpty());
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
