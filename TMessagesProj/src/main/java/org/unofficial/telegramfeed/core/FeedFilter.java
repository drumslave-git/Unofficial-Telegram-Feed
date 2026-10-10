package org.unofficial.telegramfeed.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a feed shows of its channels' posts. Everything is shown when the filter is empty.
 * A post is described to the filter as a {@link Post}; an album is judged part by part for
 * media and lengths and over all its captions for the words. No Android dependencies.
 */
public final class FeedFilter {

    /** Which posts pass by what they carry. */
    public static final int MODE_ALL = 0;
    public static final int MODE_MEDIA_ONLY = 1;
    public static final int MODE_TEXT_ONLY = 2;

    /** Media kinds, a bit each, for {@link #mediaTypes}. */
    public static final int MEDIA_PHOTO = 1;
    public static final int MEDIA_VIDEO = 1 << 1;
    public static final int MEDIA_GIF = 1 << 2;
    public static final int MEDIA_MUSIC = 1 << 3;
    public static final int MEDIA_VOICE = 1 << 4;
    public static final int MEDIA_FILE = 1 << 5;
    /** Stickers, round videos, polls, locations and everything the app draws as a label. */
    public static final int MEDIA_OTHER = 1 << 6;
    public static final int MEDIA_ALL = MEDIA_PHOTO | MEDIA_VIDEO | MEDIA_GIF | MEDIA_MUSIC | MEDIA_VOICE | MEDIA_FILE | MEDIA_OTHER;

    /** What the filter knows about one message. */
    public static final class Post {
        public final String text;
        /** One of the {@code MEDIA_} kinds, or 0 for a post without media. */
        public final int mediaKind;
        /** A service line (a pinned post, a new photo): not a post, shown only by an empty filter. */
        public final boolean serviceNote;
        /** For a video: its length. */
        public final int videoSeconds;
        /** The album the message belongs to, 0 for none. */
        public final long albumId;

        public Post(String text, int mediaKind, boolean serviceNote, int videoSeconds, long albumId) {
            this.text = text == null ? "" : text;
            this.mediaKind = mediaKind;
            this.serviceNote = serviceNote;
            this.videoSeconds = videoSeconds;
            this.albumId = albumId;
        }

        public static Post text(String text) {
            return new Post(text, 0, false, 0, 0);
        }

        public static Post media(int kind, String caption) {
            return new Post(caption, kind, false, 0, 0);
        }

        public static Post video(int seconds, String caption) {
            return new Post(caption, MEDIA_VIDEO, false, seconds, 0);
        }
    }

    public int mode = MODE_ALL;
    /** The media kinds a post may carry to pass; {@link #MEDIA_ALL} when every kind does. */
    public int mediaTypes = MEDIA_ALL;
    /** A video passes from this length in seconds; 0 for any length. GIFs are not videos here. */
    public int minVideoSeconds = 0;
    /** A post without media passes from this many characters; 0 for any length. */
    public int minTextLength = 0;
    /** A word condition in the rule syntax; empty for none. */
    public String words = "";

    private RuleExpr parsedWords;
    private String parsedSource;
    private static final RuleEvaluator EVALUATOR = new RuleEvaluator();

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
        return mode == MODE_ALL && (mediaTypes & MEDIA_ALL) == MEDIA_ALL && minVideoSeconds <= 0 && minTextLength <= 0 && condition() == null;
    }

    public boolean allowsMedia(int mediaType) {
        return (mediaTypes & mediaType) != 0;
    }

    public FeedFilter withMedia(int mediaType, boolean allowed) {
        FeedFilter f = new FeedFilter(this);
        f.mediaTypes = allowed ? mediaTypes | mediaType : mediaTypes & ~mediaType;
        return f;
    }

    /** The parsed word condition, or null when there is none or the text does not parse. */
    public RuleExpr condition() {
        String source = words == null ? "" : words.trim();
        if (source.isEmpty()) {
            return null;
        }
        if (!source.equals(parsedSource)) {
            parsedSource = source;
            try {
                parsedWords = RuleParser.parse(source);
            } catch (RuleParser.SyntaxException e) {
                parsedWords = null;
            }
        }
        return parsedWords;
    }

    private boolean matchesWords(String text) {
        RuleExpr expr = condition();
        return expr == null || EVALUATOR.matches(expr, text);
    }

    /** The words of the post pass, or cannot be judged alone: an album part without a caption takes its siblings' verdict. */
    private boolean wordsMayPass(Post post) {
        return (post.albumId != 0 && post.text.isEmpty()) || matchesWords(post.text);
    }

    /** The media and length settings, which judge every message by itself. */
    private boolean content(Post post) {
        if (post.serviceNote) {
            return isEmpty();
        }
        if (post.mediaKind == 0) {
            if (mode == MODE_MEDIA_ONLY) return false;
            return post.text.trim().length() >= minTextLength;
        }
        if (mode == MODE_TEXT_ONLY) return false;
        if (!allowsMedia(post.mediaKind)) return false;
        if (post.mediaKind == MEDIA_VIDEO && post.videoSeconds < minVideoSeconds) return false;
        return true;
    }

    /**
     * Whether a single message may show in the feed, as rules judge a post before its album is
     * complete: an album part counts as shown when the feed shows whole albums and is not
     * text-only, since the caption of an album sits on one part that a filter by kind may drop.
     */
    public boolean mayShow(Post post, boolean wholePost) {
        if (post.serviceNote) {
            return isEmpty();
        }
        return wordsMayPass(post) && (content(post) || (wholePost && post.albumId != 0 && mode != MODE_TEXT_ONLY));
    }

    /** Whether the post passes by itself, as the shared media tabs list single items by kind. */
    public boolean allows(Post post) {
        return content(post) && wordsMayPass(post);
    }

    /**
     * What the feed shows of one post: {@code parts} is a single message or the parts of an
     * album, in order. The words are judged over all captions together, the media and lengths
     * part by part; with {@code wholePost} one part that passes brings the others. Empty when
     * the post is left out.
     */
    public List<Post> shownParts(List<Post> parts, boolean wholePost) {
        if (condition() != null) {
            StringBuilder all = new StringBuilder();
            for (Post p : parts) {
                if (!p.text.isEmpty()) {
                    if (all.length() > 0) all.append('\n');
                    all.append(p.text);
                }
            }
            if (!matchesWords(all.toString())) {
                return new ArrayList<>();
            }
        }
        List<Post> passing = new ArrayList<>();
        for (Post p : parts) {
            if (content(p)) passing.add(p);
        }
        if (passing.isEmpty() || !wholePost) return passing;
        return new ArrayList<>(parts);
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
