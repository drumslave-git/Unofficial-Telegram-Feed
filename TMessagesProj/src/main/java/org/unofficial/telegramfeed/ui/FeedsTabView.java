package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.feeds.FeedsController;

import java.util.ArrayList;
import java.util.List;

/**
 * The Feeds tab of the chat list: the feeds with their counts, a drag handle to reorder, the
 * row menu, "New feed", and the empty state. Lives inside a {@code DialogsActivity.ViewPage}
 * over the dialogs list and takes that list's paddings.
 */
public class FeedsTabView extends FrameLayout {

    private static final int ROW_FEED = 0;
    private static final int ROW_NEW_FEED = 1;
    private static final int ROW_SHADOW = 2;
    private static final int ROW_EMPTY = 3;

    private final BaseFragment fragment;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final View dialogsList;
    private final RecyclerListView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter;
    private final ItemTouchHelper itemTouchHelper;
    private boolean reordering;

    public FeedsTabView(Context context, BaseFragment fragment, View dialogsList, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = fragment.getCurrentAccount();
        this.resourcesProvider = resourcesProvider;
        this.dialogsList = dialogsList;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));

        listView = new RecyclerListView(context, resourcesProvider);
        listView.setClipToPadding(false);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
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
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private FeedsController controller() {
        return FeedsController.getInstance(currentAccount);
    }

    /** Re-reads the feeds and their counts. */
    public void update() {
        if (reordering) {
            return;
        }
        adapter.notifyDataSetChanged();
    }

    public void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (dialogsList != null && (listView.getPaddingTop() != dialogsList.getPaddingTop() || listView.getPaddingBottom() != dialogsList.getPaddingBottom())) {
            listView.setPadding(0, dialogsList.getPaddingTop(), 0, dialogsList.getPaddingBottom());
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
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

        List<Feed> feeds() {
            return controller().getFeeds();
        }

        int rowType(int position) {
            List<Feed> feeds = feeds();
            if (feeds.isEmpty()) {
                return position == 0 ? ROW_EMPTY : position == 1 ? ROW_NEW_FEED : ROW_SHADOW;
            }
            if (position < feeds.size()) {
                return ROW_FEED;
            }
            return position == feeds.size() ? ROW_NEW_FEED : ROW_SHADOW;
        }

        Feed feedAt(int position) {
            List<Feed> feeds = feeds();
            return position >= 0 && position < feeds.size() ? feeds.get(position) : null;
        }

        @Override
        public int getItemCount() {
            List<Feed> feeds = feeds();
            return feeds.isEmpty() ? 3 : feeds.size() + 2;
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
                    FeedCell cell = new FeedCell(parent.getContext(), resourcesProvider);
                    cell.reorderView.setOnTouchListener((v, event) -> {
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            RecyclerView.ViewHolder holder = listView.getChildViewHolder(cell);
                            if (holder != null) {
                                itemTouchHelper.startDrag(holder);
                            }
                        }
                        return false;
                    });
                    view = cell;
                    break;
                }
                case ROW_NEW_FEED: {
                    TextCell cell = new TextCell(parent.getContext(), resourcesProvider);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedNewFeed), R.drawable.msg_add, false);
                    view = cell;
                    break;
                }
                case ROW_EMPTY: {
                    LinearLayout layout = new LinearLayout(parent.getContext());
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
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
                case ROW_SHADOW:
                default:
                    view = new ShadowSectionCell(parent.getContext(), resourcesProvider);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == ROW_FEED) {
                Feed feed = feedAt(position);
                if (feed != null) {
                    int withNew = channelsWithNewPosts(currentAccount, feed);
                    ((FeedCell) holder.itemView).set(feed.name, feed.channelIds.size(), withNew, SharedConfig.tgfeedCountPosts ? unreadPosts(currentAccount, feed) : withNew, position < feeds().size() - 1);
                }
            }
        }
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != ROW_FEED) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            if (source.getItemViewType() != ROW_FEED || target.getItemViewType() != ROW_FEED) {
                return false;
            }
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            reordering = true;
            controller().moveFeed(from, to);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.cancelClickRunnables(false);
                if (viewHolder != null) {
                    viewHolder.itemView.setPressed(true);
                }
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            reordering = false;
            update();
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }
    }
}
