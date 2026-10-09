package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.messenger.utils.TextWatcherImpl;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.feeds.FeedsController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The sheet that adds channels to a feed: the joined channels with a search, multi-select, one
 * "Add" press, and a remembered checkbox that hides channels already in a feed.
 */
public class AddChannelsSheet extends BottomSheetWithRecyclerListView {

    private static final int ID_HIDE = 1;
    private static final String PREF_HIDE = "tgfeedHideChannelsInFeeds";

    private final Feed feed;
    private final Utilities.Callback<List<Long>> onAdd;
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
    private final FrameLayout searchContainer;
    private final FragmentSearchField searchField;
    private ButtonWithCounterView button;
    private UniversalAdapter adapter;
    private String query = "";
    private boolean hideInFeeds;

    public AddChannelsSheet(BaseFragment fragment, Feed feed, Utilities.Callback<List<Long>> onAdd) {
        super(fragment.getParentActivity(), fragment, true, true, false, false, false, ActionBarType.SLIDING, fragment.getResourceProvider());
        this.feed = feed;
        this.onAdd = onAdd;
        hideInFeeds = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).getBoolean(PREF_HIDE, true);
        occupyNavigationBar = true;
        drawNavigationBar = false;
        ignoreTouchActionBar = false;
        showShadow = false;
        AndroidUtilities.enableEdgeToEdge(getWindow());
        Context context = getContext();

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, AndroidUtilities.navigationBarHeight + dp(68));
        recyclerListView.setClipToPadding(false);
        recyclerListView.setOnItemClickListener((view, position) -> {
            UItem item = adapter.getItem(position - 1);
            if (item == null) {
                return;
            }
            if (item.id == ID_HIDE) {
                hideInFeeds = !hideInFeeds;
                ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putBoolean(PREF_HIDE, hideInFeeds).apply();
                adapter.update(true);
            } else if (item.object instanceof TLRPC.Chat) {
                long id = ((TLRPC.Chat) item.object).id;
                if (!selected.remove(id)) {
                    selected.add(id);
                }
                adapter.update(true);
                button.setCount(selected.size(), true);
            }
        });

        searchField = new FragmentSearchField(context, resourcesProvider);
        searchField.editText.setHint(LocaleController.getString(R.string.TgfeedSearchChannels));
        searchField.editText.addTextChangedListener(new TextWatcherImpl() {
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                adapter.update(true);
            }
        });
        searchContainer = new FrameLayout(context);
        searchContainer.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        searchContainer.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 10, 8, 10, 0));
        containerView.addView(searchContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));

        button = new ButtonWithCounterView(context, resourcesProvider);
        button.setText(LocaleController.getString(R.string.Add), false);
        button.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                return;
            }
            onAdd.run(new ArrayList<>(selected));
            dismiss();
        });
        FrameLayout buttonContainer = new FrameLayout(context);
        buttonContainer.setPadding(backgroundPaddingLeft + dp(10), dp(10), backgroundPaddingLeft + dp(10), AndroidUtilities.navigationBarHeight + dp(10));
        buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        containerView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        recyclerListView.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            @Override
            public void onDrawOver(@androidx.annotation.NonNull android.graphics.Canvas c, @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView parent, @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView.State state) {
                updateSearchY();
            }
        });
        updateSearchY();
        adapter.update(false);
    }

    /** Keeps the search field at the top of the list's content, under the sheet's action bar. */
    private void updateSearchY() {
        float top = AndroidUtilities.displaySize.y;
        for (int i = 0; i < recyclerListView.getChildCount(); i++) {
            View child = recyclerListView.getChildAt(i);
            if (recyclerListView.getChildAdapterPosition(child) >= 1 && child.getY() < top) {
                top = child.getY();
            }
        }
        float y = Math.max(AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), top);
        if (searchContainer.getTranslationY() != y) {
            searchContainer.setTranslationY(y);
        }
    }

    @Override
    protected void onContainerLayout(int l, int t, int r, int b) {
        super.onContainerLayout(l, t, r, b);
        updateSearchY();
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.TgfeedAddChannels);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    /** The joined channels that are not in this feed, filtered by the checkbox and the search. */
    private List<TLRPC.Chat> channels() {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        FeedsController feedsController = FeedsController.getInstance(currentAccount);
        String q = query == null ? "" : query.toLowerCase();
        List<TLRPC.Chat> out = new ArrayList<>();
        for (TLRPC.Dialog dialog : messagesController.getAllDialogs()) {
            if (dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = messagesController.getChat(-dialog.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || chat.left || chat.kicked) {
                continue;
            }
            if (feed.hasChannel(chat.id)) {
                continue;
            }
            if (hideInFeeds && feedsController.isChannelInAnyFeed(chat.id)) {
                continue;
            }
            if (!q.isEmpty()) {
                String title = chat.title == null ? "" : chat.title.toLowerCase();
                String username = ChatObject.getPublicUsername(chat);
                if (!title.contains(q) && (username == null || !username.toLowerCase().contains(q))) {
                    continue;
                }
            }
            out.add(chat);
        }
        return out;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (feed == null) {
            return; // the base constructor fills the list before this sheet's fields are set
        }
        items.add(UItem.asSpace(0, dp(56)));
        items.add(UItem.asCheck(ID_HIDE, LocaleController.getString(R.string.TgfeedHideChannelsInFeeds)).setChecked(hideInFeeds));
        List<TLRPC.Chat> channels = channels();
        if (channels.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.TgfeedNoChannelsToAdd)));
            return;
        }
        for (TLRPC.Chat chat : channels) {
            items.add(UItem.asUserCheckbox((int) (chat.id ^ (chat.id >>> 32)), chat).setChecked(selected.contains(chat.id)));
        }
        items.add(UItem.asShadow(null));
    }
}
