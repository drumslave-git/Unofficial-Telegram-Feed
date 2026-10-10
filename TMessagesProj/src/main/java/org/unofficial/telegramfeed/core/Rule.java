package org.unofficial.telegramfeed.core;

import java.util.Objects;

/**
 * A keyword rule of one channel, optionally scoped by a feed, in which case it sees only the
 * posts that feed shows. The condition is kept in the text form of {@link RuleParser}; an
 * empty condition matches every post, a post without text included. No Android dependencies.
 */
public final class Rule {

    /** Notification priorities; the highest of the matching rules wins. */
    public static final int PRIORITY_SILENT = 0;
    public static final int PRIORITY_NORMAL = 1;
    public static final int PRIORITY_URGENT = 2;

    public long id;
    public String name = "";
    public boolean enabled = true;
    public long channelId;
    /** The feed that limits what the rule sees; 0 for none. */
    public long feedId;
    /** The condition in the rule syntax; empty for every post. */
    public String condition = "";
    public int priority = PRIORITY_NORMAL;
    public boolean readAloud;
    /** When the rule is active; null for always. */
    public Schedule schedule;
    /** Unix seconds; the rule matches only posts from this moment on. */
    public long createdAt;

    private RuleExpr parsed;
    private String parsedSource;
    private boolean parseFailed;

    public Rule() {
    }

    public Rule(Rule other) {
        id = other.id;
        name = other.name;
        enabled = other.enabled;
        channelId = other.channelId;
        feedId = other.feedId;
        condition = other.condition;
        priority = other.priority;
        readAloud = other.readAloud;
        schedule = other.schedule;
        createdAt = other.createdAt;
    }

    /** True for a rule with no condition: it notifies about every post of its channel. */
    public boolean matchesEverything() {
        return condition == null || condition.trim().isEmpty();
    }

    /** The parsed condition; null for no condition or for text that does not parse. */
    public RuleExpr expr() {
        String source = condition == null ? "" : condition.trim();
        if (source.isEmpty()) {
            return null;
        }
        if (!source.equals(parsedSource)) {
            parsedSource = source;
            try {
                parsed = RuleParser.parse(source);
                parseFailed = false;
            } catch (RuleParser.SyntaxException e) {
                parsed = null;
                parseFailed = true;
            }
        }
        return parsed;
    }

    /** True when the condition's text does not parse; such a rule matches nothing. */
    public boolean isBroken() {
        expr();
        return !matchesEverything() && parseFailed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rule)) return false;
        Rule r = (Rule) o;
        return id == r.id && enabled == r.enabled && channelId == r.channelId && feedId == r.feedId && priority == r.priority
                && readAloud == r.readAloud && createdAt == r.createdAt && Objects.equals(name, r.name)
                && Objects.equals(condition, r.condition) && Objects.equals(schedule, r.schedule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, channelId, feedId, condition, priority, readAloud, schedule, createdAt);
    }
}
