package org.unofficial.telegramfeed.core;

import java.util.Objects;

/**
 * What a feed shows of its channels' posts. Everything is shown when the filter is empty.
 * No Android dependencies.
 */
public final class FeedFilter {

    /** Which posts pass by what they carry. */
    public static final int MODE_ALL = 0;
    public static final int MODE_MEDIA_ONLY = 1;
    public static final int MODE_TEXT_ONLY = 2;

    /** Media types, a bit each, for {@link #mediaTypes}. */
    public static final int MEDIA_PHOTO = 1;
    public static final int MEDIA_VIDEO = 1 << 1;
    public static final int MEDIA_FILE = 1 << 2;
    public static final int MEDIA_MUSIC = 1 << 3;
    public static final int MEDIA_VOICE = 1 << 4;
    public static final int MEDIA_ROUND_VIDEO = 1 << 5;
    public static final int MEDIA_STICKER = 1 << 6;
    public static final int MEDIA_GIF = 1 << 7;
    public static final int MEDIA_ALL = MEDIA_PHOTO | MEDIA_VIDEO | MEDIA_FILE | MEDIA_MUSIC | MEDIA_VOICE | MEDIA_ROUND_VIDEO | MEDIA_STICKER | MEDIA_GIF;

    public int mode = MODE_ALL;
    /** The media types a post may carry to pass; {@link #MEDIA_ALL} when every type does. */
    public int mediaTypes = MEDIA_ALL;
    /** A video passes from this length in seconds; 0 for any length. */
    public int minVideoSeconds = 0;
    /** A text post passes from this many characters; 0 for any length. */
    public int minTextLength = 0;
    /** A word condition in the rule syntax; empty for none. */
    public String words = "";

    public FeedFilter() {
    }

    public FeedFilter(FeedFilter other) {
        mode = other.mode;
        mediaTypes = other.mediaTypes;
        minVideoSeconds = other.minVideoSeconds;
        minTextLength = other.minTextLength;
        words = other.words;
    }

    /** True when the filter lets every post through. */
    public boolean isEmpty() {
        return mode == MODE_ALL && mediaTypes == MEDIA_ALL && minVideoSeconds == 0 && minTextLength == 0 && words.isEmpty();
    }

    public boolean allowsMedia(int mediaType) {
        return (mediaTypes & mediaType) != 0;
    }

    public FeedFilter withMedia(int mediaType, boolean allowed) {
        FeedFilter f = new FeedFilter(this);
        f.mediaTypes = allowed ? mediaTypes | mediaType : mediaTypes & ~mediaType;
        return f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FeedFilter)) return false;
        FeedFilter f = (FeedFilter) o;
        return mode == f.mode && mediaTypes == f.mediaTypes && minVideoSeconds == f.minVideoSeconds && minTextLength == f.minTextLength && words.equals(f.words);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, mediaTypes, minVideoSeconds, minTextLength, words);
    }
}
