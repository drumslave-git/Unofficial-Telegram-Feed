package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * {@code DialogsActivity.ViewPage} over the dialogs list and takes that list's paddings.
 */
public class FeedsTabView extends FrameLayout {

    private static final int ROW_FEED = 0;
    private static final int ROW_NEW_FEED = 1;
    private static final int ROW_EMPTY = 2;

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
    private final View dialogsList;
    private final RecyclerListView listView;
    private final Adapter adapter;
    private final ArrayList<FeedRow> rows = new ArrayList<>();
    private int headerOffset;
    private final NotificationCenter.NotificationCenterDelegate themeObserver = (id, account, args) -> updateColors();

    public FeedsTabView(Context context, BaseFragment fragment, View dialogsList, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = fragment.getCurrentAccount();
        this.resourcesProvider = resourcesProvider;
        this.dialogsList = dialogsList;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        listView = new RecyclerListView(context, resourcesProvider);
        listView.setClipToPadding(false);
        listView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new Adapter();
        listView.setAdapter(adapter);
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
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(themeObserver, NotificationCenter.didSetNewTheme);
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
    }

    public void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    /**
     * How far the chat list's header is moved up from its full height (0 or less): it folds the
     * stories and the search field away as the chats scroll, and the dialogs list scrolls its
     * rows by the same amount, so the feeds start that much higher than the list's padding.
     */
    public void setHeaderOffset(int offset) {
        if (headerOffset != offset) {
            headerOffset = offset;
            updatePadding();
        }
    }

    private void updatePadding() {
        if (dialogsList == null) {
            return;
        }
        int top = Math.max(0, dialogsList.getPaddingTop() + headerOffset);
        if (listView.getPaddingTop() != top || listView.getPaddingBottom() != dialogsList.getPaddingBottom()) {
            listView.setPadding(0, top, 0, dialogsList.getPaddingBottom());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updatePadding();
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
            return rows.isEmpty() ? 2 : rows.size() + 1;
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
