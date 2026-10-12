package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
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
import org.telegram.ui.ActionBar.Theme;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The joined channels with a search, in two modes: adding channels to a feed (multi-select, one
 * "Add" press, and a remembered checkbox that hides channels already in a feed) and picking the
 * one channel of a rule (a tap picks it).
 */
public class ChannelsSheet extends BottomSheetWithRecyclerListView {

    private static final int ID_HIDE = 1;
    private static final String PREF_HIDE = "tgfeedHideChannelsInFeeds";

    /** The feed channels are added to; null when picking one channel. */
    private final Feed feed;
    private final Utilities.Callback<List<Long>> onAdd;
    private final Utilities.Callback<Long> onPick;
    private final long pickedId;
    private final boolean ready;
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
    private final FrameLayout searchContainer;
    private final FragmentSearchField searchField;
    private ButtonWithCounterView button;
    private UniversalAdapter adapter;
    private String query = "";
    private boolean hideInFeeds;

    /** Adds the channels picked to the feed. */
    public static ChannelsSheet addToFeed(BaseFragment fragment, Feed feed, Utilities.Callback<List<Long>> onAdd) {
        return new ChannelsSheet(fragment, feed, onAdd, null, 0);
    }

    /** Picks one channel; {@code pickedId} is the channel picked so far, 0 for none. */
    public static ChannelsSheet pickOne(BaseFragment fragment, long pickedId, Utilities.Callback<Long> onPick) {
        return new ChannelsSheet(fragment, null, null, onPick, pickedId);
    }

    private ChannelsSheet(BaseFragment fragment, Feed feed, Utilities.Callback<List<Long>> onAdd, Utilities.Callback<Long> onPick, long pickedId) {
        // Not edge to edge: the sheet then sees the keyboard and stands above it while the search is typed into.
        super(fragment.getParentActivity(), fragment, true, false, false, false, ActionBarType.SLIDING, fragment.getResourceProvider());
        this.feed = feed;
        updateTitle(); // the base constructor asked for the title before the mode was known
        this.onAdd = onAdd;
        this.onPick = onPick;
        this.pickedId = pickedId;
        hideInFeeds = feed != null && ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).getBoolean(PREF_HIDE, true);
        smoothKeyboardAnimationEnabled = true;
        ignoreTouchActionBar = false;
        showShadow = false;
        Context context = getContext();

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, dp(feed != null ? 68 : 8));
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
                if (feed == null) {
                    AndroidUtilities.hideKeyboard(recyclerListView);
                    onPick.run(id);
                    dismiss();
                    return;
                }
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
        // Pinned under the action bar, it hides the rows that scroll beneath it.
        View searchBackground = new View(context);
        searchBackground.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
        searchContainer.addView(searchBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        searchContainer.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 10, 8, 10, 0));
        containerView.addView(searchContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));

        if (feed != null) {
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
            buttonContainer.setPadding(backgroundPaddingLeft + dp(10), dp(10), backgroundPaddingLeft + dp(10), dp(10));
            buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
            containerView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        }
        recyclerListView.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            @Override
            public void onDrawOver(@androidx.annotation.NonNull android.graphics.Canvas c, @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView parent, @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView.State state) {
                updateSearchY();
            }
        });
        updateSearchY();
        ready = true;
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
        return LocaleController.getString(feed != null ? R.string.TgfeedAddChannels : R.string.TgfeedRuleChannel);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    /** The joined channels, by name when picking one; for a feed, those not in it, filtered by the checkbox. Then the search. */
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
            if (feed != null && feed.hasChannel(chat.id)) {
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
        if (feed == null) {
            Collections.sort(out, (a, b) -> (a.title == null ? "" : a.title).compareToIgnoreCase(b.title == null ? "" : b.title));
        }
        return out;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!ready) {
            return; // the base constructor fills the list before this sheet's fields are set
        }
        items.add(UItem.asSpace(0, dp(56)));
        if (feed != null) {
            items.add(UItem.asCheck(ID_HIDE, LocaleController.getString(R.string.TgfeedHideChannelsInFeeds)).setChecked(hideInFeeds));
        }
        List<TLRPC.Chat> channels = channels();
        if (channels.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.TgfeedNoChannelsToAdd)));
            return;
        }
        for (TLRPC.Chat chat : channels) {
            int id = (int) (chat.id ^ (chat.id >>> 32));
            if (feed != null) {
                items.add(UItem.asUserCheckbox(id, chat).setChecked(selected.contains(chat.id)));
            } else {
                UItem item = UItem.asRadioUser(chat).setChecked(chat.id == pickedId);
                item.id = id;
                items.add(item);
            }
        }
        items.add(UItem.asShadow(null));
    }
}
