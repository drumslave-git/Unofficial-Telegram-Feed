package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.feeds.FeedsController;

import java.util.List;

/** The feed editor: the name in the action bar with a pencil, "Add channels", and the ordered channels with remove and Undo. */
public class FeedEditActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_ADD = 0;
    private static final int ROW_HEADER = 1;
    private static final int ROW_CHANNEL = 2;
    private static final int ROW_INFO = 3;
    private static final int ROW_SHADOW = 4;

    private static final int MENU_RENAME = 1;

    private final long feedId;
    private Feed feed;
    private RecyclerListView listView;
    private Adapter adapter;

    public FeedEditActivity(long feedId) {
        this.feedId = feedId;
    }

    private FeedsController controller() {
        return getAccountInstance().getFeedsController();
    }

    @Override
    public boolean onFragmentCreate() {
        feed = controller().getFeed(feedId);
        if (feed == null) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.tgfeedFeedsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.tgfeedFeedsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.tgfeedFeedsChanged) {
            feed = controller().getFeed(feedId);
            if (feed == null) {
                finishFragment();
                return;
            }
            if (actionBar != null) {
                actionBar.setTitle(feed.name);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(feed.name);
        actionBar.createMenu().addItem(MENU_RENAME, R.drawable.msg_edit).setContentDescription(LocaleController.getString(R.string.TgfeedRename));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_RENAME) {
                    TgfeedAlerts.promptName(FeedEditActivity.this, LocaleController.getString(R.string.TgfeedRename), feed.name, name -> {
                        feed.name = name;
                        controller().updateFeed(feed);
                    });
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (adapter.rowType(position) == ROW_ADD) {
                openAddChannels();
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void openAddChannels() {
        new AddChannelsSheet(this, feed, channelIds -> {
            for (long channelId : channelIds) {
                feed.addChannel(channelId);
            }
            controller().updateFeed(feed);
        }).show();
    }

    private void removeChannel(long channelId) {
        int position = feed.removeChannel(channelId);
        if (position < 0) {
            return;
        }
        controller().updateFeed(feed);
        TLRPC.Chat chat = getMessagesController().getChat(channelId);
        String title = chat != null ? chat.title : "";
        BulletinFactory.of(this).createUndoBulletin(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.TgfeedChannelRemoved, title)), () -> {
            if (feed.addChannel(channelId, position)) {
                controller().updateFeed(feed);
            }
        }, null).show();
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        int rowType(int position) {
            if (position == 0) {
                return ROW_ADD;
            }
            if (feed.channelIds.isEmpty()) {
                return position == 1 ? ROW_INFO : ROW_SHADOW;
            }
            if (position == 1) {
                return ROW_HEADER;
            }
            return position - 2 < feed.channelIds.size() ? ROW_CHANNEL : ROW_SHADOW;
        }

        @Override
        public int getItemCount() {
            return feed.channelIds.isEmpty() ? 3 : feed.channelIds.size() + 3;
        }

        @Override
        public int getItemViewType(int position) {
            return rowType(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == ROW_ADD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case ROW_ADD: {
                    TextCell cell = new TextCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedAddChannels), R.drawable.msg_add, false);
                    view = cell;
                    break;
                }
                case ROW_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.setText(LocaleController.getString(R.string.TgfeedChannelsHeader));
                    view = cell;
                    break;
                }
                case ROW_CHANNEL: {
                    UserCell cell = new UserCell(context, 6, 0, false);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case ROW_INFO: {
                    TextView text = new TextView(context);
                    text.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                    text.setGravity(Gravity.CENTER);
                    text.setPadding(dp(24), dp(20), dp(24), dp(24));
                    text.setText(LocaleController.getString(R.string.TgfeedNoChannelsInFeed));
                    view = text;
                    break;
                }
                case ROW_SHADOW:
                default:
                    view = new ShadowSectionCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != ROW_CHANNEL) {
                return;
            }
            List<Long> ids = feed.channelIds;
            int index = position - 2;
            if (index < 0 || index >= ids.size()) {
                return;
            }
            long channelId = ids.get(index);
            TLRPC.Chat chat = getMessagesController().getChat(channelId);
            UserCell cell = (UserCell) holder.itemView;
            String status = chat != null && chat.participants_count > 0 ? LocaleController.formatPluralString("Subscribers", chat.participants_count) : null;
            cell.setData(chat, chat != null ? null : String.valueOf(channelId), status, 0, index < ids.size() - 1);
            cell.setCloseIcon(v -> removeChannel(channelId));
        }
    }
}
