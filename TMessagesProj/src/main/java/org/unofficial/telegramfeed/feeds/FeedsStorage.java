package org.unofficial.telegramfeed.feeds;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedFilter;
import org.unofficial.telegramfeed.core.Rule;
import org.unofficial.telegramfeed.core.Schedule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The fork's own SQLite file of one account, {@code tgfeed.db} next to Telegram's
 * {@code cache4.db}. Every call runs on its own queue; results come back on the UI thread.
 */
public class FeedsStorage {

    private static final int SCHEMA_VERSION = 2;

    private final int currentAccount;
    private final DispatchQueue queue;
    private SQLiteDatabase database;
    private File file;

    public FeedsStorage(int account) {
        currentAccount = account;
        queue = new DispatchQueue("tgfeedStorage_" + account);
        queue.postRunnable(this::open);
    }

    private File databaseFile() {
        File dir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            dir = new File(dir, "account" + currentAccount + "/");
            dir.mkdirs();
        }
        return new File(dir, "tgfeed.db");
    }

    private void open() {
        try {
            file = databaseFile();
            database = new SQLiteDatabase(file.getPath());
            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
            database.executeFast("CREATE TABLE IF NOT EXISTS feeds(id INTEGER PRIMARY KEY, name TEXT NOT NULL, sort INTEGER NOT NULL, show_minimized INTEGER NOT NULL, show_whole_post INTEGER NOT NULL, filter_mode INTEGER NOT NULL, filter_media INTEGER NOT NULL, min_video_seconds INTEGER NOT NULL, min_text_length INTEGER NOT NULL, filter_words TEXT NOT NULL)").stepThis().dispose();
            database.executeFast("CREATE TABLE IF NOT EXISTS feed_channels(feed_id INTEGER NOT NULL, channel_id INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(feed_id, channel_id))").stepThis().dispose();
            database.executeFast("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL, channel_id INTEGER NOT NULL, feed_id INTEGER NOT NULL, condition TEXT NOT NULL, priority INTEGER NOT NULL, read_aloud INTEGER NOT NULL, schedule TEXT NOT NULL, created_at INTEGER NOT NULL)").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + SCHEMA_VERSION).stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void loadFeeds(Utilities.Callback<List<Feed>> callback) {
        queue.postRunnable(() -> {
            List<Feed> feeds = new ArrayList<>();
            if (database != null) {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT id, name, sort, show_minimized, show_whole_post, filter_mode, filter_media, min_video_seconds, min_text_length, filter_words FROM feeds ORDER BY sort");
                    while (cursor.next()) {
                        Feed feed = new Feed(cursor.longValue(0), cursor.stringValue(1), cursor.intValue(2));
                        feed.showMinimized = cursor.intValue(3) != 0;
                        feed.showWholePost = cursor.intValue(4) != 0;
                        feed.filter.mode = cursor.intValue(5);
                        feed.filter.mediaTypes = cursor.intValue(6);
                        feed.filter.minVideoSeconds = cursor.intValue(7);
                        feed.filter.minTextLength = cursor.intValue(8);
                        feed.filter.words = cursor.stringValue(9);
                        feeds.add(feed);
                    }
                    cursor.dispose();
                    for (Feed feed : feeds) {
                        cursor = database.queryFinalized("SELECT channel_id FROM feed_channels WHERE feed_id = ? ORDER BY position", feed.id);
                        while (cursor.next()) {
                            feed.channelIds.add(cursor.longValue(0));
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            Feed.sortByOrder(feeds);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.run(feeds));
        });
    }

    /** Inserts or replaces the feed with its channels. */
    public void saveFeed(Feed source) {
        final Feed feed = new Feed(source);
        queue.postRunnable(() -> {
            if (database == null) {
                return;
            }
            try {
                database.beginTransaction();
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO feeds VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                state.requery();
                state.bindLong(1, feed.id);
                state.bindString(2, feed.name);
                state.bindInteger(3, feed.order);
                state.bindInteger(4, feed.showMinimized ? 1 : 0);
                state.bindInteger(5, feed.showWholePost ? 1 : 0);
                state.bindInteger(6, feed.filter.mode);
                state.bindInteger(7, feed.filter.mediaTypes);
                state.bindInteger(8, feed.filter.minVideoSeconds);
                state.bindInteger(9, feed.filter.minTextLength);
                state.bindString(10, feed.filter.words);
                state.step();
                state.dispose();
                database.executeFast("DELETE FROM feed_channels WHERE feed_id = " + feed.id).stepThis().dispose();
                state = database.executeFast("INSERT INTO feed_channels VALUES(?, ?, ?)");
                for (int i = 0; i < feed.channelIds.size(); i++) {
                    state.requery();
                    state.bindLong(1, feed.id);
                    state.bindLong(2, feed.channelIds.get(i));
                    state.bindInteger(3, i);
                    state.step();
                }
                state.dispose();
                database.commitTransaction();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveOrder(List<Feed> source) {
        final List<Feed> feeds = new ArrayList<>();
        for (Feed feed : source) {
            feeds.add(new Feed(feed));
        }
        queue.postRunnable(() -> {
            if (database == null) {
                return;
            }
            try {
                database.beginTransaction();
                SQLitePreparedStatement state = database.executeFast("UPDATE feeds SET sort = ? WHERE id = ?");
                for (Feed feed : feeds) {
                    state.requery();
                    state.bindInteger(1, feed.order);
                    state.bindLong(2, feed.id);
                    state.step();
                }
                state.dispose();
                database.commitTransaction();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteFeed(long feedId) {
        queue.postRunnable(() -> {
            if (database == null) {
                return;
            }
            try {
                database.executeFast("DELETE FROM feed_channels WHERE feed_id = " + feedId).stepThis().dispose();
                database.executeFast("DELETE FROM feeds WHERE id = " + feedId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void loadRules(Utilities.Callback<List<Rule>> callback) {
        queue.postRunnable(() -> {
            List<Rule> rules = new ArrayList<>();
            if (database != null) {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT id, name, enabled, channel_id, feed_id, condition, priority, read_aloud, schedule, created_at FROM rules ORDER BY id");
                    while (cursor.next()) {
                        Rule rule = new Rule();
                        rule.id = cursor.longValue(0);
                        rule.name = cursor.stringValue(1);
                        rule.enabled = cursor.intValue(2) != 0;
                        rule.channelId = cursor.longValue(3);
                        rule.feedId = cursor.longValue(4);
                        rule.condition = cursor.stringValue(5);
                        rule.priority = cursor.intValue(6);
                        rule.readAloud = cursor.intValue(7) != 0;
                        String schedule = cursor.stringValue(8);
                        if (schedule != null && !schedule.isEmpty()) {
                            try {
                                rule.schedule = Schedule.decode(schedule);
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        }
                        rule.createdAt = cursor.longValue(9);
                        rules.add(rule);
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.run(rules));
        });
    }

    /** Inserts or replaces the rule. */
    public void saveRule(Rule source) {
        final Rule rule = new Rule(source);
        queue.postRunnable(() -> {
            if (database == null) {
                return;
            }
            try {
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO rules VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                state.requery();
                state.bindLong(1, rule.id);
                state.bindString(2, rule.name);
                state.bindInteger(3, rule.enabled ? 1 : 0);
                state.bindLong(4, rule.channelId);
                state.bindLong(5, rule.feedId);
                state.bindString(6, rule.condition == null ? "" : rule.condition);
                state.bindInteger(7, rule.priority);
                state.bindInteger(8, rule.readAloud ? 1 : 0);
                state.bindString(9, rule.schedule == null ? "" : rule.schedule.encode());
                state.bindLong(10, rule.createdAt);
                state.step();
                state.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteRules(List<Long> ids) {
        final List<Long> copy = new ArrayList<>(ids);
        queue.postRunnable(() -> {
            if (database == null || copy.isEmpty()) {
                return;
            }
            try {
                StringBuilder in = new StringBuilder();
                for (long id : copy) {
                    if (in.length() > 0) in.append(',');
                    in.append(id);
                }
                database.executeFast("DELETE FROM rules WHERE id IN (" + in + ")").stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Closes the database and deletes the file (the account logged out). */
    public void cleanup() {
        queue.postRunnable(() -> {
            try {
                if (database != null) {
                    database.close();
                    database = null;
                }
                if (file != null) {
                    file.delete();
                    new File(file.getPath() + "-wal").delete();
                    new File(file.getPath() + "-shm").delete();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            open();
        });
    }
}
