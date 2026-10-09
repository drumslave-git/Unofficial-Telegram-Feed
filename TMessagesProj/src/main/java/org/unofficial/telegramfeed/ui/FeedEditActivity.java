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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedFilter;
import org.unofficial.telegramfeed.feeds.FeedsController;

import java.util.ArrayList;
import java.util.List;

/**
 * The feed editor: the name in the action bar with a pencil, "Add channels", the ordered
 * channels with remove and Undo, and the filter settings below them.
 */
public class FeedEditActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_ADD = 0;
    private static final int ROW_HEADER = 1;
    private static final int ROW_CHANNEL = 2;
    private static final int ROW_INFO = 3;
    private static final int ROW_SHADOW = 4;
    private static final int ROW_FILTER_HEADER = 5;
    private static final int ROW_MODE = 6;
    private static final int ROW_MEDIA = 7;
    private static final int ROW_VIDEO = 8;
    private static final int ROW_TEXT = 9;
    private static final int ROW_WORDS = 10;
    private static final int ROW_WORDS_INFO = 11;
    private static final int ROW_MINIMIZED = 12;
    private static final int ROW_WHOLE = 13;
    private static final int ROW_FILTER_INFO = 14;

    private static final int MENU_RENAME = 1;

    private static final int[] MEDIA_KINDS = {FeedFilter.MEDIA_PHOTO, FeedFilter.MEDIA_VIDEO, FeedFilter.MEDIA_GIF, FeedFilter.MEDIA_MUSIC, FeedFilter.MEDIA_VOICE, FeedFilter.MEDIA_FILE, FeedFilter.MEDIA_OTHER};
    private static final int[] MEDIA_LABELS = {R.string.TgfeedMediaPhotos, R.string.TgfeedMediaVideos, R.string.TgfeedMediaGifs, R.string.TgfeedMediaMusic, R.string.TgfeedMediaVoice, R.string.TgfeedMediaFiles, R.string.TgfeedMediaOther};
    private static final int[] VIDEO_SECONDS = {0, 30, 60, 180, 300, 600, 1800};
    private static final int[] TEXT_LENGTHS = {0, 50, 100, 200, 500, 1000};

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
                adapter.updateRows();
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
        adapter.updateRows();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(adapter.rowType(position), view));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void onRowClick(int type, View view) {
        switch (type) {
            case ROW_ADD:
                openAddChannels();
                break;
            case ROW_MODE:
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedFilterShow), new CharSequence[]{
                        LocaleController.getString(R.string.TgfeedFilterAll),
                        LocaleController.getString(R.string.TgfeedFilterMediaOnly),
                        LocaleController.getString(R.string.TgfeedFilterTextOnly)}, feed.filter.mode, mode -> {
                    feed.filter.mode = mode;
                    controller().updateFeed(feed);
                });
                break;
            case ROW_MEDIA: {
                CharSequence[] labels = new CharSequence[MEDIA_KINDS.length];
                boolean[] checked = new boolean[MEDIA_KINDS.length];
                for (int i = 0; i < MEDIA_KINDS.length; i++) {
                    labels[i] = LocaleController.getString(MEDIA_LABELS[i]);
                    checked[i] = feed.filter.allowsMedia(MEDIA_KINDS[i]);
                }
                TgfeedAlerts.chooseMany(this, LocaleController.getString(R.string.TgfeedMediaTypes), labels, checked, result -> {
                    int types = 0;
                    for (int i = 0; i < MEDIA_KINDS.length; i++) {
                        if (result[i]) {
                            types |= MEDIA_KINDS[i];
                        }
                    }
                    feed.filter.mediaTypes = types;
                    controller().updateFeed(feed);
                });
                break;
            }
            case ROW_VIDEO: {
                CharSequence[] labels = new CharSequence[VIDEO_SECONDS.length];
                for (int i = 0; i < VIDEO_SECONDS.length; i++) {
                    labels[i] = videoLengthLabel(VIDEO_SECONDS[i]);
                }
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedMinVideoLength), labels, indexOf(VIDEO_SECONDS, feed.filter.minVideoSeconds), index -> {
                    feed.filter.minVideoSeconds = VIDEO_SECONDS[index];
                    controller().updateFeed(feed);
                });
                break;
            }
            case ROW_TEXT: {
                CharSequence[] labels = new CharSequence[TEXT_LENGTHS.length];
                for (int i = 0; i < TEXT_LENGTHS.length; i++) {
                    labels[i] = textLengthLabel(TEXT_LENGTHS[i]);
                }
                TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedMinTextLength), labels, indexOf(TEXT_LENGTHS, feed.filter.minTextLength), index -> {
                    feed.filter.minTextLength = TEXT_LENGTHS[index];
                    controller().updateFeed(feed);
                });
                break;
            }
            case ROW_WORDS:
                TgfeedAlerts.promptCondition(this, LocaleController.getString(R.string.TgfeedWords), feed.filter.words, words -> {
                    feed.filter.words = words;
                    controller().updateFeed(feed);
                });
                break;
            case ROW_MINIMIZED:
                feed.showMinimized = !feed.showMinimized;
                ((TextCheckCell) view).setChecked(feed.showMinimized);
                controller().updateFeed(feed);
                break;
            case ROW_WHOLE:
                feed.showWholePost = !feed.showWholePost;
                ((TextCheckCell) view).setChecked(feed.showWholePost);
                controller().updateFeed(feed);
                break;
            default:
                break;
        }
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private static String videoLengthLabel(int seconds) {
        if (seconds <= 0) {
            return LocaleController.getString(R.string.TgfeedAnyLength);
        }
        if (seconds % 60 == 0) {
            return LocaleController.formatPluralString("Minutes", seconds / 60);
        }
        return LocaleController.formatPluralString("Seconds", seconds);
    }

    private static String textLengthLabel(int characters) {
        if (characters <= 0) {
            return LocaleController.getString(R.string.TgfeedAnyLength);
        }
        return LocaleController.formatPluralString("TgfeedCharacters", characters);
    }

    private String modeLabel() {
        switch (feed.filter.mode) {
            case FeedFilter.MODE_MEDIA_ONLY:
                return LocaleController.getString(R.string.TgfeedFilterMediaOnly);
            case FeedFilter.MODE_TEXT_ONLY:
                return LocaleController.getString(R.string.TgfeedFilterTextOnly);
            default:
                return LocaleController.getString(R.string.TgfeedFilterAll);
        }
    }

    private String mediaLabel() {
        int allowed = 0;
        for (int kind : MEDIA_KINDS) {
            if (feed.filter.allowsMedia(kind)) {
                allowed++;
            }
        }
        if (allowed == MEDIA_KINDS.length) {
            return LocaleController.getString(R.string.TgfeedMediaAllKinds);
        }
        return LocaleController.formatString(R.string.TgfeedMediaSomeKinds, allowed, MEDIA_KINDS.length);
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

        private final ArrayList<Integer> rows = new ArrayList<>();
        private int firstChannelRow = -1;

        void updateRows() {
            rows.clear();
            rows.add(ROW_ADD);
            if (feed.channelIds.isEmpty()) {
                rows.add(ROW_INFO);
            } else {
                rows.add(ROW_HEADER);
                firstChannelRow = rows.size();
                for (int i = 0; i < feed.channelIds.size(); i++) {
                    rows.add(ROW_CHANNEL);
                }
            }
            rows.add(ROW_SHADOW);
            rows.add(ROW_FILTER_HEADER);
            rows.add(ROW_MODE);
            rows.add(ROW_MEDIA);
            rows.add(ROW_VIDEO);
            rows.add(ROW_TEXT);
            rows.add(ROW_WORDS);
            rows.add(ROW_WORDS_INFO);
            rows.add(ROW_MINIMIZED);
            rows.add(ROW_WHOLE);
            rows.add(ROW_FILTER_INFO);
        }

        int rowType(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position) : ROW_SHADOW;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rowType(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == ROW_ADD || type == ROW_MODE || type == ROW_MEDIA || type == ROW_VIDEO || type == ROW_TEXT || type == ROW_WORDS || type == ROW_MINIMIZED || type == ROW_WHOLE;
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
                case ROW_HEADER:
                case ROW_FILTER_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.setText(LocaleController.getString(viewType == ROW_HEADER ? R.string.TgfeedChannelsHeader : R.string.TgfeedFilterHeader));
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
                case ROW_MODE:
                case ROW_MEDIA:
                case ROW_VIDEO:
                case ROW_TEXT:
                case ROW_WORDS: {
                    TextCell cell = new TextCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case ROW_MINIMIZED:
                case ROW_WHOLE: {
                    TextCheckCell cell = new TextCheckCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case ROW_WORDS_INFO:
                case ROW_FILTER_INFO: {
                    TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
                    cell.setBackground(Theme.getThemedDrawableByKey(context, viewType == ROW_FILTER_INFO ? R.drawable.greydivider_bottom : R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    view = cell;
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
            View view = holder.itemView;
            switch (holder.getItemViewType()) {
                case ROW_CHANNEL: {
                    List<Long> ids = feed.channelIds;
                    int index = position - firstChannelRow;
                    if (index < 0 || index >= ids.size()) {
                        return;
                    }
                    long channelId = ids.get(index);
                    TLRPC.Chat chat = getMessagesController().getChat(channelId);
                    UserCell cell = (UserCell) view;
                    String status = chat != null && chat.participants_count > 0 ? LocaleController.formatPluralString("Subscribers", chat.participants_count) : null;
                    cell.setData(chat, chat != null ? null : String.valueOf(channelId), status, 0, index < ids.size() - 1);
                    cell.setCloseIcon(v -> removeChannel(channelId));
                    break;
                }
                case ROW_MODE:
                    ((TextCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedFilterShow), modeLabel(), true);
                    break;
                case ROW_MEDIA:
                    ((TextCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedMediaTypes), mediaLabel(), true);
                    break;
                case ROW_VIDEO:
                    ((TextCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedMinVideoLength), videoLengthLabel(feed.filter.minVideoSeconds), true);
                    break;
                case ROW_TEXT:
                    ((TextCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedMinTextLength), textLengthLabel(feed.filter.minTextLength), true);
                    break;
                case ROW_WORDS: {
                    String words = feed.filter.words == null ? "" : feed.filter.words.trim();
                    ((TextCell) view).setTextAndValue(LocaleController.getString(R.string.TgfeedWords), words.isEmpty() ? LocaleController.getString(R.string.TgfeedWordsNone) : words, false);
                    break;
                }
                case ROW_WORDS_INFO:
                    ((TextInfoPrivacyCell) view).setText(LocaleController.getString(R.string.TgfeedWordsInfo));
                    break;
                case ROW_MINIMIZED:
                    ((TextCheckCell) view).setTextAndCheck(LocaleController.getString(R.string.TgfeedShowMinimized), feed.showMinimized, true);
                    break;
                case ROW_WHOLE:
                    ((TextCheckCell) view).setTextAndCheck(LocaleController.getString(R.string.TgfeedShowWholePost), feed.showWholePost, false);
                    break;
                case ROW_FILTER_INFO:
                    ((TextInfoPrivacyCell) view).setText(LocaleController.getString(R.string.TgfeedFilterInfo));
                    break;
                default:
                    break;
            }
        }
    }
}
