package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.unofficial.telegramfeed.core.FeedFilter.Post;

public class RuleMatcherTest {

    private static final long CH1 = 1;
    private static final long CH2 = 2;
    private static final Calendar NOW = monday(10, 0);

    private static Calendar monday(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.SEPTEMBER, 14, hour, minute, 0); // 2026-09-14 is a Monday
        return c;
    }

    private static Rule rule(long id, long channel, String condition) {
        Rule r = new Rule();
        r.id = id;
        r.name = "r" + id;
        r.channelId = channel;
        r.condition = condition;
        return r;
    }

    private static List<Rule> rules(Rule... r) {
        return Arrays.asList(r);
    }

    private static final Map<Long, RuleMatcher.FeedScope> NO_FEEDS = Collections.emptyMap();

    private static List<Long> ids(RuleMatcher.Match m) {
        List<Long> out = new ArrayList<>();
        for (Rule r : m.rules) out.add(r.id);
        return out;
    }

    @Test
    public void channelScopePriorityMergeAndReadAloud() {
        Rule silent = rule(1, CH1, "btc");
        silent.priority = Rule.PRIORITY_SILENT;
        Rule urgent = rule(2, CH1, "bitcoin OR btc");
        urgent.priority = Rule.PRIORITY_URGENT;
        urgent.readAloud = true;
        Rule other = rule(3, CH2, "eth");
        Rule off = rule(4, CH1, "btc");
        off.enabled = false;
        off.priority = Rule.PRIORITY_URGENT;
        RuleMatcher m = new RuleMatcher();
        List<Rule> all = rules(silent, urgent, other, off);

        RuleMatcher.Match m1 = m.evaluate(all, NO_FEEDS, CH1, Post.text("BTC up"), 1, NOW);
        assertEquals(Arrays.asList(1L, 2L), ids(m1));
        assertEquals(Rule.PRIORITY_URGENT, m1.priority);
        assertTrue(m1.readAloud);
        assertEquals(Arrays.asList("r2", "r1"), m1.ruleNames());

        assertNull(m.evaluate(all, NO_FEEDS, CH2, Post.text("btc up"), 1, NOW)); // rule 1 watches CH1 only
        assertEquals(Arrays.asList(3L), ids(m.evaluate(all, NO_FEEDS, CH2, Post.text("eth"), 1, NOW)));
        assertNull(m.evaluate(all, NO_FEEDS, CH1, Post.text("nothing here"), 1, NOW));
        assertNull(m.evaluate(all, NO_FEEDS, CH1, Post.media(FeedFilter.MEDIA_PHOTO, ""), 1, NOW)); // no caption
    }

    @Test
    public void aServiceLineIsNotAPost() {
        Rule every = rule(1, CH1, "");
        assertTrue(every.matchesEverything());
        RuleMatcher m = new RuleMatcher();
        assertNotNull(m.evaluate(rules(every), NO_FEEDS, CH1, Post.media(FeedFilter.MEDIA_PHOTO, ""), 1, NOW));
        assertNull(m.evaluate(rules(every), NO_FEEDS, CH1, new Post("", 0, true, 0, 0), 1, NOW));
    }

    @Test
    public void aRuleWithNoConditionNotifiesAboutAPostWithoutText() {
        RuleMatcher m = new RuleMatcher();
        Post photo = Post.media(FeedFilter.MEDIA_PHOTO, "");
        assertEquals(Arrays.asList(1L), ids(m.evaluate(rules(rule(1, CH1, "")), NO_FEEDS, CH1, photo, 1, NOW)));
        assertNull(m.evaluate(rules(rule(2, CH1, "btc")), NO_FEEDS, CH1, photo, 1, NOW));
    }

    @Test
    public void aBrokenConditionMatchesNothing() {
        Rule broken = rule(1, CH1, "(btc OR");
        assertTrue(broken.isBroken());
        assertFalse(rule(2, CH1, "").isBroken());
        assertNull(new RuleMatcher().evaluate(rules(broken), NO_FEEDS, CH1, Post.text("btc"), 1, NOW));
    }

    @Test
    public void aRuleMatchesOnlyPostsFromItsCreationOn() {
        Rule r = rule(1, CH1, "btc");
        r.createdAt = 100;
        RuleMatcher m = new RuleMatcher();
        assertNull(m.evaluate(rules(r), NO_FEEDS, CH1, Post.text("btc"), 99, NOW));
        assertNotNull(m.evaluate(rules(r), NO_FEEDS, CH1, Post.text("btc"), 100, NOW));
    }

    @Test
    public void theScheduleIsJudgedAtTheGivenMoment() {
        Rule r = rule(1, CH1, "x");
        r.schedule = new Schedule(new HashSet<>(Arrays.asList(1)), 9 * 60, 12 * 60);
        RuleMatcher m = new RuleMatcher();
        assertNotNull(m.evaluate(rules(r), NO_FEEDS, CH1, Post.text("x"), 1, monday(10, 0)));
        assertNull(m.evaluate(rules(r), NO_FEEDS, CH1, Post.text("x"), 1, monday(13, 0)));
        Calendar tuesday = monday(10, 0);
        tuesday.add(Calendar.DAY_OF_MONTH, 1);
        assertNull(m.evaluate(rules(r), NO_FEEDS, CH1, Post.text("x"), 1, tuesday));
    }

    private static Map<Long, RuleMatcher.FeedScope> feeds(Object... pairs) {
        Map<Long, RuleMatcher.FeedScope> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Long) pairs[i], (RuleMatcher.FeedScope) pairs[i + 1]);
        }
        return map;
    }

    private static RuleMatcher.FeedScope scope(FeedFilter filter, boolean wholePost, Long... channels) {
        return new RuleMatcher.FeedScope(Arrays.asList(channels), filter, wholePost);
    }

    @Test
    public void aRuleScopedByAFeedSeesOnlyWhatTheFeedShows() {
        FeedFilter mediaOnly = new FeedFilter();
        mediaOnly.mode = FeedFilter.MODE_MEDIA_ONLY;
        Rule scoped = rule(1, CH1, "btc");
        scoped.feedId = 10;
        Rule free = rule(2, CH1, "btc");
        RuleMatcher m = new RuleMatcher();
        Map<Long, RuleMatcher.FeedScope> f = feeds(10L, scope(mediaOnly, true, CH1));
        assertEquals(Arrays.asList(2L), ids(m.evaluate(rules(scoped, free), f, CH1, Post.text("btc up"), 1, NOW)));
        assertEquals(Arrays.asList(1L, 2L), ids(m.evaluate(rules(scoped, free), f, CH1, Post.media(FeedFilter.MEDIA_PHOTO, "btc up"), 1, NOW)));
    }

    @Test
    public void aRuleOfAFeedThatIsGoneOrNoLongerHoldsTheChannelSeesNothing() {
        Rule scoped = rule(1, CH1, "btc");
        scoped.feedId = 10;
        RuleMatcher m = new RuleMatcher();
        assertNull(m.evaluate(rules(scoped), NO_FEEDS, CH1, Post.text("btc"), 1, NOW));
        assertNull(m.evaluate(rules(scoped), feeds(10L, scope(new FeedFilter(), true, CH2)), CH1, Post.text("btc"), 1, NOW));
        assertNotNull(m.evaluate(rules(scoped), feeds(10L, scope(new FeedFilter(), true, CH1)), CH1, Post.text("btc"), 1, NOW));
    }

    @Test
    public void aPostTheFeedsWordsLeaveOutRaisesNothing() {
        FeedFilter noAds = new FeedFilter();
        noAds.words = "NOT ~#ad";
        Rule scoped = rule(1, CH1, "btc");
        scoped.feedId = 10;
        RuleMatcher m = new RuleMatcher();
        Map<Long, RuleMatcher.FeedScope> f = feeds(10L, scope(noAds, true, CH1));
        assertNotNull(m.evaluate(rules(scoped), f, CH1, Post.text("btc up"), 1, NOW));
        assertNull(m.evaluate(rules(scoped), f, CH1, Post.text("btc up #ad"), 1, NOW));
    }

    @Test
    public void theCaptionPartOfAnAlbumNotifiesWhenTheFeedShowsWholeAlbums() {
        FeedFilter videos = new FeedFilter();
        videos.mediaTypes = FeedFilter.MEDIA_VIDEO;
        Rule inWhole = rule(1, CH1, "btc");
        inWhole.feedId = 10;
        Rule inParts = rule(2, CH1, "btc");
        inParts.feedId = 20;
        Post caption = new Post("btc up", FeedFilter.MEDIA_PHOTO, false, 0, 9);
        RuleMatcher m = new RuleMatcher();
        Map<Long, RuleMatcher.FeedScope> f = feeds(10L, scope(videos, true, CH1), 20L, scope(videos, false, CH1));
        assertEquals(Arrays.asList(1L), ids(m.evaluate(rules(inWhole, inParts), f, CH1, caption, 1, NOW)));
    }

    @Test
    public void anAlbumIsOnePostNamedByItsCaptionPart() {
        Rule every = rule(1, CH1, "");
        Rule quay = rule(2, CH1, "quay");
        Post p10 = new Post("", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        Post p11 = new Post("Three views of the quay", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        Post p12 = new Post("", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        RuleMatcher m = new RuleMatcher();
        RuleMatcher.Match match = m.evaluateAlbum(rules(every, quay), NO_FEEDS, CH1, Arrays.asList(p12, p11, p10), Arrays.asList(12, 11, 10), 1, NOW);
        assertEquals(Arrays.asList(1L, 2L), ids(match));
        assertEquals(11, match.messageId);

        RuleMatcher.Match noCaption = m.evaluateAlbum(rules(every, quay), NO_FEEDS, CH1, Arrays.asList(p12, p10), Arrays.asList(12, 10), 1, NOW);
        assertEquals(Arrays.asList(1L), ids(noCaption));
        assertEquals(10, noCaption.messageId);
    }

    @Test
    public void anAlbumItsFeedHidesRaisesNothing() {
        FeedFilter textOnly = new FeedFilter();
        textOnly.mode = FeedFilter.MODE_TEXT_ONLY;
        Rule every = rule(1, CH1, "");
        every.feedId = 10;
        Post a = new Post("", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        Post b = new Post("", FeedFilter.MEDIA_PHOTO, false, 0, 7);
        assertNull(new RuleMatcher().evaluateAlbum(rules(every), feeds(10L, scope(textOnly, true, CH1)), CH1, Arrays.asList(a, b), Arrays.asList(1, 2), 1, NOW));
    }

    @Test
    public void theMatchOpensInTheFeedOfTheDecidingRule() {
        Rule low = rule(1, CH1, "btc");
        low.feedId = 10;
        Rule high = rule(2, CH1, "btc");
        high.feedId = 20;
        high.priority = Rule.PRIORITY_URGENT;
        Map<Long, RuleMatcher.FeedScope> f = feeds(10L, scope(new FeedFilter(), true, CH1), 20L, scope(new FeedFilter(), true, CH1));
        assertEquals(20, new RuleMatcher().evaluate(rules(low, high), f, CH1, Post.text("btc"), 1, NOW).feedId());
    }

    @Test
    public void copiesAndEquality() {
        Rule r = rule(1, CH1, "btc");
        r.schedule = Schedule.decode("1,2|09:00|18:00");
        Rule copy = new Rule(r);
        assertEquals(r, copy);
        assertEquals(r.hashCode(), copy.hashCode());
        copy.priority = Rule.PRIORITY_URGENT;
        assertFalse(r.equals(copy));
    }
}
