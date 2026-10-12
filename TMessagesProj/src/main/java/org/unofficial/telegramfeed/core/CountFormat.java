package org.unofficial.telegramfeed.core;

/** Unread counts in a few characters: 670, 1.1K, 73K, 1.2M. Never rounds up. */
public final class CountFormat {

    private CountFormat() {
    }

    public static String shortCount(int count) {
        if (count < 1000) {
            return Integer.toString(count);
        }
        if (count < 10_000) {
            return withTenth(count, 1000) + "K";
        }
        if (count < 1_000_000) {
            return (count / 1000) + "K";
        }
        if (count < 10_000_000) {
            return withTenth(count, 1_000_000) + "M";
        }
        return (count / 1_000_000) + "M";
    }

    private static String withTenth(int count, int unit) {
        int whole = count / unit;
        int tenth = count % unit / (unit / 10);
        return tenth == 0 ? Integer.toString(whole) : whole + "." + tenth;
    }
}
