package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.feeds.FeedsController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The Feeds tab of the chat list: the feeds drawn as the chat list draws chats, newest post
 * first, the row menu, "New feed", and the empty state. Lives inside a
 * {@code DialogsActivity.ViewPage} over the dialogs list, takes that list's paddings and, as
 * that list does, folds the chat list's header away as it scrolls.
 */
public class FeedsTabView extends FrameLayout {

    private static final int ROW_FEED = 0;
    private static final int ROW_NEW_FEED = 1;
    private static final int ROW_EMPTY = 2;
    private static final int ROW_SPACER = 3;

    /** The chat list's header above the feeds: the stories, the search field, the tabs and the panels. */
    public interface Header {
        /** Where the chat list's rows start while the header is unfolded, as the header is drawn now. */
        int listTopPadding();

        /** The space the chat list's rows keep free at the bottom. */
        int listBottomPadding();

        /** How far the header folds at most: the stories and the search field. */
        int foldRange();

        /** How far the header is folded while the list is at its top: the stories, folded into the title. */
        int restingFold();

        /** The feeds moved; the header follows them in its next frame. */
        void onFeedsMoved();
    }

    /** A feed and its newest post: the last post of whichever of its channels posted last. */
    private static final class FeedRow {
        final Feed feed;
        /** The newest post's channel as a dialog id; 0 while the feed has no post. */
        long dialogId;
        /** The newest post, all its parts when it is an album, as the chat list keeps them. */
        ArrayList<MessageObject> post;
        int date;

        FeedRow(Feed feed) {
            this.feed = feed;
        }
    }

    private final BaseFragment fragment;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Header header;
    private final RecyclerListView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter;
    private final ArrayList<FeedRow> rows = new ArrayList<>();
    /** The last row, which lets a short list scroll the search field away. */
    private View spacer;
    private final NotificationCenter.NotificationCenterDelegate themeObserver = (id, account, args) -> updateColors();
    /**
     * Before every frame the list is put where the header ends in that frame: the header moves
     * as it draws (its panels animate, the search field folds), so a position taken at any
     * other time can be stale by the time it is drawn.
     */
    private final ViewTreeObserver.OnPreDrawListener headerFollower = this::beforeFrame;

    public FeedsTabView(Context context, BaseFragment fragment, Header header, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = fragment.getCurrentAccount();
        this.resourcesProvider = resourcesProvider;
        this.header = header;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        listView = new RecyclerListView(context, resourcesProvider) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                header.onFeedsMoved();
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                remeasureSpacer();
            }
        };
        listView.setClipToPadding(false);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                header.onFeedsMoved();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    settleSearchField();
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (adapter.rowType(position) == ROW_NEW_FEED) {
                createFeed();
                return;
            }
            Feed feed = adapter.feedAt(position);
            if (feed != null) {
                fragment.presentFragment(new FeedActivity(feed.id));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            Feed feed = adapter.feedAt(position);
            if (feed == null) {
                return false;
            }
            showMenu(view, feed);
            return true;
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        buildRows();
    }

    private FeedsController controller() {
        return FeedsController.getInstance(currentAccount);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(themeObserver, NotificationCenter.didSetNewTheme);
        getViewTreeObserver().addOnPreDrawListener(headerFollower);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(themeObserver, NotificationCenter.didSetNewTheme);
        getViewTreeObserver().removeOnPreDrawListener(headerFollower);
    }

    /** Takes a new theme's colours; the rows are made again, since each takes its colours when it is made. */
    private void updateColors() {
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        listView.setAdapter(null);
        listView.getRecycledViewPool().clear();
        listView.setAdapter(adapter);
    }

    /** Re-reads the feeds, their newest posts and their counts. */
    public void update() {
        buildRows();
        adapter.notifyDataSetChanged();
        remeasureSpacer();
    }

    public void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    /**
     * How far the chat list's header is folded (0 or less) for where the feeds are, worked out
     * as the chat list works it out from its chats: the stories fold while the list is at its
     * top, and the first row's scroll past the top folds the search field. {@code current} while
     * the list has no rows laid out yet.
     */
    public float headerOffset(float current) {
        if (listView.getChildCount() == 0) {
            return current;
        }
        int range = header.foldRange();
        RecyclerView.ViewHolder first = listView.findViewHolderForLayoutPosition(0);
        if (first == null) {
            return -range;
        }
        int scrolled = Math.max(0, listView.getPaddingTop() - (int) first.itemView.getY());
        return -Math.min(range, header.restingFold() + scrolled);
    }

    /** Scrolls the list to the header's fold, as the chat list scrolls a folder's chats when its tab opens. */
    public void followHeader(float offset) {
        int scrolled = Math.max(0, (int) -offset - header.restingFold());
        layoutManager.scrollToPositionWithOffset(0, -scrolled);
    }

    /** As the chat list does, a half-folded search field folds away or comes back when the scroll stops. */
    private void settleSearchField() {
        RecyclerView.ViewHolder first = listView.findViewHolderForLayoutPosition(0);
        if (first == null) {
            return;
        }
        int search = header.foldRange() - header.restingFold();
        int folded = listView.getPaddingTop() - (int) first.itemView.getY();
        if (folded > 0 && folded < search) {
            listView.smoothScrollBy(0, folded < search / 2 ? -folded : search - folded);
        }
    }

    /** How far the first feed was from the list's top in the last frame; the header follows any change. */
    private int lastFirstOffset = Integer.MIN_VALUE;

    private boolean beforeFrame() {
        if (getVisibility() != VISIBLE) {
            return true;
        }
        boolean moved = followHeaderEdge();
        RecyclerView.ViewHolder first = listView.findViewHolderForLayoutPosition(0);
        int firstOffset = first == null ? Integer.MIN_VALUE : (int) first.itemView.getY() - listView.getPaddingTop();
        if (moved || firstOffset != lastFirstOffset) {
            lastFirstOffset = firstOffset;
            header.onFeedsMoved();
        }
        return true;
    }

    /**
     * Puts the list where the header ends now: the chat list's top padding as the header draws
     * it, without the stories while they are folded into the title, and the first feed never
     * below it while nothing scrolls the list. True when the list moved.
     */
    private boolean followHeaderEdge() {
        int top = Math.max(0, header.listTopPadding() - header.restingFold());
        int bottom = header.listBottomPadding();
        int oldTop = listView.getPaddingTop();
        boolean moved = false;
        if (oldTop != top || listView.getPaddingBottom() != bottom) {
            listView.setPadding(0, top, 0, bottom);
            if (listView.getScrollState() != RecyclerView.SCROLL_STATE_DRAGGING) {
                listView.offsetChildrenVertical(top - oldTop);
            }
            remeasureSpacer();
            moved = true;
        }
        RecyclerView.ViewHolder first = listView.findViewHolderForLayoutPosition(0);
        if (first != null && first.itemView.getTop() > top && listView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && !listView.isAnimating()) {
            listView.offsetChildrenVertical(top - first.itemView.getTop());
            moved = true;
        }
        return moved;
    }

    private void remeasureSpacer() {
        if (spacer != null) {
            spacer.requestLayout();
        }
    }

    /**
     * The spacer's height: what the rows leave of the list, plus the search field, so the list
     * scrolls the search field away however few feeds it has. Counts the rows as Telegram's
     * {@code DialogsAdapter.LastEmptyView} counts the chats, by the cells' fixed heights.
     */
    private int spacerHeight() {
        if (rows.isEmpty()) {
            return 0;
        }
        int feedRow = dp(SharedConfig.useThreeLinesLayout ? 76 : 70) + 1;
        int newFeedRow = dp(50);
        int content = rows.size() * feedRow + newFeedRow;
        int visible = listView.getMeasuredHeight() - listView.getPaddingTop() - listView.getPaddingBottom();
        return Math.max(0, visible + header.foldRange() - header.restingFold() - content);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        followHeaderEdge();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** The feeds with their newest posts, newest first, as the chat list orders chats. */
    private void buildRows() {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        List<Feed> feeds = controller().getFeeds();
        HashMap<Long, FeedRow> byId = new HashMap<>();
        for (Feed feed : feeds) {
            FeedRow row = new FeedRow(feed);
            for (long channelId : feed.channelIds) {
                ArrayList<MessageObject> post = messagesController.dialogMessage.get(-channelId);
                if (post == null || post.isEmpty() || post.get(0).messageOwner == null) {
                    continue;
                }
                int date = post.get(0).messageOwner.date;
                if (row.post == null || date > row.date) {
                    row.dialogId = -channelId;
                    row.post = post;
                    row.date = date;
                }
            }
            byId.put(feed.id, row);
        }
        rows.clear();
        for (Feed feed : Feed.byNewestPost(feeds, f -> byId.get(f.id).date)) {
            rows.add(byId.get(feed.id));
        }
    }

    /** The channels of the feed with unread posts the feed shows. */
    public static int channelsWithNewPosts(int account, Feed feed) {
        return org.unofficial.telegramfeed.feeds.FeedCounts.getInstance(account).channelsWithNewPosts(feed);
    }

    /** The unread posts of the feed's channels that the feed shows. */
    public static int unreadPosts(int account, Feed feed) {
        return org.unofficial.telegramfeed.feeds.FeedCounts.getInstance(account).unreadPosts(feed);
    }

    private void createFeed() {
        TgfeedAlerts.promptName(fragment, LocaleController.getString(R.string.TgfeedNewFeed), "", name -> {
            List<MessagesController.DialogFilter> folders = new ArrayList<>();
            List<List<Long>> folderChannels = new ArrayList<>();
            for (MessagesController.DialogFilter filter : MessagesController.getInstance(currentAccount).getDialogFilters()) {
                if (filter.isDefault()) {
                    continue;
                }
                List<Long> channels = channelsOfFolder(currentAccount, filter);
                if (!channels.isEmpty()) {
                    folders.add(filter);
                    folderChannels.add(channels);
                }
            }
            if (folders.isEmpty()) {
                openEditor(controller().createFeed(name, new ArrayList<>()));
                return;
            }
            CharSequence[] items = new CharSequence[folders.size() + 1];
            items[0] = LocaleController.getString(R.string.TgfeedEmptyFeed);
            for (int i = 0; i < folders.size(); i++) {
                items[i + 1] = folders.get(i).name;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), resourcesProvider);
            builder.setTitle(LocaleController.getString(R.string.TgfeedStartWith));
            builder.setItems(items, (dialog, which) -> openEditor(controller().createFeed(name, which == 0 ? new ArrayList<>() : folderChannels.get(which - 1))));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            fragment.showDialog(builder.create());
        });
    }

    private void openEditor(Feed feed) {
        fragment.presentFragment(new FeedEditActivity(feed.id));
    }

    /** The channels a Telegram folder shows, in the chat list's order. */
    public static List<Long> channelsOfFolder(int account, MessagesController.DialogFilter filter) {
        MessagesController messagesController = MessagesController.getInstance(account);
        List<Long> out = new ArrayList<>();
        for (TLRPC.Dialog dialog : messagesController.getAllDialogs()) {
            if (dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = messagesController.getChat(-dialog.id);
            if (chat == null || !org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup(chat) || chat.left || chat.kicked) {
                continue;
            }
            if (filter.includesDialog(org.telegram.messenger.AccountInstance.getInstance(account), dialog.id, dialog)) {
                out.add(chat.id);
            }
        }
        return out;
    }

    private void showMenu(View cell, Feed feed) {
        ItemOptions.makeOptions(fragment, cell)
                .add(R.drawable.msg_folders, LocaleController.getString(R.string.TgfeedEditChannels), () -> openEditor(feed))
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.TgfeedRename), () -> TgfeedAlerts.promptName(fragment, LocaleController.getString(R.string.TgfeedRename), feed.name, name -> {
                    feed.name = name;
                    controller().updateFeed(feed);
                }))
                .add(R.drawable.msg_notifications, LocaleController.getString(R.string.TgfeedRules), () -> fragment.presentFragment(RulesActivity.ofFeed(feed.id)))
                .add(R.drawable.msg_markread, LocaleController.getString(R.string.MarkAsRead), () -> markRead(feed))
                .addIf(true, R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDelete(feed))
                .show();
    }

    private void markRead(Feed feed) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        for (long channelId : feed.channelIds) {
            TLRPC.Dialog dialog = messagesController.getDialog(-channelId);
            if (dialog != null && (dialog.unread_count > 0 || dialog.unread_mark)) {
                messagesController.markDialogAsRead(dialog.id, dialog.top_message, dialog.top_message, dialog.last_message_date, false, 0, 0, true, 0);
            }
        }
        update();
    }

    private void confirmDelete(Feed feed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.TgfeedDeleteFeed));
        CharSequence text = LocaleController.formatString(R.string.TgfeedDeleteFeedText, feed.name);
        int rules = org.unofficial.telegramfeed.feeds.RulesController.getInstance(currentAccount).getRulesOfFeed(feed.id).size();
        if (rules > 0) {
            text = text + " " + LocaleController.formatPluralString("TgfeedDeleteFeedRules", rules);
        }
        builder.setMessage(AndroidUtilities.replaceTags(text.toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> controller().deleteFeed(feed.id));
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        int rowType(int position) {
            if (position == getItemCount() - 1) {
                return ROW_SPACER;
            }
            if (rows.isEmpty()) {
                return position == 0 ? ROW_EMPTY : ROW_NEW_FEED;
            }
            return position < rows.size() ? ROW_FEED : ROW_NEW_FEED;
        }

        Feed feedAt(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position).feed : null;
        }

        @Override
        public int getItemCount() {
            return (rows.isEmpty() ? 2 : rows.size() + 1) + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return rowType(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == ROW_FEED || type == ROW_NEW_FEED;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_FEED: {
                    // The chat list's own row, so a feed looks as a chat does.
                    view = new DialogCell(null, parent.getContext(), false, false, currentAccount, resourcesProvider);
                    break;
                }
                case ROW_NEW_FEED: {
                    TextCell cell = new TextCell(parent.getContext(), resourcesProvider);
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedNewFeed), R.drawable.msg_add, false);
                    view = cell;
                    break;
                }
                case ROW_SPACER: {
                    view = new View(parent.getContext()) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), spacerHeight());
                        }
                    };
                    spacer = view;
                    break;
                }
                case ROW_EMPTY:
                default: {
                    LinearLayout layout = new LinearLayout(parent.getContext());
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(dp(24), dp(28), dp(24), dp(20));
                    TextView title = new TextView(parent.getContext());
                    title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                    title.setTypeface(AndroidUtilities.bold());
                    title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                    title.setGravity(Gravity.CENTER);
                    title.setText(LocaleController.getString(R.string.TgfeedFeedsEmptyTitle));
                    layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                    TextView text = new TextView(parent.getContext());
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
                    text.setGravity(Gravity.CENTER);
                    text.setText(LocaleController.getString(R.string.TgfeedFeedsEmptyText));
                    layout.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
                    view = layout;
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != ROW_FEED || position >= rows.size()) {
                return;
            }
            FeedRow row = rows.get(position);
            DialogCell cell = (DialogCell) holder.itemView;
            cell.useSeparator = position < rows.size() - 1;
            if (row.post == null) {
                cell.setTgfeedEmptyFeed(row.feed.name, row.feed.id, LocaleController.formatPluralString("TgfeedChannels", row.feed.channelIds.size()));
                return;
            }
            int unread = SharedConfig.tgfeedCountPosts ? unreadPosts(currentAccount, row.feed) : channelsWithNewPosts(currentAccount, row.feed);
            cell.setTgfeedFeed(row.feed.name, row.dialogId, row.post.get(0), row.post.size() > 1 ? row.post : null, unread);
        }
    }
}
