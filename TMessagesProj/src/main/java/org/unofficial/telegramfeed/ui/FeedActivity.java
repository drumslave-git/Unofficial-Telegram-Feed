package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManagerFixed;
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
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.ReactionsContainerLayout;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.TopicsFragment;
import org.telegram.ui.Components.CounterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MessageBackgroundDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedOrder;
import org.unofficial.telegramfeed.core.FeedFilter;
import org.unofficial.telegramfeed.feeds.FeedSearch;
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
    private static final int MENU_SEARCH = 2;
    private static final int MENU_INFO = 3;
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
        int maxId;
        int maxDate;
        /** The channel was loaded around a date and newer posts are still on the server. */
        boolean hasNewer;

        ChannelState(long channelId) {
            this.channelId = channelId;
        }

        /** Forgets the loaded range, keeping the guid and the load index. */
        void reset() {
            loading = false;
            loadedOnce = false;
            cacheEnd = false;
            endReached = false;
            minId = Integer.MAX_VALUE;
            minDate = Integer.MAX_VALUE;
            maxId = 0;
            maxDate = 0;
            hasNewer = false;
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
    private GridLayoutManagerFixed layoutManager;
    private Adapter adapter;
    private ChatActionCell floatingDateView;
    private FrameLayout pagedownButton;
    private CounterView pagedownCounter;
    private TextView emptyView;
    private NumberTextView selectedCountView;
    private ActionBarMenuItem searchItem;
    private HorizontalScrollView filtersView;
    private FrameLayout searchBar;
    private ImageView searchUpButton;
    private ImageView searchDownButton;
    private TextView searchCountText;
    private TextView searchListToggle;
    private RecyclerListView resultsList;
    private ResultsAdapter resultsAdapter;
    private FeedSearch search;
    private FiltersView.MediaFilterData searchFilter;
    private boolean searching;
    /** The match the feed is at, in {@link FeedSearch#results}; -1 for none. */
    private int searchIndex = -1;
    /** A match to go to once its page has loaded. */
    private int pendingSearchIndex = -1;
    /** The post lit up after a jump, keyed like {@link #posts}; 0 for none. */
    private long highlightKey;
    /** A post to scroll to once the history around it has loaded. */
    private long pendingJumpKey;
    /** Whether to show the newest posts once a reload from the top finishes. */
    private boolean scrollToBottomOnLoad;
    private final HashSet<Long> resultKeys = new HashSet<>();
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

    /** The album a shown post is part of, or null for a single post, a folded row or a service row. */
    private MessageObject.GroupedMessages groupOf(MessageObject message) {
        if (message.contentType != 0 || !message.hasValidGroupId() || minimized.contains(key(-message.getDialogId(), message.getId()))) {
            return null;
        }
        return groups.get(message.getGroupId() ^ message.getDialogId());
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
        getNotificationCenter().addObserver(this, NotificationCenter.tgfeedCountsChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        search = new FeedSearch(currentAccount, this::onSearchResults);
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
        getNotificationCenter().removeObserver(this, NotificationCenter.tgfeedCountsChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        for (ChannelState state : channels.values()) {
            getConnectionsManager().cancelRequestsForGuid(state.classGuid);
        }
        search.cancel();
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
        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search);
        searchItem.setIsSearchField(true);
        searchItem.setContentDescription(LocaleController.getString(R.string.Search));
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                showFilters(true);
                updateSearchBar();
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                searchFilter = null;
                searchItem.clearSearchFilters();
                clearSearch();
                showFilters(false);
                showResultsList(false);
                updateSearchBar();
            }

            @Override
            public void onSearchPressed(EditText editText) {
                performSearch();
            }

            @Override
            public void onSearchFilterCleared(FiltersView.MediaFilterData filterData) {
                searchFilter = null;
                showFilters(true);
                performSearch();
            }
        });
        menu.addItem(MENU_INFO, R.drawable.msg_info).setContentDescription(LocaleController.getString(R.string.TgfeedFeedInfo));
        menu.addItem(MENU_EDIT, R.drawable.msg_edit).setContentDescription(LocaleController.getString(R.string.TgfeedEditChannels));
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
                } else if (id == MENU_INFO) {
                    presentFragment(new FeedInfoActivity(feedId));
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
            private final ArrayList<ChatMessageCell> drawTimeAfter = new ArrayList<>();
            private final ArrayList<ChatMessageCell> drawNamesAfter = new ArrayList<>();
            private final ArrayList<ChatMessageCell> drawCaptionAfter = new ArrayList<>();
            private final ArrayList<ChatMessageCell> drawReactionsAfter = new ArrayList<>();
            private final ArrayList<MessageObject.GroupedMessages> drawingGroups = new ArrayList<>(10);
            private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                drawBackgroundElements(canvas);
                super.dispatchDraw(canvas);
                drawForegroundElements(canvas);
            }

            /** As ChatActivity's list: the selection or highlight band, single bubbles, and one bubble under all the cells of an album. */
            private void drawBackgroundElements(Canvas canvas) {
                int count = getChildCount();
                MessageObject.GroupedMessages lastDrawnGroup = null;
                for (int a = 0; a < count; a++) {
                    View child = getChildAt(a);
                    if (!(child instanceof ChatMessageCell) || child.getVisibility() != View.VISIBLE) {
                        continue;
                    }
                    ChatMessageCell cell = (ChatMessageCell) child;
                    MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                    if (group == null || group != lastDrawnGroup) {
                        lastDrawnGroup = group;
                        MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                        MessageBackgroundDrawable backgroundDrawable = cell.getBackgroundDrawable();
                        if ((backgroundDrawable.isAnimationInProgress() || cell.isDrawingSelectionBackground()) && (position == null || (position.flags & MessageObject.POSITION_FLAG_RIGHT) != 0)) {
                            if (cell.isHighlighted() || cell.isHighlightedAnimated()) {
                                if (position == null) {
                                    Paint paint = highlightPaint;
                                    paint.setColor(Theme.getColor(Theme.key_chat_selectedBackground));
                                    canvas.save();
                                    canvas.translate(0, cell.getTranslationY());
                                    int wasAlpha = paint.getAlpha();
                                    paint.setAlpha((int) (wasAlpha * cell.getHighlightAlpha() * cell.getAlpha()));
                                    canvas.drawRect(0, cell.getTop(), getMeasuredWidth(), cell.getBottom(), paint);
                                    paint.setAlpha(wasAlpha);
                                    canvas.restore();
                                }
                            } else {
                                int y = (int) cell.getY();
                                int height;
                                canvas.save();
                                if (position == null) {
                                    height = cell.getMeasuredHeight();
                                } else {
                                    height = y + cell.getMeasuredHeight();
                                    long time = 0;
                                    float touchX = 0;
                                    float touchY = 0;
                                    for (int i = 0; i < count; i++) {
                                        View inner = getChildAt(i);
                                        if (inner instanceof ChatMessageCell && ((ChatMessageCell) inner).getCurrentMessagesGroup() == group) {
                                            ChatMessageCell innerCell = (ChatMessageCell) inner;
                                            MessageBackgroundDrawable drawable = innerCell.getBackgroundDrawable();
                                            y = Math.min(y, (int) innerCell.getY());
                                            height = Math.max(height, (int) innerCell.getY() + innerCell.getMeasuredHeight());
                                            long touchTime = drawable.getLastTouchTime();
                                            if (touchTime > time) {
                                                touchX = drawable.getTouchX() + innerCell.getX();
                                                touchY = drawable.getTouchY() + innerCell.getY();
                                                time = touchTime;
                                            }
                                        }
                                    }
                                    backgroundDrawable.setTouchCoordsOverride(touchX, touchY - y);
                                    height -= y;
                                }
                                canvas.clipRect(0, y, getMeasuredWidth(), y + height);
                                backgroundDrawable.setCustomPaint(null);
                                backgroundDrawable.setColor(Theme.getColor(Theme.key_chat_selectedBackground));
                                backgroundDrawable.setBounds(0, y, getMeasuredWidth(), y + height);
                                backgroundDrawable.draw(canvas);
                                canvas.restore();
                            }
                        }
                    }
                    if (group == null && cell.drawBackgroundInParent()) {
                        canvas.save();
                        canvas.translate(cell.getX(), cell.getY() + cell.getPaddingTop());
                        cell.drawBackgroundInternal(canvas, true);
                        canvas.restore();
                    }
                }

                drawingGroups.clear();
                for (int i = 0; i < count; i++) {
                    View child = getChildAt(i);
                    if (!(child instanceof ChatMessageCell)) {
                        continue;
                    }
                    ChatMessageCell cell = (ChatMessageCell) child;
                    if (child.getY() > getHeight() || child.getY() + child.getHeight() < 0 || cell.getVisibility() == View.GONE) {
                        continue;
                    }
                    MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                    MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                    if (group == null || position == null || group.messages.size() == 1 || cell.getMessageObject().deleted) {
                        continue;
                    }
                    if (!drawingGroups.contains(group)) {
                        group.transitionParams.left = 0;
                        group.transitionParams.top = 0;
                        group.transitionParams.right = 0;
                        group.transitionParams.bottom = 0;
                        group.transitionParams.pinnedBotton = false;
                        group.transitionParams.pinnedTop = false;
                        group.transitionParams.cell = cell;
                        drawingGroups.add(group);
                    }
                    group.transitionParams.pinnedTop = cell.isPinnedTop();
                    group.transitionParams.pinnedBotton = cell.isPinnedBottom();
                    int left = cell.getLeft() + cell.getBackgroundDrawableLeft();
                    int right = cell.getLeft() + cell.getBackgroundDrawableRight();
                    int top = cell.getTop() + cell.getPaddingTop() + cell.getBackgroundDrawableTop();
                    int bottom = cell.getTop() + cell.getPaddingTop() + cell.getBackgroundDrawableBottom();
                    if ((position.flags & MessageObject.POSITION_FLAG_TOP) == 0) {
                        top -= dp(10);
                    }
                    if ((position.flags & MessageObject.POSITION_FLAG_BOTTOM) == 0) {
                        bottom += dp(10);
                    }
                    if (group.transitionParams.top == 0 || top < group.transitionParams.top) {
                        group.transitionParams.top = top;
                    }
                    if (group.transitionParams.bottom == 0 || bottom > group.transitionParams.bottom) {
                        group.transitionParams.bottom = bottom;
                    }
                    if (group.transitionParams.left == 0 || left < group.transitionParams.left) {
                        group.transitionParams.left = left;
                    }
                    if (group.transitionParams.right == 0 || right > group.transitionParams.right) {
                        group.transitionParams.right = right;
                    }
                }
                for (int i = 0; i < drawingGroups.size(); i++) {
                    MessageObject.GroupedMessages group = drawingGroups.get(i);
                    float x = group.transitionParams.cell.getNonAnimationTranslationX(true);
                    float l = group.transitionParams.left + x + group.transitionParams.offsetLeft;
                    float t = group.transitionParams.top + group.transitionParams.offsetTop;
                    float r = group.transitionParams.right + x + group.transitionParams.offsetRight;
                    float b = group.transitionParams.bottom + group.transitionParams.offsetBottom;
                    if (!group.transitionParams.backgroundChangeBounds) {
                        t += group.transitionParams.cell.getTranslationY();
                        b += group.transitionParams.cell.getTranslationY();
                    }
                    if (b > getMeasuredHeight() + dp(20)) {
                        b = getMeasuredHeight() + dp(20);
                    }
                    boolean selected = true;
                    for (int a = 0, n = group.messages.size(); a < n; a++) {
                        MessageObject object = group.messages.get(a);
                        if (!selectedPosts.containsKey(key(-object.getDialogId(), object.getId()))) {
                            selected = false;
                            break;
                        }
                    }
                    group.transitionParams.cell.drawBackground(canvas, (int) l, (int) t, (int) r, (int) b, group.transitionParams.pinnedTop, group.transitionParams.pinnedBotton, selected, 0);
                    group.transitionParams.cell = null;
                    group.transitionParams.drawCaptionLayout = group.hasCaption;
                }
            }

            /** The time, name, caption and reactions of an album are drawn once, over all its cells. */
            private void drawForegroundElements(Canvas canvas) {
                for (int a = 0; a < drawTimeAfter.size(); a++) {
                    ChatMessageCell cell = drawTimeAfter.get(a);
                    canvas.save();
                    canvas.translate(cell.getLeft() + cell.getNonAnimationTranslationX(false), cell.getY() + cell.getPaddingTop());
                    cell.drawTime(canvas, cell.shouldDrawAlphaLayer() ? cell.getAlpha() : 1f, true);
                    canvas.restore();
                }
                drawTimeAfter.clear();
                for (int a = 0; a < drawNamesAfter.size(); a++) {
                    ChatMessageCell cell = drawNamesAfter.get(a);
                    canvas.save();
                    canvas.translate(cell.getLeft() + cell.getNonAnimationTranslationX(false), cell.getY() + cell.getPaddingTop());
                    cell.setInvalidatesParent(true);
                    cell.drawNamesLayout(canvas, cell.shouldDrawAlphaLayer() ? cell.getAlpha() : 1f);
                    cell.setInvalidatesParent(false);
                    canvas.restore();
                }
                drawNamesAfter.clear();
                for (int a = 0; a < drawCaptionAfter.size(); a++) {
                    ChatMessageCell cell = drawCaptionAfter.get(a);
                    boolean selectionOnly = cell.getCurrentPosition() != null && (cell.getCurrentPosition().flags & MessageObject.POSITION_FLAG_LEFT) == 0;
                    if (cell.getTransitionParams().wasDraw) {
                        canvas.save();
                        canvas.translate(cell.getLeft() + cell.getNonAnimationTranslationX(false), cell.getY() + cell.getPaddingTop());
                        cell.setInvalidatesParent(true);
                        cell.drawCaptionLayout(canvas, selectionOnly, cell.shouldDrawAlphaLayer() ? cell.getAlpha() : 1f);
                        cell.setInvalidatesParent(false);
                        canvas.restore();
                    }
                }
                drawCaptionAfter.clear();
                for (int a = 0; a < drawReactionsAfter.size(); a++) {
                    ChatMessageCell cell = drawReactionsAfter.get(a);
                    boolean selectionOnly = cell.getCurrentPosition() != null && (cell.getCurrentPosition().flags & MessageObject.POSITION_FLAG_LEFT) == 0;
                    if (!selectionOnly && cell.getTransitionParams().wasDraw) {
                        float alpha = cell.shouldDrawAlphaLayer() ? cell.getAlpha() : 1f;
                        canvas.save();
                        canvas.translate(cell.getLeft() + cell.getNonAnimationTranslationX(false), cell.getY() + cell.getPaddingTop());
                        cell.setInvalidatesParent(true);
                        cell.drawReactionsLayout(canvas, alpha, null);
                        cell.drawCommentLayout(canvas, alpha);
                        cell.setInvalidatesParent(false);
                        canvas.restore();
                    }
                }
                drawReactionsAfter.clear();
            }

            @Override
            public boolean drawChild(Canvas canvas, View child, long drawingTime) {
                boolean result = super.drawChild(canvas, child, drawingTime);
                if (child instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) child;
                    if (cell.hasOutboundsContent()) {
                        canvas.save();
                        canvas.translate(cell.getX(), cell.getY() + cell.getPaddingTopAnimated());
                        cell.drawOutboundsContent(canvas);
                        canvas.restore();
                    }
                    cell.drawCheckBox(canvas);
                    MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                    if (position != null) {
                        if (position.last) {
                            drawTimeAfter.add(cell);
                        }
                        if (position.minX == 0 && position.minY == 0 && cell.hasNameLayout()) {
                            drawNamesAfter.add(cell);
                        }
                        if ((position.flags & cell.captionFlag()) != 0) {
                            drawCaptionAfter.add(cell);
                        }
                        if ((position.flags & MessageObject.POSITION_FLAG_BOTTOM) != 0 && (position.flags & MessageObject.POSITION_FLAG_LEFT) != 0) {
                            drawReactionsAfter.add(cell);
                        }
                    }
                    drawAvatar(canvas, cell);
                } else if (child instanceof ChatActionCell) {
                    ChatActionCell cell = (ChatActionCell) child;
                    canvas.save();
                    canvas.translate(cell.getX(), cell.getY());
                    cell.drawOutboundsContent(canvas);
                    canvas.restore();
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
                    if (p >= 0) {
                        int nextPosition;
                        MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                        MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                        if (group != null && position != null) {
                            int idx = group.posArray.indexOf(position);
                            int size = group.posArray.size();
                            if ((position.flags & MessageObject.POSITION_FLAG_BOTTOM) != 0) {
                                nextPosition = p - size + idx;
                            } else {
                                nextPosition = p - 1;
                                for (int a = idx + 1; a < size; a++) {
                                    if (group.posArray.get(a).minY > position.maxY) {
                                        break;
                                    }
                                    nextPosition--;
                                }
                            }
                        } else {
                            nextPosition = p - 1;
                        }
                        if (findViewHolderForAdapterPosition(nextPosition) != null) {
                            imageReceiver.setVisible(false, false);
                            return;
                        }
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
                            holder = findViewHolderForAdapterPosition(p + 1);
                            if (holder == null) {
                                break;
                            }
                            top = holder.itemView.getTop();
                            if (holder.itemView instanceof ChatMessageCell && ((ChatMessageCell) holder.itemView).drawPinnedTop()) {
                                p = p + 1;
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
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            listView.setDefaultFocusHighlightEnabled(false); // a hardware Enter must not dim the list
        }
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(3));
        listView.setItemAnimator(null);
        layoutManager = new GridLayoutManagerFixed(context, 1000, LinearLayoutManager.VERTICAL, true) {
            @Override
            public boolean shouldLayoutChildFromOpositeSide(View child) {
                return child instanceof ChatMessageCell;
            }

            @Override
            protected boolean hasSiblingChild(int position) {
                MessageObject message = adapter.messageAt(position);
                MessageObject.GroupedMessages group = message != null ? groupOf(message) : null;
                if (group == null) {
                    return false;
                }
                MessageObject.GroupedMessagePosition pos = group.getPosition(message);
                if (pos == null || pos.minX == pos.maxX || pos.minY != pos.maxY || pos.minY == 0) {
                    return false;
                }
                for (int a = 0; a < group.posArray.size(); a++) {
                    MessageObject.GroupedMessagePosition p = group.posArray.get(a);
                    if (p != pos && p.minY <= pos.minY && p.maxY >= pos.minY) {
                        return true;
                    }
                }
                return false;
            }
        };
        layoutManager.setSpanSizeLookup(new GridLayoutManagerFixed.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                MessageObject message = adapter.messageAt(position);
                MessageObject.GroupedMessages group = message != null ? groupOf(message) : null;
                if (group != null) {
                    MessageObject.GroupedMessagePosition pos = group.getPosition(message);
                    if (pos != null) {
                        return pos.spanSize;
                    }
                }
                return 1000;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                outRect.bottom = 0;
                if (!(view instanceof ChatMessageCell)) {
                    return;
                }
                ChatMessageCell cell = (ChatMessageCell) view;
                MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                if (group == null || position == null || position.siblingHeights == null) {
                    return;
                }
                float maxHeight = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.5f;
                int h = cell.getExtraInsetHeight();
                for (int a = 0; a < position.siblingHeights.length; a++) {
                    h += (int) Math.ceil(maxHeight * position.siblingHeights[a]);
                }
                h += (position.maxY - position.minY) * Math.round(7 * AndroidUtilities.density);
                for (int a = 0; a < group.posArray.size(); a++) {
                    MessageObject.GroupedMessagePosition pos = group.posArray.get(a);
                    if (pos.minY != position.minY || pos.minX == position.minX && pos.maxX == position.maxX && pos.minY == position.minY && pos.maxY == position.maxY) {
                        continue;
                    }
                    if (pos.minY == position.minY) {
                        h -= (int) Math.ceil(maxHeight * pos.ph) - dp(4);
                        break;
                    }
                }
                outRect.bottom = -h;
            }
        });
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
                checkLoadNewer();
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

        createSearchViews(context);

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

    // ---------------------------------------------------------------- search

    private void createSearchViews(Context context) {
        filtersView = new HorizontalScrollView(context);
        filtersView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        filtersView.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(6), dp(7), dp(6), dp(7));
        for (FiltersView.MediaFilterData data : FiltersView.filters) {
            TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setSingleLine(true);
            chip.setText(data.getTitle());
            chip.setCompoundDrawablePadding(dp(6));
            android.graphics.drawable.Drawable icon = context.getResources().getDrawable(data.iconResFilled).mutate();
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon), PorterDuff.Mode.MULTIPLY));
            icon.setBounds(0, 0, dp(20), dp(20));
            chip.setCompoundDrawables(icon, null, null, null);
            chip.setPadding(dp(10), 0, dp(14), 0);
            chip.setFocusable(false);
            android.graphics.drawable.GradientDrawable chipBackground = new android.graphics.drawable.GradientDrawable();
            chipBackground.setCornerRadius(dp(18));
            chipBackground.setColor(Theme.getColor(Theme.key_groupcreate_spanBackground));
            chip.setBackground(chipBackground);
            chip.setOnClickListener(v -> {
                searchFilter = data;
                searchItem.addSearchFilter(data);
                showFilters(false);
                performSearch();
            });
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 6, 0, 0));
        }
        filtersView.addView(chips, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        filtersView.setVisibility(View.GONE);
        contentView.addView(filtersView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.SEARCH_TABS_HEIGHT, Gravity.TOP));

        resultsList = new RecyclerListView(context);
        resultsList.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        resultsList.setLayoutManager(new LinearLayoutManager(context));
        resultsAdapter = new ResultsAdapter(context);
        resultsList.setAdapter(resultsAdapter);
        resultsList.setOnItemClickListener((view, position) -> {
            showResultsList(false);
            jumpTo(position);
        });
        resultsList.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= resultsAdapter.getItemCount() - 5 && search.hasMore() && !search.isLoading()) {
                    search.loadMore();
                }
            }
        });
        resultsList.setVisibility(View.GONE);
        contentView.addView(resultsList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 51));

        searchBar = new FrameLayout(context);
        searchBar.setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelBackground));
        searchBar.setVisibility(View.GONE);
        View shadow = new View(context);
        shadow.setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelShadow));
        searchBar.addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.TOP));
        searchUpButton = new ImageView(context);
        searchUpButton.setScaleType(ImageView.ScaleType.CENTER);
        searchUpButton.setImageResource(R.drawable.msg_go_up);
        searchUpButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_searchPanelIcons), PorterDuff.Mode.MULTIPLY));
        searchUpButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_actionBarActionModeDefaultSelector), 1));
        searchUpButton.setContentDescription(LocaleController.getString(R.string.AccDescrSearchNext));
        searchUpButton.setOnClickListener(v -> jumpTo(searchIndex + 1));
        searchBar.addView(searchUpButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        searchDownButton = new ImageView(context);
        searchDownButton.setScaleType(ImageView.ScaleType.CENTER);
        searchDownButton.setImageResource(R.drawable.msg_go_down);
        searchDownButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_searchPanelIcons), PorterDuff.Mode.MULTIPLY));
        searchDownButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_actionBarActionModeDefaultSelector), 1));
        searchDownButton.setContentDescription(LocaleController.getString(R.string.AccDescrSearchPrev));
        searchDownButton.setOnClickListener(v -> jumpTo(searchIndex - 1));
        searchBar.addView(searchDownButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 52, 0, 0, 0));
        searchCountText = new TextView(context);
        searchCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchCountText.setTypeface(AndroidUtilities.bold());
        searchCountText.setTextColor(Theme.getColor(Theme.key_chat_searchPanelText));
        searchCountText.setSingleLine(true);
        searchBar.addView(searchCountText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 108, 0, 140, 0));
        searchListToggle = new TextView(context);
        searchListToggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchListToggle.setTypeface(AndroidUtilities.bold());
        searchListToggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        searchListToggle.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchListToggle.setOnClickListener(v -> showResultsList(resultsList.getVisibility() != View.VISIBLE));
        searchBar.addView(searchListToggle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        contentView.addView(searchBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 51, Gravity.BOTTOM));
    }

    private void showFilters(boolean show) {
        if (filtersView == null) {
            return;
        }
        boolean visible = show && searching && searchFilter == null;
        filtersView.setVisibility(visible ? View.VISIBLE : View.GONE);
        listView.setPadding(0, dp(visible ? DialogsActivity.SEARCH_TABS_HEIGHT + 4 : 4), 0, listView.getPaddingBottom());
    }

    private void showResultsList(boolean show) {
        if (resultsList == null) {
            return;
        }
        boolean visible = show && search.isActive();
        if (visible) {
            resultsAdapter.notifyDataSetChanged();
        }
        resultsList.setVisibility(visible ? View.VISIBLE : View.GONE);
        updateSearchBar();
    }

    private void clearSearch() {
        search.cancel();
        resultKeys.clear();
        searchIndex = -1;
        pendingSearchIndex = -1;
        highlightKey = 0;
        pendingJumpKey = 0;
        if (searchItem != null) {
            searchItem.setShowSearchProgress(false);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void performSearch() {
        String query = searchItem.getSearchField().getText().toString().trim();
        if (query.isEmpty() && searchFilter == null) {
            clearSearch();
            showResultsList(false);
            updateSearchBar();
            return;
        }
        AndroidUtilities.hideKeyboard(searchItem.getSearchField());
        clearSearch();
        pendingSearchIndex = 0;
        searchItem.setShowSearchProgress(true);
        search.search(feed.channelIds, query, searchFilter != null ? searchFilter.filter : null);
        updateSearchBar();
    }

    private void onSearchResults() {
        resultKeys.clear();
        for (MessageObject message : search.results) {
            resultKeys.add(key(-message.getDialogId(), message.getId()));
        }
        if (!search.isLoading()) {
            searchItem.setShowSearchProgress(false);
        }
        resultsAdapter.notifyDataSetChanged();
        if (pendingSearchIndex >= 0) {
            if (pendingSearchIndex < search.results.size()) {
                int index = pendingSearchIndex;
                pendingSearchIndex = -1;
                jumpTo(index);
            } else if (!search.hasMore()) {
                pendingSearchIndex = -1;
            } else if (!search.isLoading()) {
                search.loadMore();
            }
        }
        updateSearchBar();
        adapter.notifyDataSetChanged();
    }

    private void updateSearchBar() {
        if (searchBar == null) {
            return;
        }
        boolean show = searching && search.isActive();
        searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
        listView.setPadding(0, listView.getPaddingTop(), 0, dp(show ? 54 : 3));
        pagedownButton.setTranslationY(show ? -dp(51) : 0);
        if (!show) {
            return;
        }
        int count = search.results.size();
        boolean list = resultsList.getVisibility() == View.VISIBLE;
        if (count == 0) {
            searchCountText.setText(search.isLoading() ? "" : LocaleController.getString(R.string.NoResult));
        } else if (list) {
            searchCountText.setText(LocaleController.formatPluralString("SearchMessagesResultCount", search.total, LocaleController.formatNumber(search.total, ' ')));
        } else {
            searchCountText.setText(LocaleController.formatString(R.string.Of, searchIndex + 1, Math.max(search.total, count)));
        }
        boolean canOlder = searchIndex + 1 < count || search.hasMore();
        boolean canNewer = searchIndex > 0;
        searchUpButton.setEnabled(canOlder);
        searchUpButton.setAlpha(canOlder ? 1f : 0.5f);
        searchDownButton.setEnabled(canNewer);
        searchDownButton.setAlpha(canNewer ? 1f : 0.5f);
        searchListToggle.setText(LocaleController.getString(list ? R.string.SearchAsChat : R.string.SearchAsList));
        searchListToggle.setEnabled(count > 0);
        searchListToggle.setAlpha(count > 0 ? 1f : 0.5f);
    }

    /** Goes to the match at {@code index}, loading the next page or the history around it first when needed. */
    private void jumpTo(int index) {
        if (index < 0) {
            return;
        }
        if (index >= search.results.size()) {
            if (search.hasMore()) {
                pendingSearchIndex = index;
                if (!search.isLoading()) {
                    searchItem.setShowSearchProgress(true);
                    search.loadMore();
                }
            }
            return;
        }
        searchIndex = index;
        MessageObject found = search.results.get(index);
        long k = key(-found.getDialogId(), found.getId());
        updateSearchBar();
        if (scrollToKey(k)) {
            return;
        }
        if (posts.containsKey(k)) {
            // loaded but not among the rows: the feed's filter leaves it out
            openChannel(getMessagesController().getChat(-found.getDialogId()), found.getId());
            return;
        }
        pendingJumpKey = k;
        loadAround(found.messageOwner.date);
    }

    /** Scrolls to the post and lights it up; false when it is not among the rows. */
    private boolean scrollToKey(long k) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (!message.isDateObject && message.contentType != 2 && key(-message.getDialogId(), message.getId()) == k) {
                highlightKey = k;
                adapter.notifyDataSetChanged();
                int offset = Math.max(dp(60), (listView.getMeasuredHeight() - listView.getPaddingTop() - listView.getPaddingBottom()) / 4);
                layoutManager.scrollToPositionWithOffset(adapter.positionOf(i), offset, false);
                AndroidUtilities.runOnUIThread(() -> {
                    if (highlightKey == k) {
                        highlightKey = 0;
                        for (int j = 0; j < listView.getChildCount(); j++) {
                            View child = listView.getChildAt(j);
                            if (child instanceof ChatMessageCell) {
                                ((ChatMessageCell) child).setHighlighted(false);
                            }
                        }
                    }
                }, 1500);
                return true;
            }
        }
        return false;
    }

    /** Reloads every channel around a date, as a jump to an old match needs; newer posts load as the list scrolls down. */
    private void loadAround(int date) {
        for (ChannelState state : channels.values()) {
            getConnectionsManager().cancelRequestsForGuid(state.classGuid);
            state.reset();
            state.loading = true;
            state.hasNewer = true;
            getMessagesController().loadMessages(-state.channelId, 0, false, INITIAL_COUNT, 0, date, false, 0, state.classGuid, 4, 0, ChatActivity.MODE_DEFAULT, 0, 0, state.loadIndex++, false);
        }
        posts.clear();
        unreadDividerDecided = true;
        unreadDividerKey = 0;
        rebuildRows();
    }

    /** Throws the loaded history away and loads the newest posts again. */
    private void resetToNewest() {
        for (ChannelState state : channels.values()) {
            getConnectionsManager().cancelRequestsForGuid(state.classGuid);
            state.reset();
        }
        posts.clear();
        scrollToBottomOnLoad = true;
        loadInitial();
        rebuildRows();
    }

    private boolean anyHasNewer() {
        for (ChannelState state : channels.values()) {
            if (state.hasNewer) {
                return true;
            }
        }
        return false;
    }

    /** The date above which posts wait for the channels that still have newer history to load. */
    private int displayCeiling() {
        int ceiling = Integer.MAX_VALUE;
        for (ChannelState state : channels.values()) {
            if (state.hasNewer && state.loadedOnce) {
                ceiling = Math.min(ceiling, state.maxDate);
            }
        }
        return ceiling;
    }

    private void checkLoadNewer() {
        if (layoutManager == null || adapter.getItemCount() == 0 || !anyHasNewer()) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() > 8) {
            return;
        }
        loadNewer();
    }

    private void loadNewer() {
        int ceiling = displayCeiling();
        for (ChannelState state : channels.values()) {
            if (state.loading || !state.hasNewer || !state.loadedOnce || state.maxDate > ceiling) {
                continue;
            }
            state.loading = true;
            getMessagesController().loadMessages(-state.channelId, 0, false, OLDER_COUNT, state.maxId, 0, false, 0, state.classGuid, 1, 0, ChatActivity.MODE_DEFAULT, 0, 0, state.loadIndex++, false);
        }
    }

    private class ResultsAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ResultsAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return search.results.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            DialogCell cell = new DialogCell(null, context, false, true, currentAccount, null);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= search.results.size()) {
                return;
            }
            MessageObject message = search.results.get(position);
            DialogCell cell = (DialogCell) holder.itemView;
            cell.useSeparator = position < search.results.size() - 1;
            cell.setDialog(message.getDialogId(), message, message.messageOwner.date, true, false);
        }
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

    private boolean anyLoading() {
        for (ChannelState state : channels.values()) {
            if (state.loading) {
                return true;
            }
        }
        return false;
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
        if (layoutManager.findLastVisibleItemPosition() < adapter.getItemCount() - 8) {
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
                state.maxId = Math.max(state.maxId, message.getId());
                state.maxDate = Math.max(state.maxDate, message.messageOwner.date);
                if (!posts.containsKey(key(state.channelId, message.getId()))) {
                    message.forceAvatar = true;
                    posts.put(key(state.channelId, message.getId()), message);
                    added = true;
                }
            }
            if (loadType == 4) {
                if (objects.size() < INITIAL_COUNT) {
                    state.endReached = true;
                    state.hasNewer = false;
                }
            } else if (loadType == 1) {
                if (objects.size() < OLDER_COUNT) {
                    state.hasNewer = false;
                }
            } else if (isCache) {
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
            if (scrollToBottomOnLoad) {
                scrollToBottomOnLoad = false;
                scrollToBottom();
            }
            if (pendingJumpKey != 0) {
                if (scrollToKey(pendingJumpKey)) {
                    pendingJumpKey = 0;
                } else if (!anyLoading()) {
                    MessageObject target = posts.get(pendingJumpKey);
                    pendingJumpKey = 0;
                    if (target != null) {
                        openChannel(getMessagesController().getChat(-target.getDialogId()), target.getId());
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                checkLoadOlder();
                checkLoadNewer();
            });
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            ChannelState state = channels.get(-dialogId);
            if (state == null || scheduled || state.hasNewer) {
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
        } else if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.tgfeedCountsChanged) {
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
        int ceiling = displayCeiling();
        ArrayList<MessageObject> shown = new ArrayList<>(all.size());
        for (MessageObject message : all) {
            if (message.messageOwner.date >= floor && message.messageOwner.date <= ceiling) {
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
            // the first part takes the first slot of the layout, which the grid lays out at the left
            Collections.sort(group.messages, (a, b) -> a.getId() - b.getId());
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
            return !anyHasNewer();
        }
        return !anyHasNewer() && layoutManager.findFirstVisibleItemPosition() <= 1;
    }

    private void scrollToBottom() {
        if (anyHasNewer()) {
            resetToNewest();
            return;
        }
        if (layoutManager == null || adapter.getItemCount() == 0) {
            return;
        }
        layoutManager.scrollToPositionWithOffset(0, 0, true);
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
            layoutManager.scrollToPositionWithOffset(divider, dp(48), false);
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
        pagedownCounter.setCount(org.unofficial.telegramfeed.feeds.FeedCounts.getInstance(currentAccount).unreadPosts(feed), true);
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
                            layoutManager.scrollToPositionWithOffset(adapter.positionOf(i), offset, false);
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
            layoutManager.scrollToPositionWithOffset(divider, dp(48), false);
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
        if (actionBar != null && actionBar.isSearchFieldVisible()) {
            if (invoked) {
                if (resultsList != null && resultsList.getVisibility() == View.VISIBLE) {
                    showResultsList(false);
                } else {
                    actionBar.closeSearchField();
                }
            }
            return false;
        }
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

        /** Position 0 is the newest post, drawn at the bottom by the reversed layout; the loading row comes last, at the top. */
        void updateRows() {
            rowCount = 0;
            messagesStartRow = rowCount;
            rowCount += messages.size();
            messagesEndRow = rowCount;
            loadingRow = -1;
            if (!messages.isEmpty() && !allEnded()) {
                loadingRow = rowCount++;
            }
        }

        /** The adapter position of the row at {@code index} in {@link #messages} (newest first). */
        int positionOf(int index) {
            return messagesStartRow + index;
        }

        MessageObject messageAt(int position) {
            if (position >= messagesStartRow && position < messagesEndRow) {
                return messages.get(position - messagesStartRow);
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
                MessageObject.GroupedMessages group = groupOf(message);
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
                long k = key(-message.getDialogId(), message.getId());
                cell.setHighlighted(highlightKey != 0 && highlightKey == k);
                cell.setHighlightedText(searching && resultKeys.contains(k) && !TextUtils.isEmpty(search.query) ? search.query : null);
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
