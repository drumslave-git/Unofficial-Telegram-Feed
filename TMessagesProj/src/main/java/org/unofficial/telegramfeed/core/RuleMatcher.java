package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Matches the rules of a channel against a new post. Pure: the caller passes the rules, the
 * feeds that scope them and the moment the post came. A service line is not a post; a post
 * without text matches only a rule with no condition; a rule scoped by a feed sees only what
 * that feed shows; a rule matches only posts from its creation on and only while its
 * schedule is active.
 */
public final class RuleMatcher {

    /** A post that matched: the rules, the highest priority among them and whether any reads aloud. */
    public static final class Match {
        public final List<Rule> rules;
        public final int priority;
        public final boolean readAloud;
        /** The part of an album the match is about: the one with the caption, else the first; 0 for a single post. */
        public final int messageId;

        Match(List<Rule> rules, int messageId) {
            this.rules = Collections.unmodifiableList(rules);
            int p = Rule.PRIORITY_SILENT;
            boolean read = false;
            for (Rule r : rules) {
                p = Math.max(p, r.priority);
                read |= r.readAloud;
            }
            this.priority = p;
            this.readAloud = read;
            this.messageId = messageId;
        }

        /** The names of the rules of the highest priority first, the others after, each once. */
        public List<String> ruleNames() {
            List<String> names = new ArrayList<>();
            for (Rule r : rules) {
                if (r.priority == priority && !names.contains(r.name)) names.add(r.name);
            }
            for (Rule r : rules) {
                if (!names.contains(r.name)) names.add(r.name);
            }
            return names;
        }

        /** The feed of the first rule of the highest priority, where the notification opens the post; 0 for none. */
        public long feedId() {
            for (Rule r : rules) {
                if (r.priority == priority) return r.feedId;
            }
            return 0;
        }
    }

    /** One feed as its rules see it: its channels and what it shows. */
    public static final class FeedScope {
        public final List<Long> channelIds;
        public final FeedFilter filter;
        public final boolean wholePost;

        public FeedScope(List<Long> channelIds, FeedFilter filter, boolean wholePost) {
            this.channelIds = channelIds;
            this.filter = filter;
            this.wholePost = wholePost;
        }

        public static FeedScope of(Feed feed) {
            return new FeedScope(feed.channelIds, feed.filter, feed.showWholePost);
        }
    }

    private final RuleEvaluator evaluator = new RuleEvaluator();

    /** The rules of the channel that are on and active at {@code when} for a post of {@code postDate}. */
    public static List<Rule> candidates(List<Rule> rules, long channelId, long postDate, Calendar when) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.enabled || r.channelId != channelId || r.isBroken()) continue;
            if (postDate < r.createdAt) continue;
            if (r.schedule != null && !r.schedule.isActive(when)) continue;
            out.add(r);
        }
        return out;
    }

    /** Whether the rule's feed shows the post; a rule without a feed sees every post of its channel. */
    private static boolean shows(Rule r, Map<Long, FeedScope> feeds, long channelId, List<FeedFilter.Post> parts) {
        if (r.feedId == 0) return true;
        FeedScope scope = feeds.get(r.feedId);
        if (scope == null || !scope.channelIds.contains(channelId)) return false;
        if (parts.size() == 1) return scope.filter.mayShow(parts.get(0), scope.wholePost);
        return !scope.filter.shownParts(parts, scope.wholePost).isEmpty();
    }

    private boolean matchesText(Rule r, String text) {
        if (r.matchesEverything()) return true;
        if (text.isEmpty()) return false;
        RuleExpr expr = r.expr();
        return expr != null && evaluator.matches(expr, text);
    }

    /** Evaluates one post; null when no rule matches. */
    public Match evaluate(List<Rule> rules, Map<Long, FeedScope> feeds, long channelId, FeedFilter.Post post, long postDate, Calendar when) {
        if (post.serviceNote) return null;
        List<FeedFilter.Post> parts = Collections.singletonList(post);
        List<Rule> hits = new ArrayList<>();
        for (Rule r : candidates(rules, channelId, postDate, when)) {
            if (shows(r, feeds, channelId, parts) && matchesText(r, post.text)) hits.add(r);
        }
        return hits.isEmpty() ? null : new Match(hits, 0);
    }

    /**
     * Evaluates an album as the one post it is: its captions together are its words, and a
     * rule's feed shows it when it shows any of its parts. {@code messageIds} are the parts' ids
     * in the order of {@code parts}.
     */
    public Match evaluateAlbum(List<Rule> rules, Map<Long, FeedScope> feeds, long channelId, List<FeedFilter.Post> parts, List<Integer> messageIds, long postDate, Calendar when) {
        if (parts.isEmpty()) return null;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Integer.compare(messageIds.get(a), messageIds.get(b)));
        List<FeedFilter.Post> ordered = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int messageId = messageIds.get(order.get(0));
        boolean captionFound = false;
        for (int i : order) {
            FeedFilter.Post p = parts.get(i);
            ordered.add(p);
            if (!p.text.isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(p.text);
                if (!captionFound) {
                    captionFound = true;
                    messageId = messageIds.get(i);
                }
            }
        }
        String words = text.toString();
        List<Rule> hits = new ArrayList<>();
        for (Rule r : candidates(rules, channelId, postDate, when)) {
            if (shows(r, feeds, channelId, ordered) && matchesText(r, words)) hits.add(r);
        }
        return hits.isEmpty() ? null : new Match(hits, messageId);
    }
}
