package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatLoadingCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ChatUnreadCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ReportBottomSheet;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.ReactionsContainerLayout;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.TopicsFragment;
import org.telegram.ui.Components.CounterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedOrder;
import org.unofficial.telegramfeed.core.FeedFilter;
import org.unofficial.telegramfeed.feeds.FeedsController;
import org.unofficial.telegramfeed.feeds.PostFilter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A feed's timeline: the posts of all its channels in one chronological list, oldest on top,
 * drawn with Telegram's message cells in the group layout so that every post carries its
 * channel's name and photo. History comes per channel through {@code MessagesController.loadMessages}
 * and is merged by date; posts older than the oldest loaded post of any channel that still has
 * history wait until that channel has loaded as far, so the list never shows a gap.
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, DialogsActivity.DialogsActivityDelegate {

    private static final int MENU_EDIT = 1;
    private static final int ACTION_COPY = 10;
    private static final int ACTION_FORWARD = 11;
    private static final int ACTION_SHARE = 12;
    private static final int INITIAL_COUNT = 30;
    private static final int OLDER_COUNT = 50;

    /** The loading state of one channel's history. */
    private static class ChannelState {
        final long channelId;
        final int classGuid = ConnectionsManager.generateClassGuid();
        int loadIndex;
        boolean loading;
        boolean loadedOnce;
        boolean cacheEnd;
        boolean endReached;
        int minId = Integer.MAX_VALUE;
        int minDate = Integer.MAX_VALUE;

        ChannelState(long channelId) {
            this.channelId = channelId;
        }
    }

    private final long feedId;
    private Feed feed;
    private final HashMap<Long, ChannelState> channels = new HashMap<>();
    /** Every loaded post, keyed by channel and message id. */
    private final HashMap<Long, MessageObject> posts = new HashMap<>();
    /** The rows, newest first, as ChatActivity keeps them: posts, date rows and the unread divider. */
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final HashMap<Long, MessageObject.GroupedMessages> groups = new HashMap<>();
    private final HashMap<Long, Integer> stableIds = new HashMap<>();
    /** The posts the filter leaves out, dropped or folded, keyed like {@link #posts}. */
    private final HashSet<Long> hidden = new HashSet<>();
    /** The folded posts that have a one-line row: one key per post or album. */
    private final HashSet<Long> minimized = new HashSet<>();
    /** The one line of each folded row. */
    private final HashMap<Long, CharSequence> summaries = new HashMap<>();
    /** The folded posts the reader opened with a tap. */
    private final HashSet<Long> expanded = new HashSet<>();
    private int nextStableId = 10;
    private long unreadDividerKey;
    private boolean unreadDividerDecided;
    private boolean unreadDividerSeen;
    private boolean restoredPosition;

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private ChatActionCell floatingDateView;
    private FrameLayout pagedownButton;
    private CounterView pagedownCounter;
    private TextView emptyView;
    private NumberTextView selectedCountView;
    /** The selected posts in selection mode, keyed by channel and id, in selection order. */
    private final LinkedHashMap<Long, MessageObject> selectedPosts = new LinkedHashMap<>();
    private final PhotoViewer.PhotoViewerProvider photoViewerProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (listView == null || messageObject == null) {
                return null;
            }
            for (int a = 0; a < listView.getChildCount(); a++) {
                View view = listView.getChildAt(a);
                if (!(view instanceof ChatMessageCell)) {
                    continue;
                }
                ChatMessageCell cell = (ChatMessageCell) view;
                MessageObject message = cell.getMessageObject();
                if (message == null || message.getId() != messageObject.getId() || message.getDialogId() != messageObject.getDialogId()) {
                    continue;
                }
                ImageReceiver imageReceiver = cell.getPhotoImage();
                int[] coords = new int[2];
                view.getLocationInWindow(coords);
                PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                object.viewX = coords[0];
                object.viewY = coords[1];
                object.parentView = listView;
                object.imageReceiver = imageReceiver;
                object.thumb = imageReceiver.getBitmapSafe();
                object.radius = imageReceiver.getRoundRadius(true);
                object.isEvent = false;
                return object;
            }
            return null;
        }
    };
    private boolean scrolling;
    private final Runnable hideFloatingDate = () -> {
        if (floatingDateView != null) {
            floatingDateView.animate().alpha(0f).setDuration(150).start();
        }
    };

    public FeedActivity(long feedId) {
        this.feedId = feedId;
    }

    private FeedsController controller() {
        return getAccountInstance().getFeedsController();
    }

    private static long key(long channelId, int messageId) {
        return (channelId << 32) | (messageId & 0xFFFFFFFFL);
    }

    @Override
    public boolean onFragmentCreate() {
        feed = controller().getFeed(feedId);
        if (feed == null) {
            return false;
        }
        for (long channelId : feed.channelIds) {
            channels.put(channelId, new ChannelState(channelId));
        }
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().addObserver(this, NotificationCenter.tgfeedFeedsChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        loadInitial();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        savePosition();
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().removeObserver(this, NotificationCenter.tgfeedFeedsChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        for (ChannelState state : channels.values()) {
            getConnectionsManager().cancelRequestsForGuid(state.classGuid);
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        savePosition();
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        updateTitle();
        actionBar.createMenu().addItem(MENU_EDIT, R.drawable.msg_edit).setContentDescription(LocaleController.getString(R.string.TgfeedEditChannels));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (actionBar.isActionModeShowed()) {
                        clearSelection();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_EDIT) {
                    presentFragment(new FeedEditActivity(feedId));
                } else if (id == ACTION_COPY) {
                    copyPosts(new ArrayList<>(selectedPosts.values()));
                    clearSelection();
                } else if (id == ACTION_FORWARD) {
                    forwardPosts(new ArrayList<>(selectedPosts.values()));
                } else if (id == ACTION_SHARE) {
                    ArrayList<MessageObject> list = new ArrayList<>(selectedPosts.values());
                    clearSelection();
                    if (!list.isEmpty()) {
                        sharePost(list.get(0));
                    }
                }
            }
        });
        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountView = new NumberTextView(actionMode.getContext());
        selectedCountView.setTextSize(18);
        selectedCountView.setTypeface(AndroidUtilities.bold());
        selectedCountView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 65, 0, 0, 0));
        actionMode.addItemWithWidth(ACTION_COPY, R.drawable.msg_copy, dp(54), LocaleController.getString(R.string.Copy));
        actionMode.addItemWithWidth(ACTION_SHARE, R.drawable.msg_share, dp(54), LocaleController.getString(R.string.ShareFile));
        actionMode.addItemWithWidth(ACTION_FORWARD, R.drawable.msg_forward, dp(54), LocaleController.getString(R.string.Forward));

        contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                return false; // the fragment view sits below the action bar, nothing to clip
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
        };
        contentView.setOccupyStatusBar(false);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        fragmentView = contentView;

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.graphics.drawable.GradientDrawable emptyBackground = new android.graphics.drawable.GradientDrawable();
        emptyBackground.setCornerRadius(dp(12));
        emptyBackground.setColor(Theme.getColor(Theme.key_chat_serviceBackground));
        emptyView.setBackground(emptyBackground);
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        listView = new RecyclerListView(context) {
            @Override
            public boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
                boolean result = super.drawChild(canvas, child, drawingTime);
                if (child instanceof ChatMessageCell) {
                    drawAvatar(canvas, (ChatMessageCell) child);
                }
                return result;
            }

            /** Draws the channel photo at the bottom of a run of pinned cells, as Telegram's chat list does. */
            private void drawAvatar(android.graphics.Canvas canvas, ChatMessageCell cell) {
                org.telegram.messenger.ImageReceiver imageReceiver = cell.getAvatarImage();
                if (imageReceiver == null || cell.getMessageObject() == null) {
                    return;
                }
                boolean updateVisibility = !cell.getMessageObject().deleted && getChildAdapterPosition(cell) != RecyclerView.NO_POSITION;
                int top = (int) cell.getY();
                if (cell.drawPinnedBottom()) {
                    RecyclerView.ViewHolder holder = getChildViewHolder(cell);
                    int p = holder.getAdapterPosition();
                    if (p >= 0 && findViewHolderForAdapterPosition(p + 1) != null) {
                        imageReceiver.setVisible(false, false);
                        return;
                    }
                }
                float tx = cell.getSlidingOffsetX() + cell.getCheckBoxTranslation();
                int y = (int) cell.getY() + cell.getLayoutHeight();
                int maxY = getMeasuredHeight() - getPaddingBottom();
                if (y > maxY) {
                    y = maxY;
                }
                if (cell.drawPinnedTop()) {
                    RecyclerView.ViewHolder holder = getChildViewHolder(cell);
                    int p = holder.getAdapterPosition();
                    if (p >= 0) {
                        for (int tries = 0; tries < 20; tries++) {
                            holder = findViewHolderForAdapterPosition(p - 1);
                            if (holder == null) {
                                break;
                            }
                            top = holder.itemView.getTop();
                            if (holder.itemView instanceof ChatMessageCell && ((ChatMessageCell) holder.itemView).drawPinnedTop()) {
                                p = p - 1;
                            } else {
                                break;
                            }
                        }
                    }
                }
                if (y - dp(48) < top) {
                    y = top + dp(48);
                }
                if (!cell.drawPinnedBottom()) {
                    int cellBottom = (int) (cell.getY() + cell.getMeasuredHeight());
                    if (y > cellBottom) {
                        y = cellBottom;
                    }
                }
                canvas.save();
                if (tx != 0) {
                    canvas.translate(tx, 0);
                }
                if (updateVisibility) {
                    imageReceiver.setImageY(y - dp(44));
                }
                if (cell.shouldDrawAlphaLayer()) {
                    imageReceiver.setAlpha(cell.getAlpha());
                    canvas.scale(cell.getScaleX(), cell.getScaleY(), cell.getX() + cell.getPivotX(), cell.getY() + (cell.getHeight() >> 1));
                } else {
                    imageReceiver.setAlpha(1f);
                }
                if (updateVisibility) {
                    imageReceiver.setVisible(true, false);
                }
                imageReceiver.draw(canvas);
                canvas.restore();
            }
        };
        listView.setTag(1);
        listView.setVerticalScrollBarEnabled(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(3));
        listView.setItemAnimator(null);
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        adapter = new Adapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((RecyclerListView.OnItemClickListenerExtended) (view, position, x, y) -> {
            if (view instanceof MinimizedPostCell) {
                expandPost(((MinimizedPostCell) view).getMessageObject());
                return;
            }
            if (!(view instanceof ChatMessageCell)) {
                return;
            }
            MessageObject message = ((ChatMessageCell) view).getMessageObject();
            if (message == null || message.getId() <= 0) {
                return;
            }
            if (!selectedPosts.isEmpty()) {
                toggleSelection(message);
            } else {
                showPostMenu((ChatMessageCell) view, message);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (!(view instanceof ChatMessageCell)) {
                return false;
            }
            MessageObject message = ((ChatMessageCell) view).getMessageObject();
            if (message == null || message.getId() <= 0) {
                return false;
            }
            toggleSelection(message);
            return true;
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                scrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                if (!scrolling) {
                    AndroidUtilities.runOnUIThread(hideFloatingDate, 700);
                    markVisibleAsRead();
                } else {
                    AndroidUtilities.cancelRunOnUIThread(hideFloatingDate);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy != 0 && scrolling) {
                    updateFloatingDate();
                }
                checkLoadOlder();
                updatePagedownButton();
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        floatingDateView = new ChatActionCell(context);
        floatingDateView.setAlpha(0f);
        floatingDateView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        contentView.addView(floatingDateView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));

        pagedownButton = new FrameLayout(context);
        pagedownButton.setVisibility(View.INVISIBLE);
        ImageView pagedownImage = new ImageView(context);
        pagedownImage.setImageResource(R.drawable.pagedown);
        pagedownImage.setScaleType(ImageView.ScaleType.CENTER);
        pagedownImage.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        pagedownImage.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(42), Theme.getColor(Theme.key_chat_goDownButton), Theme.getColor(Theme.key_listSelector)));
        pagedownButton.addView(pagedownImage, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
        pagedownCounter = new CounterView(context, null);
        pagedownCounter.setColors(Theme.key_chat_goDownButtonCounter, Theme.key_chat_goDownButtonCounterBackground);
        pagedownCounter.setGravity(Gravity.CENTER);
        pagedownButton.addView(pagedownCounter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));
        pagedownButton.setOnClickListener(v -> onPagedown());
        contentView.addView(pagedownButton, LayoutHelper.createFrame(46, 66, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 12, 8));

        rebuildRows();
        return fragmentView;
    }

    private void updateTitle() {
        if (actionBar == null || feed == null) {
            return;
        }
        actionBar.setTitle(feed.name);
        actionBar.setSubtitle(LocaleController.formatPluralString("TgfeedChannels", feed.channelIds.size()));
    }

    // ---------------------------------------------------------------- loading

    private void loadInitial() {
        for (ChannelState state : channels.values()) {
            if (!state.loading && !state.loadedOnce) {
                state.loading = true;
                getMessagesController().loadMessages(-state.channelId, 0, false, INITIAL_COUNT, 0, 0, true, 0, state.classGuid, 2, 0, ChatActivity.MODE_DEFAULT, 0, 0, state.loadIndex++, false);
            }
        }
    }

    private boolean allEnded() {
        for (ChannelState state : channels.values()) {
            if (!state.endReached) {
                return false;
            }
        }
        return true;
    }

    /** The date below which posts wait for the channels that still have history to load. */
    private int displayFloor() {
        int floor = 0;
        for (ChannelState state : channels.values()) {
            if (!state.endReached && state.loadedOnce) {
                floor = Math.max(floor, state.minDate);
            }
        }
        return floor;
    }

    private void checkLoadOlder() {
        if (layoutManager == null || adapter.getItemCount() == 0) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() > 8) {
            return;
        }
        loadOlder();
    }

    private void loadOlder() {
        int floor = displayFloor();
        for (ChannelState state : channels.values()) {
            if (state.loading || state.endReached || !state.loadedOnce) {
                continue;
            }
            if (state.minDate > floor) {
                continue;
            }
            state.loading = true;
            getMessagesController().loadMessages(-state.channelId, 0, false, OLDER_COUNT, state.minId, 0, !state.cacheEnd, state.minDate, state.classGuid, 0, 0, ChatActivity.MODE_DEFAULT, 0, 0, state.loadIndex++, false);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            int classGuid = (Integer) args[10];
            ChannelState state = null;
            for (ChannelState s : channels.values()) {
                if (s.classGuid == classGuid) {
                    state = s;
                    break;
                }
            }
            if (state == null) {
                return;
            }
            ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[2];
            boolean isCache = (Boolean) args[3];
            boolean isEnd = (Boolean) args[9];
            int loadType = (Integer) args[8];
            state.loading = false;
            state.loadedOnce = true;
            boolean added = false;
            for (MessageObject message : objects) {
                if (message.isDateObject || message.getId() <= 0) {
                    continue;
                }
                state.minId = Math.min(state.minId, message.getId());
                state.minDate = Math.min(state.minDate, message.messageOwner.date);
                if (!posts.containsKey(key(state.channelId, message.getId()))) {
                    message.forceAvatar = true;
                    posts.put(key(state.channelId, message.getId()), message);
                    added = true;
                }
            }
            if (isCache) {
                if (isEnd || objects.size() < (loadType == 2 ? INITIAL_COUNT : OLDER_COUNT)) {
                    state.cacheEnd = true;
                }
                if (objects.isEmpty() && !isEnd) {
                    state.loading = true;
                    getMessagesController().loadMessages(-state.channelId, 0, false, loadType == 2 ? INITIAL_COUNT : OLDER_COUNT, loadType == 2 ? 0 : state.minId, 0, false, loadType == 2 ? 0 : state.minDate, state.classGuid, loadType, 0, ChatActivity.MODE_DEFAULT, 0, 0, state.loadIndex++, false);
                    return;
                }
            } else if (isEnd || objects.isEmpty()) {
                state.endReached = true;
            }
            rebuildRows();
            if (!restoredPosition) {
                restorePosition();
            }
            AndroidUtilities.runOnUIThread(this::checkLoadOlder);
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            ChannelState state = channels.get(-dialogId);
            if (state == null || scheduled) {
                return;
            }
            boolean atBottom = isAtBottom();
            ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[1];
            boolean added = false;
            for (MessageObject message : objects) {
                if (message.getId() <= 0 || message.isDateObject) {
                    continue;
                }
                long k = key(state.channelId, message.getId());
                if (!posts.containsKey(k)) {
                    message.forceAvatar = true;
                    posts.put(k, message);
                    added = true;
                }
            }
            if (added) {
                rebuildRows();
                if (atBottom) {
                    scrollToBottom();
                    markVisibleAsRead();
                }
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            long channelId = (Long) args[1];
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (scheduled || !channels.containsKey(channelId)) {
                return;
            }
            boolean removed = false;
            for (int messageId : ids) {
                if (posts.remove(key(channelId, messageId)) != null) {
                    removed = true;
                }
            }
            if (removed) {
                rebuildRows();
            }
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            long dialogId = (Long) args[0];
            ChannelState state = channels.get(-dialogId);
            if (state == null) {
                return;
            }
            ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[1];
            boolean replaced = false;
            for (MessageObject message : objects) {
                long k = key(state.channelId, message.getId());
                MessageObject old = posts.get(k);
                if (old != null) {
                    message.forceAvatar = true;
                    message.copyStableParams(old);
                    posts.put(k, message);
                    replaced = true;
                }
            }
            if (replaced) {
                rebuildRows();
            }
        } else if (id == NotificationCenter.tgfeedFeedsChanged) {
            Feed updated = controller().getFeed(feedId);
            if (updated == null) {
                finishFragment();
                return;
            }
            feed = updated;
            updateTitle();
            expanded.clear();
            boolean changed = false;
            for (long channelId : feed.channelIds) {
                if (!channels.containsKey(channelId)) {
                    channels.put(channelId, new ChannelState(channelId));
                    changed = true;
                }
            }
            for (Long channelId : new ArrayList<>(channels.keySet())) {
                if (!feed.hasChannel(channelId)) {
                    ChannelState state = channels.remove(channelId);
                    getConnectionsManager().cancelRequestsForGuid(state.classGuid);
                    for (Long k : new ArrayList<>(posts.keySet())) {
                        if ((k >> 32) == channelId) {
                            posts.remove(k);
                        }
                    }
                    changed = true;
                }
            }
            if (changed) {
                loadInitial();
            }
            rebuildRows();
        } else if (id == NotificationCenter.updateInterfaces) {
            updatePagedownButton();
        }
    }

    // ---------------------------------------------------------------- rows

    private boolean isUnread(MessageObject message) {
        TLRPC.Dialog dialog = getMessagesController().getDialog(message.getDialogId());
        return dialog != null && message.getId() > dialog.read_inbox_max_id;
    }

    private int stableId(long key) {
        Integer id = stableIds.get(key);
        if (id == null) {
            id = nextStableId++;
            stableIds.put(key, id);
        }
        return id;
    }

    private void rebuildRows() {
        ArrayList<MessageObject> all = new ArrayList<>(posts.values());
        int floor = allEnded() ? 0 : displayFloor();
        ArrayList<MessageObject> shown = new ArrayList<>(all.size());
        for (MessageObject message : all) {
            if (message.messageOwner.date >= floor) {
                shown.add(message);
            }
        }
        Collections.sort(shown, (a, b) -> FeedOrder.OLDEST_FIRST.compare(
                new FeedOrder.Key(-a.getDialogId(), a.getId(), a.messageOwner.date),
                new FeedOrder.Key(-b.getDialogId(), b.getId(), b.messageOwner.date)));
        Collections.reverse(shown);
        ArrayList<MessageObject> visible = applyFilter(shown);

        groups.clear();
        for (MessageObject message : visible) {
            if (message.hasValidGroupId() && !minimized.contains(key(-message.getDialogId(), message.getId()))) {
                long groupKey = message.getGroupId() ^ message.getDialogId();
                MessageObject.GroupedMessages group = groups.get(groupKey);
                if (group == null) {
                    group = new MessageObject.GroupedMessages();
                    group.groupId = message.getGroupId();
                    groups.put(groupKey, group);
                }
                group.messages.add(message);
            }
        }
        for (MessageObject.GroupedMessages group : groups.values()) {
            group.calculate();
        }

        if (!unreadDividerDecided && !visible.isEmpty()) {
            boolean anyLoaded = false;
            for (ChannelState state : channels.values()) {
                anyLoaded |= state.loadedOnce;
            }
            if (anyLoaded) {
                for (int i = visible.size() - 1; i >= 0; i--) {
                    MessageObject message = visible.get(i);
                    if (minimized.contains(key(-message.getDialogId(), message.getId()))) {
                        continue;
                    }
                    if (isUnread(message)) {
                        unreadDividerKey = key(-message.getDialogId(), message.getId());
                        break;
                    }
                }
                unreadDividerDecided = true;
            }
        }

        messages.clear();
        int currentDay = Integer.MIN_VALUE;
        int currentDayStart = 0;
        for (MessageObject message : visible) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(message.messageOwner.date * 1000L);
            int day = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
            if (day != currentDay) {
                if (currentDay != Integer.MIN_VALUE) {
                    messages.add(dateRow(currentDayStart, currentDay));
                }
                currentDay = day;
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                currentDayStart = (int) (calendar.getTimeInMillis() / 1000);
            }
            message.stableId = stableId(key(-message.getDialogId(), message.getId()));
            messages.add(message);
            if (unreadDividerKey != 0 && key(-message.getDialogId(), message.getId()) == unreadDividerKey) {
                messages.add(unreadRow());
            }
        }
        if (currentDay != Integer.MIN_VALUE) {
            messages.add(dateRow(currentDayStart, currentDay));
        }

        adapter.updateRows();
        adapter.notifyDataSetChanged();
        updateEmptyView();
        updatePagedownButton();
    }

    /**
     * Drops what the feed's filter leaves out. A post or album the filter hides is gone, or,
     * with "Show minimized", keeps its newest part as a one-line row; a part the reader opened
     * is shown whole again. {@code shown} is newest first with album parts adjacent.
     */
    private ArrayList<MessageObject> applyFilter(ArrayList<MessageObject> shown) {
        hidden.clear();
        minimized.clear();
        summaries.clear();
        if (feed.filter.isEmpty()) {
            return shown;
        }
        HashMap<Long, ArrayList<MessageObject>> albums = new HashMap<>();
        for (MessageObject message : shown) {
            if (message.hasValidGroupId()) {
                long groupKey = message.getGroupId() ^ message.getDialogId();
                ArrayList<MessageObject> parts = albums.get(groupKey);
                if (parts == null) {
                    parts = new ArrayList<>();
                    albums.put(groupKey, parts);
                }
                parts.add(message);
            }
        }
        HashSet<Long> judgedAlbums = new HashSet<>();
        HashSet<Long> dropped = new HashSet<>();
        for (MessageObject message : shown) {
            ArrayList<MessageObject> parts;
            if (message.hasValidGroupId()) {
                long groupKey = message.getGroupId() ^ message.getDialogId();
                if (!judgedAlbums.add(groupKey)) {
                    continue;
                }
                parts = albums.get(groupKey);
            } else {
                parts = new ArrayList<>(1);
                parts.add(message);
            }
            boolean opened = false;
            for (MessageObject part : parts) {
                opened |= expanded.contains(key(-part.getDialogId(), part.getId()));
            }
            if (opened) {
                continue;
            }
            ArrayList<FeedFilter.Post> described = new ArrayList<>(parts.size());
            for (MessageObject part : parts) {
                described.add(PostFilter.describe(part));
            }
            List<FeedFilter.Post> kept = feed.filter.shownParts(described, feed.showWholePost);
            if (kept.isEmpty()) {
                for (int i = 0; i < parts.size(); i++) {
                    long k = key(-parts.get(i).getDialogId(), parts.get(i).getId());
                    hidden.add(k);
                    if (feed.showMinimized && i == 0) {
                        minimized.add(k);
                        summaries.put(k, summary(parts));
                    } else {
                        dropped.add(k);
                    }
                }
            } else {
                for (int i = 0; i < parts.size(); i++) {
                    if (!kept.contains(described.get(i))) {
                        long k = key(-parts.get(i).getDialogId(), parts.get(i).getId());
                        hidden.add(k);
                        dropped.add(k);
                    }
                }
            }
        }
        if (dropped.isEmpty()) {
            return shown;
        }
        ArrayList<MessageObject> visible = new ArrayList<>(shown.size());
        for (MessageObject message : shown) {
            if (!dropped.contains(key(-message.getDialogId(), message.getId()))) {
                visible.add(message);
            }
        }
        return visible;
    }

    /** The one line of a folded post: the first caption of the album, oldest part first. */
    private static CharSequence summary(ArrayList<MessageObject> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (!android.text.TextUtils.isEmpty(parts.get(i).messageOwner.message)) {
                return PostFilter.summary(parts.get(i));
            }
        }
        return PostFilter.summary(parts.get(parts.size() - 1));
    }

    /** Opens a folded post or album in place. */
    private void expandPost(MessageObject message) {
        if (message == null) {
            return;
        }
        if (message.hasValidGroupId()) {
            long groupKey = message.getGroupId() ^ message.getDialogId();
            for (MessageObject post : posts.values()) {
                if (post.hasValidGroupId() && (post.getGroupId() ^ post.getDialogId()) == groupKey) {
                    expanded.add(key(-post.getDialogId(), post.getId()));
                }
            }
        } else {
            expanded.add(key(-message.getDialogId(), message.getId()));
        }
        rebuildRows();
    }

    private MessageObject dateRow(int dayStart, int day) {
        TLRPC.TL_message dateMsg = new TLRPC.TL_message();
        dateMsg.message = LocaleController.formatDateChat(dayStart);
        dateMsg.id = 0;
        dateMsg.date = dayStart;
        MessageObject dateObj = new MessageObject(currentAccount, dateMsg, false, false);
        dateObj.type = 10;
        dateObj.contentType = 1;
        dateObj.isDateObject = true;
        dateObj.stableId = stableId(key(1L << 40, day));
        return dateObj;
    }

    private MessageObject unreadRow() {
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.message = "";
        msg.id = 0;
        MessageObject obj = new MessageObject(currentAccount, msg, false, false);
        obj.type = MessageObject.TYPE_LOADING;
        obj.contentType = 2;
        obj.stableId = 2;
        return obj;
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        if (feed.channelIds.isEmpty()) {
            emptyView.setText(LocaleController.getString(R.string.TgfeedNoChannelsInFeed));
            emptyView.setVisibility(View.VISIBLE);
        } else if (messages.isEmpty() && allEnded()) {
            emptyView.setText(LocaleController.getString(hidden.isEmpty() ? R.string.TgfeedNoPosts : R.string.TgfeedNoPostsPassFilter));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- scrolling

    private boolean isAtBottom() {
        if (layoutManager == null || adapter.getItemCount() == 0) {
            return true;
        }
        return layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
    }

    private void scrollToBottom() {
        if (layoutManager == null || adapter.getItemCount() == 0) {
            return;
        }
        layoutManager.scrollToPositionWithOffset(adapter.getItemCount() - 1, -100000 - listView.getPaddingTop());
        updatePagedownButton();
    }

    private int unreadDividerPosition() {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contentType == 2) {
                return adapter.positionOf(i);
            }
        }
        return -1;
    }

    private void onPagedown() {
        int divider = unreadDividerPosition();
        if (divider >= 0 && !unreadDividerSeen) {
            unreadDividerSeen = true;
            layoutManager.scrollToPositionWithOffset(divider, dp(48));
        } else {
            scrollToBottom();
        }
        markVisibleAsRead();
    }

    private void updatePagedownButton() {
        if (pagedownButton == null) {
            return;
        }
        boolean show = !isAtBottom();
        if (show != (pagedownButton.getVisibility() == View.VISIBLE)) {
            pagedownButton.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
        int unread = 0;
        for (long channelId : feed.channelIds) {
            TLRPC.Dialog dialog = getMessagesController().getDialog(-channelId);
            if (dialog != null) {
                unread += dialog.unread_count;
            }
        }
        for (MessageObject post : posts.values()) {
            if (hidden.contains(key(-post.getDialogId(), post.getId())) && isUnread(post)) {
                unread--;
            }
        }
        pagedownCounter.setCount(Math.max(0, unread), true);
    }

    private void restorePosition() {
        if (messages.isEmpty() || layoutManager == null) {
            return;
        }
        restoredPosition = true;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        String saved = preferences.getString(positionKey(), null);
        if (saved != null) {
            String[] parts = saved.split(":");
            if (parts.length == 3) {
                try {
                    long savedKey = key(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
                    int offset = Integer.parseInt(parts[2]);
                    for (int i = 0; i < messages.size(); i++) {
                        MessageObject message = messages.get(i);
                        if (!message.isDateObject && message.contentType != 2 && key(-message.getDialogId(), message.getId()) == savedKey) {
                            layoutManager.scrollToPositionWithOffset(adapter.positionOf(i), offset);
                            return;
                        }
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        int divider = unreadDividerPosition();
        if (divider >= 0) {
            unreadDividerSeen = true;
            layoutManager.scrollToPositionWithOffset(divider, dp(48));
        } else {
            scrollToBottom();
        }
    }

    private String positionKey() {
        return "tgfeedPos_" + currentAccount + "_" + feedId;
    }

    private void savePosition() {
        if (layoutManager == null || listView == null || messages.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit();
        if (isAtBottom()) {
            editor.remove(positionKey()).apply();
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof ChatMessageCell) {
                MessageObject message = ((ChatMessageCell) child).getMessageObject();
                if (message != null) {
                    editor.putString(positionKey(), (-message.getDialogId()) + ":" + message.getId() + ":" + (child.getTop() - listView.getPaddingTop())).apply();
                    return;
                }
            }
        }
    }

    private void updateFloatingDate() {
        View top = null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child.getBottom() > listView.getPaddingTop() && (top == null || child.getTop() < top.getTop())) {
                top = child;
            }
        }
        if (top == null) {
            return;
        }
        MessageObject message = null;
        if (top instanceof ChatMessageCell) {
            message = ((ChatMessageCell) top).getMessageObject();
        } else if (top instanceof ChatActionCell) {
            message = ((ChatActionCell) top).getMessageObject();
        } else if (top instanceof MinimizedPostCell) {
            message = ((MinimizedPostCell) top).getMessageObject();
        }
        if (message == null || message.contentType == 2) {
            return;
        }
        floatingDateView.setCustomDate(message.messageOwner.date, false, true);
        if (top instanceof ChatActionCell && message.isDateObject && top.getTop() >= listView.getPaddingTop()) {
            floatingDateView.setAlpha(0f);
        } else if (floatingDateView.getAlpha() != 1f) {
            floatingDateView.animate().cancel();
            floatingDateView.setAlpha(1f);
        }
    }

    /** Marks each channel read up to the newest post of it on the screen. */
    private void markVisibleAsRead() {
        if (listView == null) {
            return;
        }
        HashMap<Long, MessageObject> newest = new HashMap<>();
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            MessageObject message = null;
            if (child instanceof ChatMessageCell) {
                message = ((ChatMessageCell) child).getMessageObject();
            } else if (child instanceof MinimizedPostCell) {
                message = ((MinimizedPostCell) child).getMessageObject();
            }
            if (message == null || message.getId() <= 0) {
                continue;
            }
            long channelId = -message.getDialogId();
            MessageObject current = newest.get(channelId);
            if (current == null || message.getId() > current.getId()) {
                newest.put(channelId, message);
            }
        }
        for (MessageObject seen : newest.values()) {
            MessageObject message = readBoundary(seen);
            TLRPC.Dialog dialog = getMessagesController().getDialog(message.getDialogId());
            if (dialog == null || message.getId() <= dialog.read_inbox_max_id) {
                continue;
            }
            int countDiff = 0;
            for (MessageObject post : posts.values()) {
                if (post.getDialogId() == message.getDialogId() && post.getId() > dialog.read_inbox_max_id && post.getId() <= message.getId()) {
                    countDiff++;
                }
            }
            getMessagesController().markDialogAsRead(message.getDialogId(), message.getId(), message.getId(), message.messageOwner.date, false, 0, countDiff, true, 0);
        }
        updatePagedownButton();
    }

    /** The post up to which the channel is read when {@code seen} is: the hidden posts right after it go with it. */
    private MessageObject readBoundary(MessageObject seen) {
        int nextShown = Integer.MAX_VALUE;
        for (MessageObject post : posts.values()) {
            if (post.getDialogId() == seen.getDialogId() && post.getId() > seen.getId() && !hidden.contains(key(-post.getDialogId(), post.getId()))) {
                nextShown = Math.min(nextShown, post.getId());
            }
        }
        MessageObject boundary = seen;
        for (MessageObject post : posts.values()) {
            if (post.getDialogId() == seen.getDialogId() && post.getId() > boundary.getId() && post.getId() < nextShown && hidden.contains(key(-post.getDialogId(), post.getId()))) {
                boundary = post;
            }
        }
        return boundary;
    }

    private void openChannel(TLRPC.Chat chat, int postId) {
        if (chat == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        if (postId != 0) {
            args.putInt("message_id", postId);
        }
        if (getMessagesController().checkCanOpenChat(args, this)) {
            presentFragment(new ChatActivity(args));
        }
    }

    // ---------------------------------------------------------------- post actions

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedPosts.isEmpty()) {
            if (invoked) {
                clearSelection();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void toggleSelection(MessageObject message) {
        long k = key(-message.getDialogId(), message.getId());
        if (selectedPosts.remove(k) == null) {
            if (selectedPosts.size() >= 100) {
                return;
            }
            selectedPosts.put(k, message);
        }
        if (selectedPosts.isEmpty()) {
            clearSelection();
            return;
        }
        if (!actionBar.isActionModeShowed()) {
            actionBar.showActionMode();
        }
        selectedCountView.setNumber(selectedPosts.size(), true);
        updateSelectionCells();
    }

    private void clearSelection() {
        selectedPosts.clear();
        if (actionBar != null && actionBar.isActionModeShowed()) {
            actionBar.hideActionMode();
        }
        updateSelectionCells();
    }

    private void updateSelectionCells() {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof ChatMessageCell) {
                MessageObject message = ((ChatMessageCell) child).getMessageObject();
                if (message != null) {
                    applySelection((ChatMessageCell) child, message, true);
                }
            }
        }
    }

    private void applySelection(ChatMessageCell cell, MessageObject message, boolean animated) {
        boolean selecting = !selectedPosts.isEmpty();
        boolean selected = selecting && selectedPosts.containsKey(key(-message.getDialogId(), message.getId()));
        cell.setCheckBoxVisible(selecting, animated);
        cell.setDrawSelectionBackground(selected);
        cell.setChecked(selected, selected, animated);
    }

    private static CharSequence textOf(MessageObject message) {
        if (message.caption != null && message.caption.length() > 0) {
            return message.caption;
        }
        return message.messageText;
    }

    private String postLink(MessageObject message) {
        TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
        String username = chat == null ? null : ChatObject.getPublicUsername(chat);
        if (username == null) {
            return null;
        }
        return "https://" + getMessagesController().linkPrefix + "/" + username + "/" + message.getId();
    }

    private String mediaPath(MessageObject message) {
        String path = message.messageOwner.attachPath;
        if (path != null && !path.isEmpty() && !new File(path).exists()) {
            path = null;
        }
        if (path == null || path.isEmpty()) {
            File f = FileLoader.getInstance(currentAccount).getPathToMessage(message.messageOwner);
            if (f != null && f.exists()) {
                path = f.getPath();
            }
        }
        return path == null || path.isEmpty() ? null : path;
    }

    private void showPostMenu(ChatMessageCell cell, MessageObject message) {
        TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
        ItemOptions options = ItemOptions.makeOptions(this, cell);

        MessageObject.GroupedMessages group = message.hasValidGroupId() ? groups.get(message.getGroupId() ^ message.getDialogId()) : null;
        MessageObject reactionsTarget = group != null && group.findPrimaryMessageObject() != null ? group.findPrimaryMessageObject() : message;
        TLRPC.ChatFull chatFull = chat == null ? null : getMessagesController().getChatFull(chat.id);
        boolean reactionsAvailable = chatFull == null || !(chatFull.available_reactions instanceof TLRPC.TL_chatReactionsNone);
        if (reactionsAvailable) {
            ReactionsContainerLayout reactionsLayout = new ReactionsContainerLayout(ReactionsContainerLayout.TYPE_DEFAULT, this, getContext(), currentAccount, getResourceProvider());
            reactionsLayout.setPadding(dp(4), dp(4), dp(4), dp(4));
            reactionsLayout.setDelegate(new ReactionsContainerLayout.ReactionsContainerDelegate() {
                @Override
                public void onReactionClicked(View view, ReactionsLayoutInBubble.VisibleReaction visibleReaction, boolean longpress, boolean addToRecent) {
                    options.dismiss();
                    sendReaction(reactionsTarget, visibleReaction);
                }

                @Override
                public void hideMenu() {
                    options.dismiss();
                }
            });
            options.addView(reactionsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (52 + reactionsLayout.getTopOffset() / AndroidUtilities.density), Gravity.RIGHT, 0, 0, 0, 0));
            reactionsLayout.setMessage(reactionsTarget, chatFull, true);
        }

        CharSequence text = textOf(message);
        String path = (message.isPhoto() || message.isVideo()) ? mediaPath(message) : null;
        options
                .addIf(message.hasReplies(), R.drawable.msg_viewreplies, LocaleController.getString(R.string.TgfeedComments), () -> openChannel(chat, message.getId()))
                .addIf(text != null && text.length() > 0, R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> copyPosts(Collections.singletonList(message)))
                .add(R.drawable.msg_forward, LocaleController.getString(R.string.Forward), () -> forwardPosts(group != null ? new ArrayList<>(group.messages) : new ArrayList<>(Collections.singletonList(message))))
                .add(R.drawable.msg_share, LocaleController.getString(R.string.ShareFile), () -> sharePost(message))
                .addIf(path != null, R.drawable.msg_gallery, LocaleController.getString(R.string.SaveToGallery), () -> saveToGallery(message, path))
                .add(R.drawable.msg_report, LocaleController.getString(R.string.ReportChat), () -> ReportBottomSheet.openMessage(this, message))
                .add(R.drawable.msg_message, LocaleController.getString(R.string.TgfeedShowInChat), () -> openChannel(chat, message.getId()))
                .show();
    }

    private void sendReaction(MessageObject message, ReactionsLayoutInBubble.VisibleReaction visibleReaction) {
        if (visibleReaction == null) {
            return;
        }
        boolean added = message.selectReaction(visibleReaction, false, false);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> visibleReactions = new ArrayList<>(message.getChoosenReactions());
        getSendMessagesHelper().sendReaction(message, visibleReactions, added ? visibleReaction : null, false, true, this, () -> {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void copyPosts(List<MessageObject> list) {
        StringBuilder builder = new StringBuilder();
        List<MessageObject> ordered = new ArrayList<>(list);
        Collections.sort(ordered, (a, b) -> Integer.compare(a.messageOwner.date, b.messageOwner.date));
        for (MessageObject message : ordered) {
            CharSequence text = textOf(message);
            if (text == null || text.length() == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(text);
        }
        if (builder.length() == 0) {
            return;
        }
        AndroidUtilities.addToClipboard(builder.toString());
        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
    }

    private ArrayList<MessageObject> forwarding;

    private void forwardPosts(ArrayList<MessageObject> list) {
        if (list.isEmpty()) {
            return;
        }
        forwarding = list;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putInt("messagesCount", list.size());
        args.putBoolean("canSelectTopics", true);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate(this);
        presentFragment(fragment);
    }

    @Override
    public boolean didSelectDialogs(DialogsActivity fragment, ArrayList<MessagesStorage.TopicKey> dids, CharSequence message, boolean param, boolean notify, int scheduleDate, int scheduleRepeatPeriod, TopicsFragment topicsFragment) {
        if (forwarding == null || forwarding.isEmpty()) {
            return false;
        }
        ArrayList<MessageObject> list = new ArrayList<>(forwarding);
        Collections.sort(list, (a, b) -> Integer.compare(a.messageOwner.date, b.messageOwner.date));
        forwarding = null;
        for (MessagesStorage.TopicKey key : dids) {
            getSendMessagesHelper().sendMessage(list, key.dialogId, false, false, notify, scheduleDate, 0);
        }
        fragment.finishFragment();
        clearSelection();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.TgfeedForwarded)).show();
        return true;
    }

    private void sharePost(MessageObject message) {
        if (message == null || getParentActivity() == null) {
            return;
        }
        showDialog(ShareAlert.createShareAlert(getParentActivity(), message, null, true, postLink(message), false));
    }

    private void saveToGallery(MessageObject message, String path) {
        if (path == null || getParentActivity() == null) {
            return;
        }
        MediaController.saveFile(path, getParentActivity(), message.isVideo() ? 1 : 0, null, null);
        BulletinFactory.of(this).createDownloadBulletin(message.isVideo() ? BulletinFactory.FileType.VIDEO : BulletinFactory.FileType.PHOTO, getResourceProvider()).show();
    }

    /** Opens the viewer at the post, paging over the pictures and videos of the whole feed. */
    private void openMedia(MessageObject message) {
        if (message == null || getParentActivity() == null) {
            return;
        }
        if (!(message.isPhoto() || message.isVideo() || message.isGif())) {
            return;
        }
        ArrayList<MessageObject> media = new ArrayList<>();
        int index = -1;
        for (MessageObject m : messages) {
            if (m.isDateObject || m.getId() <= 0 || !(m.isPhoto() || m.isVideo() || m.isGif())) {
                continue;
            }
            if (m == message) {
                index = media.size();
            }
            media.add(m);
        }
        if (index < 0) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(this);
        PhotoViewer.getInstance().openPhoto(media, index, 0, 0, 0, photoViewerProvider);
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final Context context;
        private int rowCount;
        private int loadingRow;
        private int messagesStartRow;
        private int messagesEndRow;

        Adapter(Context context) {
            this.context = context;
            setHasStableIds(true);
        }

        void updateRows() {
            rowCount = 0;
            loadingRow = -1;
            if (!messages.isEmpty() && !allEnded()) {
                loadingRow = rowCount++;
            }
            messagesStartRow = rowCount;
            rowCount += messages.size();
            messagesEndRow = rowCount;
        }

        /** The adapter position of the row at {@code index} in {@link #messages} (newest first). */
        int positionOf(int index) {
            return messagesStartRow + (messages.size() - 1 - index);
        }

        MessageObject messageAt(int position) {
            if (position >= messagesStartRow && position < messagesEndRow) {
                return messages.get(messages.size() - (position - messagesStartRow) - 1);
            }
            return null;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public long getItemId(int position) {
            MessageObject message = messageAt(position);
            if (message != null) {
                return message.stableId;
            }
            return position == loadingRow ? 1 : 3;
        }

        @Override
        public int getItemViewType(int position) {
            MessageObject message = messageAt(position);
            if (message != null) {
                if (message.contentType == 0 && minimized.contains(key(-message.getDialogId(), message.getId()))) {
                    return 3;
                }
                return message.contentType;
            }
            return 4;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                    @Override
                    public void didPressChannelAvatar(ChatMessageCell cell, TLRPC.Chat chat, int postId, float touchX, float touchY, boolean asForward) {
                        openChannel(chat, 0);
                    }

                    @Override
                    public boolean canPerformActions() {
                        return true;
                    }

                    @Override
                    public void didLongPress(ChatMessageCell cell, float x, float y) {
                        if (cell.getMessageObject() != null && cell.getMessageObject().getId() > 0) {
                            toggleSelection(cell.getMessageObject());
                        }
                    }

                    @Override
                    public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) {
                        openMedia(cell.getMessageObject());
                    }

                    @Override
                    public void didPressSideButton(ChatMessageCell cell) {
                        sharePost(cell.getMessageObject());
                    }

                    @Override
                    public void didPressCommentButton(ChatMessageCell cell) {
                        MessageObject message = cell.getMessageObject();
                        if (message != null) {
                            openChannel(getMessagesController().getChat(-message.getDialogId()), message.getId());
                        }
                    }

                    @Override
                    public void didPressReaction(ChatMessageCell cell, TLRPC.ReactionCount reaction, boolean longpress, float x, float y) {
                        MessageObject message = cell.getMessageObject();
                        if (message != null && reaction != null) {
                            sendReaction(message, ReactionsLayoutInBubble.VisibleReaction.fromTL(reaction.reaction));
                        }
                    }
                });
                view = cell;
            } else if (viewType == 1) {
                view = new ChatActionCell(context);
            } else if (viewType == 2) {
                view = new ChatUnreadCell(context, null);
            } else if (viewType == 3) {
                view = new MinimizedPostCell(context);
            } else {
                view = new ChatLoadingCell(context, contentView, null);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == loadingRow) {
                ((ChatLoadingCell) holder.itemView).setProgressVisible(true);
                return;
            }
            MessageObject message = messageAt(position);
            if (message == null) {
                return;
            }
            View view = holder.itemView;
            if (view instanceof ChatMessageCell) {
                ChatMessageCell cell = (ChatMessageCell) view;
                cell.isChat = true;
                MessageObject.GroupedMessages group = message.hasValidGroupId() ? groups.get(message.getGroupId() ^ message.getDialogId()) : null;
                boolean pinnedTop = false;
                boolean pinnedBottom = false;
                int prevPosition;
                int nextPosition;
                if (group != null) {
                    MessageObject.GroupedMessagePosition pos = group.getPosition(message);
                    if (pos != null) {
                        if (group.isDocuments) {
                            prevPosition = position + group.posArray.indexOf(pos) + 1;
                            nextPosition = position - group.posArray.size() + group.posArray.indexOf(pos);
                        } else {
                            if ((pos.flags & MessageObject.POSITION_FLAG_TOP) != 0) {
                                prevPosition = position + group.posArray.indexOf(pos) + 1;
                            } else {
                                pinnedTop = true;
                                prevPosition = -100;
                            }
                            if ((pos.flags & MessageObject.POSITION_FLAG_BOTTOM) != 0) {
                                nextPosition = position - group.posArray.size() + group.posArray.indexOf(pos);
                            } else {
                                pinnedBottom = true;
                                nextPosition = -100;
                            }
                        }
                    } else {
                        prevPosition = -100;
                        nextPosition = -100;
                    }
                } else {
                    nextPosition = position - 1;
                    prevPosition = position + 1;
                }
                if (!pinnedBottom && getItemViewType(nextPosition) == 0) {
                    MessageObject next = messageAt(nextPosition);
                    pinnedBottom = next != null && next.getDialogId() == message.getDialogId() && Math.abs(next.messageOwner.date - message.messageOwner.date) <= 5 * 60;
                }
                if (!pinnedTop && getItemViewType(prevPosition) == 0) {
                    MessageObject prev = messageAt(prevPosition);
                    pinnedTop = prev != null && prev.getDialogId() == message.getDialogId() && Math.abs(prev.messageOwner.date - message.messageOwner.date) <= 5 * 60;
                }
                cell.setMessageObject(message, group, pinnedBottom, pinnedTop, false);
                cell.setHighlighted(false);
                applySelection(cell, message, false);
            } else if (view instanceof ChatActionCell) {
                ChatActionCell cell = (ChatActionCell) view;
                cell.setMessageObject(message);
                cell.setAlpha(1f);
            } else if (view instanceof ChatUnreadCell) {
                ((ChatUnreadCell) view).setText(LocaleController.getString(R.string.TgfeedUnreadPosts));
            } else if (view instanceof MinimizedPostCell) {
                TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
                ((MinimizedPostCell) view).set(message, chat != null ? chat.title : "", summaries.get(key(-message.getDialogId(), message.getId())));
            }
        }
    }
}
