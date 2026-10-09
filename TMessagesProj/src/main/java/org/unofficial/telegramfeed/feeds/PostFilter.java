package org.unofficial.telegramfeed.feeds;

import android.text.TextUtils;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.unofficial.telegramfeed.core.FeedFilter;

/** Describes Telegram messages to the pure {@link FeedFilter}. */
public final class PostFilter {

    private PostFilter() {
    }

    /** A service line (a pinned post, a changed photo), which no filter but the empty one shows. */
    public static boolean isServiceNote(MessageObject message) {
        return message.messageOwner.action != null && !(message.messageOwner.action instanceof TLRPC.TL_messageActionEmpty);
    }

    /** The media kind of the message for the filter, 0 for a post without media. */
    public static int mediaKind(MessageObject message) {
        if (message.isPhoto()) {
            return FeedFilter.MEDIA_PHOTO;
        }
        if (message.isRoundVideo() || message.isAnyKindOfSticker()) {
            return FeedFilter.MEDIA_OTHER;
        }
        if (message.isGif()) {
            return FeedFilter.MEDIA_GIF;
        }
        if (message.isVideo()) {
            return FeedFilter.MEDIA_VIDEO;
        }
        if (message.isMusic()) {
            return FeedFilter.MEDIA_MUSIC;
        }
        if (message.isVoice()) {
            return FeedFilter.MEDIA_VOICE;
        }
        if (message.isDocument()) {
            return FeedFilter.MEDIA_FILE;
        }
        if (message.isMediaEmpty()) {
            return 0;
        }
        return FeedFilter.MEDIA_OTHER;
    }

    public static FeedFilter.Post describe(MessageObject message) {
        int kind = mediaKind(message);
        int seconds = kind == FeedFilter.MEDIA_VIDEO ? (int) Math.round(message.getDuration()) : 0;
        long albumId = message.hasValidGroupId() ? message.getGroupId() : 0;
        return new FeedFilter.Post(message.messageOwner.message, kind, isServiceNote(message), seconds, albumId);
    }

    /** The one line a minimized post folds to: its first line of text, or what it carries. */
    public static CharSequence summary(MessageObject message) {
        String text = message.messageOwner.message;
        if (!TextUtils.isEmpty(text)) {
            String line = text.trim();
            int newline = line.indexOf('\n');
            if (newline >= 0) {
                line = line.substring(0, newline).trim();
            }
            if (line.length() > 200) {
                line = line.substring(0, 200);
            }
            if (!line.isEmpty()) {
                return line;
            }
        }
        return message.messageText != null ? message.messageText : "";
    }
}
