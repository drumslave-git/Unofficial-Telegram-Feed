package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ContextLinkCell;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.Cells.SharedLinkCell;
import org.telegram.ui.Cells.SharedPhotoVideoCell2;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmbedBottomSheet;
import org.telegram.ui.Components.ExtendedGridLayoutManager;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.Size;
import org.telegram.ui.PhotoViewer;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.feeds.FeedSearch;
import org.unofficial.telegramfeed.feeds.FeedsController;

import java.util.ArrayList;

/**
 * The feed's info: its name and channel count in the action bar, the editor behind the pencil,
 * and the shared media of all its channels together under the tabs Media, Files, Links, Music,
 * Voice and GIFs. Each tab is a merged {@code messages.search} with that kind's filter.
 */
public class FeedInfoActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int TAB_MEDIA = 0;
    private static final int TAB_FILES = 1;
    private static final int TAB_LINKS = 2;
    private static final int TAB_MUSIC = 3;
    private static final int TAB_VOICE = 4;
    private static final int TAB_GIFS = 5;
    private static final int TAB_COUNT = 6;
    private static final int COLUMNS = 3;

    private static final int MENU_EDIT = 1;

    private final long feedId;
    private Feed feed;
    private ScrollSlidingTextTabStrip tabs;
    private FrameLayout pagesContainer;
    private final Page[] pages = new Page[TAB_COUNT];
    private int selectedTab = TAB_MEDIA;
    private SharedPhotoVideoCell2.SharedResources sharedResources;

    /** One tab: its search, its list and the empty and loading views. */
    private class Page {
        final int tab;
        final FeedSearch search;
        FrameLayout view;
        RecyclerListView list;
        PageAdapter adapter;
        TextView emptyView;
        RadialProgressView progress;

        Page(int tab) {
            this.tab = tab;
            search = new FeedSearch(currentAccount, () -> onResults(this));
        }
    }

    public FeedInfoActivity(long feedId) {
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
        for (Page page : pages) {
            if (page != null) {
                page.search.cancel();
            }
        }
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.tgfeedFeedsChanged) {
            Feed updated = controller().getFeed(feedId);
            if (updated == null) {
                finishFragment();
                return;
            }
            boolean channelsChanged = !updated.channelIds.equals(feed.channelIds);
            feed = updated;
            updateTitle();
            if (channelsChanged) {
                for (Page page : pages) {
                    if (page != null) {
                        page.search.search(feed.channelIds, "", filterFor(page.tab));
                        onResults(page);
                    }
                }
            }
        }
    }

    private void updateTitle() {
        if (actionBar == null) {
            return;
        }
        actionBar.setTitle(feed.name);
        actionBar.setSubtitle(LocaleController.formatPluralString("TgfeedChannels", feed.channelIds.size()));
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        updateTitle();
        actionBar.createMenu().addItem(MENU_EDIT, R.drawable.msg_edit).setContentDescription(LocaleController.getString(R.string.TgfeedEditChannels));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_EDIT) {
                    presentFragment(new FeedEditActivity(feedId));
                }
            }
        });

        sharedResources = new SharedPhotoVideoCell2.SharedResources(context, null);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        pagesContainer = new FrameLayout(context);
        frameLayout.addView(pagesContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 48, 0, 0));

        tabs = new ScrollSlidingTextTabStrip(context);
        tabs.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        tabs.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
        tabs.addTextTab(TAB_MEDIA, LocaleController.getString(R.string.SharedMediaTab2));
        tabs.addTextTab(TAB_FILES, LocaleController.getString(R.string.SharedFilesTab2));
        tabs.addTextTab(TAB_LINKS, LocaleController.getString(R.string.SharedLinksTab2));
        tabs.addTextTab(TAB_MUSIC, LocaleController.getString(R.string.SharedMusicTab2));
        tabs.addTextTab(TAB_VOICE, LocaleController.getString(R.string.SharedVoiceTab2));
        tabs.addTextTab(TAB_GIFS, LocaleController.getString(R.string.SharedGIFsTab2));
        tabs.setInitialTabId(TAB_MEDIA);
        tabs.finishAddingTabs();
        tabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override
            public void onPageSelected(int page, boolean forward) {
                selectTab(page);
            }

            @Override
            public void onPageScrolled(float progress) {
            }
        });
        frameLayout.addView(tabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP));
        View shadow = new View(context);
        shadow.setBackgroundColor(Theme.getColor(Theme.key_divider));
        frameLayout.addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.TOP, 0, 48, 0, 0));

        selectTab(TAB_MEDIA);
        return fragmentView;
    }

    private static TLRPC.MessagesFilter filterFor(int tab) {
        switch (tab) {
            case TAB_FILES:
                return new TLRPC.TL_inputMessagesFilterDocument();
            case TAB_LINKS:
                return new TLRPC.TL_inputMessagesFilterUrl();
            case TAB_MUSIC:
                return new TLRPC.TL_inputMessagesFilterMusic();
            case TAB_VOICE:
                return new TLRPC.TL_inputMessagesFilterVoice();
            case TAB_GIFS:
                return new TLRPC.TL_inputMessagesFilterGif();
            case TAB_MEDIA:
            default:
                return new TLRPC.TL_inputMessagesFilterPhotoVideo();
        }
    }

    private static int emptyTextFor(int tab) {
        switch (tab) {
            case TAB_FILES:
                return R.string.TgfeedNoFiles;
            case TAB_LINKS:
                return R.string.TgfeedNoLinks;
            case TAB_MUSIC:
                return R.string.TgfeedNoMusic;
            case TAB_VOICE:
                return R.string.TgfeedNoVoice;
            case TAB_GIFS:
                return R.string.TgfeedNoGifs;
            case TAB_MEDIA:
            default:
                return R.string.TgfeedNoMedia;
        }
    }

    private static boolean isGrid(int tab) {
        return tab == TAB_MEDIA;
    }

    /** The GIF's own size, for the flow layout that fits a row of GIFs to the width as the chat's shared media does. */
    private static void sizeOf(TLRPC.Document document, Size size) {
        size.width = size.height = 100;
        if (document == null) {
            return;
        }
        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90);
        if (thumb != null && thumb.w != 0 && thumb.h != 0) {
            size.width = thumb.w;
            size.height = thumb.h;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeImageSize || attribute instanceof TLRPC.TL_documentAttributeVideo) {
                size.width = attribute.w;
                size.height = attribute.h;
                break;
            }
        }
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        for (int i = 0; i < TAB_COUNT; i++) {
            if (pages[i] != null && pages[i].view != null) {
                pages[i].view.setVisibility(i == tab ? View.VISIBLE : View.GONE);
            }
        }
        Page page = pages[tab];
        if (page == null) {
            page = pages[tab] = createPage(tab);
            page.search.search(feed.channelIds, "", filterFor(tab));
            onResults(page);
        }
    }

    private Page createPage(int tab) {
        Context context = getParentActivity();
        Page page = new Page(tab);
        page.view = new FrameLayout(context);

        page.list = new RecyclerListView(context);
        page.list.setClipToPadding(false);
        if (tab == TAB_GIFS) {
            Size size = new Size();
            ExtendedGridLayoutManager manager = new ExtendedGridLayoutManager(context, 100) {
                @Override
                protected Size getSizeForItem(int i) {
                    sizeOf(i < page.search.results.size() ? page.search.results.get(i).getDocument() : null, size);
                    return size;
                }

                @Override
                protected int getFlowItemCount() {
                    return page.search.results.size();
                }
            };
            manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (page.search.results.isEmpty()) {
                        return manager.getSpanCount();
                    }
                    return Math.min(manager.getSpanSizeForItem(position), manager.getSpanCount());
                }
            });
            page.list.setLayoutManager(manager);
            page.list.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                    int position = parent.getChildAdapterPosition(view);
                    outRect.left = 0;
                    outRect.bottom = 0;
                    outRect.top = manager.isFirstRow(position) ? 0 : dp(2);
                    outRect.right = manager.isLastInRow(position) ? 0 : dp(2);
                }
            });
        } else if (isGrid(tab)) {
            page.list.setLayoutManager(new GridLayoutManager(context, COLUMNS));
            page.list.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                    int position = parent.getChildAdapterPosition(view);
                    outRect.right = position % COLUMNS == COLUMNS - 1 ? 0 : dp(2);
                    outRect.bottom = dp(2);
                }
            });
        } else {
            page.list.setLayoutManager(new LinearLayoutManager(context));
        }
        page.adapter = new PageAdapter(page);
        page.list.setAdapter(page.adapter);
        page.list.setOnItemClickListener((view, position) -> onItemClick(page, view, position));
        page.list.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= page.adapter.getItemCount() - 3 * COLUMNS && page.search.hasMore() && !page.search.isLoading()) {
                    page.search.loadMore();
                }
            }
        });
        page.view.addView(page.list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        page.emptyView = new TextView(context);
        page.emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        page.emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        page.emptyView.setGravity(Gravity.CENTER);
        page.emptyView.setPadding(dp(40), 0, dp(40), dp(48));
        page.emptyView.setText(LocaleController.getString(emptyTextFor(tab)));
        page.emptyView.setVisibility(View.GONE);
        page.view.addView(page.emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        page.progress = new RadialProgressView(context);
        page.progress.setVisibility(View.GONE);
        page.view.addView(page.progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        pagesContainer.addView(page.view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return page;
    }

    private void onResults(Page page) {
        if (page.adapter == null) {
            return;
        }
        page.adapter.notifyDataSetChanged();
        boolean empty = page.search.results.isEmpty();
        page.progress.setVisibility(empty && page.search.isLoading() ? View.VISIBLE : View.GONE);
        page.emptyView.setVisibility(empty && !page.search.isLoading() && !page.search.hasMore() ? View.VISIBLE : View.GONE);
        if (!empty && page.search.hasMore() && !page.search.isLoading() && page.search.results.size() < 3 * COLUMNS) {
            page.search.loadMore();
        }
    }

    // ---------------------------------------------------------------- taps

    private void onItemClick(Page page, View view, int position) {
        if (position < 0 || position >= page.search.results.size()) {
            return;
        }
        MessageObject message = page.search.results.get(position);
        if (page.tab == TAB_MEDIA || page.tab == TAB_GIFS) {
            PhotoViewer.getInstance().setParentActivity(this);
            PhotoViewer.getInstance().openPhoto(page.search.results, position, 0, 0, 0, photoViewerProvider);
        } else if (page.tab == TAB_MUSIC || page.tab == TAB_VOICE) {
            if (view instanceof SharedAudioCell) {
                ((SharedAudioCell) view).didPressedButton();
            }
        } else if (page.tab == TAB_FILES) {
            if (!(view instanceof SharedDocumentCell)) {
                return;
            }
            SharedDocumentCell cell = (SharedDocumentCell) view;
            TLRPC.Document document = message.getDocument();
            if (cell.isLoaded()) {
                if (message.canPreviewDocument()) {
                    PhotoViewer.getInstance().setParentActivity(this);
                    PhotoViewer.getInstance().openPhoto(page.search.results, position, 0, 0, 0, photoViewerProvider);
                    return;
                }
                AndroidUtilities.openDocument(message, getParentActivity(), this);
            } else if (!cell.isLoading()) {
                message.putInDownloadsStore = true;
                getFileLoader().loadFile(document, message, FileLoader.PRIORITY_LOW, 0);
                cell.updateFileExistIcon(true);
            } else {
                getFileLoader().cancelLoadFile(document);
                cell.updateFileExistIcon(true);
            }
        } else if (page.tab == TAB_LINKS) {
            try {
                TLRPC.WebPage webPage = MessageObject.getMedia(message.messageOwner) != null ? MessageObject.getMedia(message.messageOwner).webpage : null;
                String link = null;
                if (webPage != null && !(webPage instanceof TLRPC.TL_webPageEmpty)) {
                    if (webPage.cached_page != null) {
                        createArticleViewer(false).open(message);
                        return;
                    } else if (webPage.embed_url != null && webPage.embed_url.length() != 0) {
                        openWebView(webPage, message);
                        return;
                    } else {
                        link = webPage.url;
                    }
                }
                if (link == null && view instanceof SharedLinkCell) {
                    link = ((SharedLinkCell) view).getLink(0);
                }
                if (link != null) {
                    openUrl(link);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private void openWebView(TLRPC.WebPage webPage, MessageObject message) {
        EmbedBottomSheet.show(this, message, photoViewerProvider, webPage.site_name, webPage.description, webPage.url, webPage.embed_url, webPage.embed_width, webPage.embed_height, false);
    }

    private void openUrl(String link) {
        if (AndroidUtilities.shouldShowUrlInAlert(link)) {
            AlertsCreator.showOpenUrlAlert(this, link, true, true);
        } else {
            Browser.openUrl(getParentActivity(), link);
        }
    }

    private final SharedLinkCell.SharedLinkCellDelegate linkDelegate = new SharedLinkCell.SharedLinkCellDelegate() {
        @Override
        public void needOpenWebView(TLRPC.WebPage webPage, MessageObject message) {
            openWebView(webPage, message);
        }

        @Override
        public boolean canPerformActions() {
            return true;
        }

        @Override
        public void onLinkPress(String url, boolean longPress) {
            if (longPress) {
                AndroidUtilities.addToClipboard(url);
                BulletinFactory.of(FeedInfoActivity.this).createCopyLinkBulletin().show();
            } else {
                openUrl(url);
            }
        }
    };

    private final PhotoViewer.PhotoViewerProvider photoViewerProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            Page page = pages[selectedTab];
            if (page == null || page.list == null || messageObject == null) {
                return null;
            }
            RecyclerListView list = page.list;
            for (int i = 0; i < list.getChildCount(); i++) {
                View view = list.getChildAt(i);
                int[] coords = new int[2];
                ImageReceiver imageReceiver = null;
                if (view instanceof SharedPhotoVideoCell2) {
                    SharedPhotoVideoCell2 cell = (SharedPhotoVideoCell2) view;
                    MessageObject message = cell.getMessageObject();
                    if (message != null && message.getId() == messageObject.getId() && message.getDialogId() == messageObject.getDialogId()) {
                        imageReceiver = cell.imageReceiver;
                        cell.getLocationInWindow(coords);
                        coords[0] += Math.round(cell.imageReceiver.getImageX());
                        coords[1] += Math.round(cell.imageReceiver.getImageY());
                    }
                } else if (view instanceof ContextLinkCell) {
                    ContextLinkCell cell = (ContextLinkCell) view;
                    MessageObject message = (MessageObject) cell.getParentObject();
                    if (message != null && message.getId() == messageObject.getId() && message.getDialogId() == messageObject.getDialogId()) {
                        imageReceiver = cell.getPhotoImage();
                        cell.getLocationInWindow(coords);
                    }
                } else if (view instanceof SharedDocumentCell) {
                    SharedDocumentCell cell = (SharedDocumentCell) view;
                    MessageObject message = cell.getMessage();
                    if (message != null && message.getId() == messageObject.getId() && message.getDialogId() == messageObject.getDialogId()) {
                        BackupImageView imageView = cell.getImageView();
                        imageReceiver = imageView.getImageReceiver();
                        imageView.getLocationInWindow(coords);
                    }
                } else if (view instanceof SharedLinkCell) {
                    SharedLinkCell cell = (SharedLinkCell) view;
                    MessageObject message = cell.getMessage();
                    if (message != null && message.getId() == messageObject.getId() && message.getDialogId() == messageObject.getDialogId()) {
                        imageReceiver = cell.getLinkImageView();
                        cell.getLocationInWindow(coords);
                    }
                }
                if (imageReceiver != null) {
                    PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                    object.viewX = coords[0];
                    object.viewY = coords[1];
                    object.parentView = list;
                    object.imageReceiver = imageReceiver;
                    object.allowTakeAnimation = true;
                    object.radius = imageReceiver.getRoundRadius(true);
                    object.thumb = imageReceiver.getBitmapSafe();
                    object.clipTopAddition = 0;
                    return object;
                }
            }
            return null;
        }
    };

    // ---------------------------------------------------------------- adapter

    private class PageAdapter extends RecyclerListView.SelectionAdapter {

        private final Page page;

        PageAdapter(Page page) {
            this.page = page;
        }

        @Override
        public int getItemCount() {
            return page.search.results.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof ContextLinkCell) {
                ImageReceiver imageReceiver = ((ContextLinkCell) holder.itemView).getPhotoImage();
                imageReceiver.setAllowStartAnimation(SharedConfig.isAutoplayGifs());
                if (SharedConfig.isAutoplayGifs()) {
                    imageReceiver.startAnimation();
                }
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (page.tab) {
                case TAB_FILES:
                    view = new SharedDocumentCell(context, SharedDocumentCell.VIEW_TYPE_DEFAULT, null);
                    break;
                case TAB_LINKS: {
                    SharedLinkCell cell = new SharedLinkCell(context, SharedLinkCell.VIEW_TYPE_DEFAULT, null);
                    cell.setDelegate(linkDelegate);
                    view = cell;
                    break;
                }
                case TAB_MUSIC:
                case TAB_VOICE:
                    view = new SharedAudioCell(context, SharedAudioCell.VIEW_TYPE_DEFAULT, null) {
                        @Override
                        public boolean needPlayMessage(MessageObject messageObject) {
                            if (messageObject.isVoice() || messageObject.isRoundVideo()) {
                                boolean result = MediaController.getInstance().playMessage(messageObject);
                                MediaController.getInstance().setVoiceMessagesPlaylist(result ? page.search.results : null, false);
                                return result;
                            } else if (messageObject.isMusic()) {
                                return MediaController.getInstance().setPlaylist(page.search.results, messageObject, 0);
                            }
                            return false;
                        }
                    };
                    break;
                case TAB_GIFS: {
                    ContextLinkCell cell = new ContextLinkCell(context, true, null);
                    cell.setCanPreviewGif(true);
                    view = cell;
                    break;
                }
                case TAB_MEDIA:
                default:
                    view = new SharedPhotoVideoCell2(context, sharedResources, currentAccount);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ArrayList<MessageObject> results = page.search.results;
            if (position < 0 || position >= results.size()) {
                return;
            }
            MessageObject message = results.get(position);
            boolean divider = position < results.size() - 1;
            View view = holder.itemView;
            if (view instanceof SharedPhotoVideoCell2) {
                ((SharedPhotoVideoCell2) view).setMessageObject(message, COLUMNS);
            } else if (view instanceof ContextLinkCell) {
                TLRPC.Document document = message.getDocument();
                if (document != null) {
                    ((ContextLinkCell) view).setGif(document, message, message.messageOwner.date, false);
                }
            } else if (view instanceof SharedDocumentCell) {
                ((SharedDocumentCell) view).setDocument(message, divider);
            } else if (view instanceof SharedLinkCell) {
                ((SharedLinkCell) view).setLink(message, divider);
            } else if (view instanceof SharedAudioCell) {
                ((SharedAudioCell) view).setMessageObject(message, divider);
            }
        }
    }
}
