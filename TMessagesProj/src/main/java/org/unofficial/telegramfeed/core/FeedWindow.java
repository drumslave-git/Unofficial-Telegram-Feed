package org.unofficial.telegramfeed.core;

/**
 * What of a feed's loaded history the merged timeline may show. Every channel holds one
 * unbroken run of its posts, from {@link Channel#minDate} to {@link Channel#maxDate}; a post
 * older than the start of a run that has older history beyond it, or newer than the end of a
 * run that has newer history beyond it, could hide a gap and waits. No Android dependencies.
 */
public final class FeedWindow {

    private FeedWindow() {
    }

    /** The loaded run of one channel's posts. */
    public static class Channel {
        /** At least one page of the channel is in. */
        public boolean loaded;
        /** Nothing older than {@link #minDate} exists. */
        public boolean olderEnd;
        /** Posts newer than {@link #maxDate} exist and are not loaded. */
        public boolean hasNewer;
        public int minDate = Integer.MAX_VALUE;
        public int maxDate;

        /** Widens the run by a loaded post. */
        public void include(int date) {
            minDate = Math.min(minDate, date);
            maxDate = Math.max(maxDate, date);
        }

        /** A run without posts has nothing beyond it either way. */
        public void settle() {
            if (minDate == Integer.MAX_VALUE) {
                olderEnd = true;
                hasNewer = false;
            }
        }

        /** Forgets the run. */
        public void forget() {
            loaded = false;
            olderEnd = false;
            hasNewer = false;
            minDate = Integer.MAX_VALUE;
            maxDate = 0;
        }

        /** Whether the run reaches the date on both sides, or the channel has nothing beyond it there. */
        public boolean covers(int date) {
            return loaded && (olderEnd || minDate <= date) && (!hasNewer || maxDate >= date);
        }
    }

    /** The date older posts wait below: the latest start of a run with older history beyond it; 0 when none has. */
    public static int floor(Iterable<? extends Channel> channels) {
        int floor = 0;
        for (Channel channel : channels) {
            if (channel.loaded && !channel.olderEnd) {
                floor = Math.max(floor, channel.minDate);
            }
        }
        return floor;
    }

    /** The date newer posts wait above: the earliest end of a run with newer history beyond it; {@code Integer.MAX_VALUE} when none has. */
    public static int ceiling(Iterable<? extends Channel> channels) {
        int ceiling = Integer.MAX_VALUE;
        for (Channel channel : channels) {
            if (channel.loaded && channel.hasNewer) {
                ceiling = Math.min(ceiling, channel.maxDate);
            }
        }
        return ceiling;
    }
}
