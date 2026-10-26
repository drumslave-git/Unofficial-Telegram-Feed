package org.unofficial.telegramfeed.feeds;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedsConfig;
import org.unofficial.telegramfeed.core.Rule;
import org.unofficial.telegramfeed.core.Schedule;

import java.nio.charset.StandardCharsets;

/**
 * The JSON file that carries an account's feeds and rules in its Saved Messages
 * ({@link SavedMessagesSync}). A file of a newer format than {@link #FORMAT} is refused.
 */
public final class ConfigFile {

    public static final String FILE_NAME = "tgfeed-backup.json";
    public static final String MIME_TYPE = "application/json";
    public static final int FORMAT = 1;

    private ConfigFile() {
    }

    public static byte[] encode(FeedsConfig config) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("app", "org.unofficial.telegramfeed");
        root.put("format", FORMAT);
        JSONArray feeds = new JSONArray();
        for (Feed feed : config.feeds) {
            JSONObject f = new JSONObject();
            f.put("id", feed.id);
            f.put("name", feed.name);
            JSONArray channels = new JSONArray();
            for (long channelId : feed.channelIds) {
                channels.put(channelId);
            }
            f.put("channels", channels);
            f.put("showMinimized", feed.showMinimized);
            f.put("showWholePost", feed.showWholePost);
            JSONObject filter = new JSONObject();
            filter.put("mode", feed.filter.mode);
            filter.put("media", feed.filter.mediaTypes);
            filter.put("minVideoSeconds", feed.filter.minVideoSeconds);
            filter.put("minTextLength", feed.filter.minTextLength);
            filter.put("words", feed.filter.words);
            f.put("filter", filter);
            feeds.put(f);
        }
        root.put("feeds", feeds);
        JSONArray rules = new JSONArray();
        for (Rule rule : config.rules) {
            JSONObject r = new JSONObject();
            r.put("id", rule.id);
            r.put("name", rule.name);
            r.put("enabled", rule.enabled);
            r.put("channel", rule.channelId);
            r.put("feed", rule.feedId);
            r.put("condition", rule.condition == null ? "" : rule.condition);
            r.put("priority", rule.priority);
            r.put("readAloud", rule.readAloud);
            r.put("schedule", rule.schedule == null ? "" : rule.schedule.encode());
            r.put("createdAt", rule.createdAt);
            rules.put(r);
        }
        root.put("rules", rules);
        return root.toString(1).getBytes(StandardCharsets.UTF_8);
    }

    public static FeedsConfig decode(byte[] bytes) throws JSONException {
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.optInt("format", 0) > FORMAT) {
            throw new JSONException("format " + root.optInt("format") + " is newer than " + FORMAT);
        }
        FeedsConfig config = new FeedsConfig();
        JSONArray feeds = root.getJSONArray("feeds");
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject f = feeds.getJSONObject(i);
            Feed feed = new Feed(f.getLong("id"), f.getString("name"), i);
            JSONArray channels = f.getJSONArray("channels");
            for (int c = 0; c < channels.length(); c++) {
                feed.addChannel(channels.getLong(c));
            }
            feed.showMinimized = f.optBoolean("showMinimized", feed.showMinimized);
            feed.showWholePost = f.optBoolean("showWholePost", feed.showWholePost);
            JSONObject filter = f.optJSONObject("filter");
            if (filter != null) {
                feed.filter.mode = filter.optInt("mode", feed.filter.mode);
                feed.filter.mediaTypes = filter.optInt("media", feed.filter.mediaTypes);
                feed.filter.minVideoSeconds = filter.optInt("minVideoSeconds", 0);
                feed.filter.minTextLength = filter.optInt("minTextLength", 0);
                feed.filter.words = filter.optString("words", "");
            }
            config.feeds.add(feed);
        }
        JSONArray rules = root.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            Rule rule = new Rule();
            rule.id = r.getLong("id");
            rule.name = r.optString("name", "");
            rule.enabled = r.optBoolean("enabled", true);
            rule.channelId = r.getLong("channel");
            rule.feedId = r.optLong("feed", 0);
            rule.condition = r.optString("condition", "");
            rule.priority = r.optInt("priority", Rule.PRIORITY_NORMAL);
            rule.readAloud = r.optBoolean("readAloud", false);
            String schedule = r.optString("schedule", "");
            if (!schedule.isEmpty()) {
                try {
                    rule.schedule = Schedule.decode(schedule);
                } catch (IllegalArgumentException e) {
                    throw new JSONException(e.getMessage());
                }
            }
            rule.createdAt = r.getLong("createdAt");
            config.rules.add(rule);
        }
        return new FeedsConfig(config.feeds, config.rules);
    }
}
