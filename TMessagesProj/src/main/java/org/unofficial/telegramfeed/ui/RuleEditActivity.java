package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateFormat;
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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.FeedFilter;
import org.unofficial.telegramfeed.core.Rule;
import org.unofficial.telegramfeed.core.RuleBuilder;
import org.unofficial.telegramfeed.core.RuleMatcher;
import org.unofficial.telegramfeed.core.RuleParser;
import org.unofficial.telegramfeed.core.Schedule;
import org.unofficial.telegramfeed.feeds.FeedsController;
import org.unofficial.telegramfeed.feeds.PostFilter;
import org.unofficial.telegramfeed.feeds.RulesController;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * The rule editor: name and switch, channel, the feed that may scope it, the condition as a
 * visual builder or as text, a dry run over the channel's recent posts, the priority with what
 * each level does, read-aloud and a schedule. Leaving with unsaved changes asks first; saving the
 * first rule asks for the notification permission.
 */
public class RuleEditActivity extends BaseFragment {

    private static final int T_NAME = 0;
    private static final int T_ENABLED = 1;
    private static final int T_SHADOW = 2;
    private static final int T_HEADER = 3;
    private static final int T_CHANNEL = 4;
    private static final int T_FEED = 5;
    private static final int T_INFO = 6;
    private static final int T_MODE = 7;
    private static final int T_OR = 8;
    private static final int T_TERM = 9;
    private static final int T_ADD_AND = 10;
    private static final int T_ADD_OR = 11;
    private static final int T_ADD_FIRST = 12;
    private static final int T_TEXT = 13;
    private static final int T_SYNTAX = 14;
    private static final int T_DRYRUN = 15;
    private static final int T_PRIORITY = 16;
    private static final int T_READ_ALOUD = 17;
    private static final int T_SCHEDULE = 18;
    private static final int T_DAYS = 19;
    private static final int T_FROM = 20;
    private static final int T_TO = 21;
    private static final int T_DELETE = 22;

    private static final int MENU_DONE = 1;
    private static final int DRY_RUN_POSTS = 100;

    private static final class Row {
        final int type;
        final int group;
        final int index;
        final CharSequence text;

        Row(int type, int group, int index, CharSequence text) {
            this.type = type;
            this.group = group;
            this.index = index;
            this.text = text;
        }
    }

    private final long ruleId;
    private final long presetChannelId;
    private final long presetFeedId;
    private Rule original;
    private Rule draft;
    private RuleBuilder builder;
    private boolean textMode;
    private CharSequence dryRunResult;
    private boolean dryRunning;
    private RecyclerListView listView;
    private Adapter adapter;
    private final ArrayList<Row> rows = new ArrayList<>();

    public RuleEditActivity(long ruleId, long presetChannelId, long presetFeedId) {
        this.ruleId = ruleId;
        this.presetChannelId = presetChannelId;
        this.presetFeedId = presetFeedId;
    }

    private RulesController controller() {
        return getAccountInstance().getRulesController();
    }

    @Override
    public boolean onFragmentCreate() {
        if (ruleId != 0) {
            Rule rule = controller().getRule(ruleId);
            if (rule == null) {
                return false;
            }
            original = new Rule(rule);
            draft = new Rule(rule);
        } else {
            draft = new Rule();
            draft.channelId = presetChannelId;
            draft.feedId = presetFeedId;
            if (draft.channelId == 0 && presetFeedId != 0) {
                Feed feed = FeedsController.getInstance(currentAccount).getFeed(presetFeedId);
                if (feed != null && feed.channelIds.size() == 1) {
                    draft.channelId = feed.channelIds.get(0);
                }
            }
            original = new Rule(draft);
        }
        builder = RuleBuilder.fromText(draft.condition);
        textMode = builder == null;
        if (builder == null) {
            builder = new RuleBuilder();
        }
        return super.onFragmentCreate();
    }

    private String channelTitle(long id) {
        if (id == 0) {
            return LocaleController.getString(R.string.TgfeedPickChannel);
        }
        TLRPC.Chat chat = getMessagesController().getChat(id);
        return chat != null ? chat.title : String.valueOf(id);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(ruleId == 0 ? R.string.TgfeedNewRule : R.string.TgfeedEditRule));
        actionBar.createMenu().addItem(MENU_DONE, R.drawable.ic_ab_done).setContentDescription(LocaleController.getString(R.string.Save));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (checkDiscard()) {
                        finishFragment();
                    }
                } else if (id == MENU_DONE) {
                    save();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setItemAnimator(null);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                onRowClick(rows.get(position), view);
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateRows();
        return fragmentView;
    }

    // ---------------------------------------------------------------- rows

    private void updateRows() {
        rows.clear();
        rows.add(new Row(T_NAME, 0, 0, null));
        rows.add(new Row(T_ENABLED, 0, 0, null));
        rows.add(new Row(T_SHADOW, 0, 0, null));

        rows.add(new Row(T_HEADER, 0, 0, LocaleController.getString(R.string.TgfeedRuleWatches)));
        rows.add(new Row(T_CHANNEL, 0, 0, null));
        rows.add(new Row(T_FEED, 0, 0, null));
        String watchesInfo = LocaleController.getString(R.string.TgfeedRuleFeedInfo);
        if (draft.channelId != 0 && getMessagesController().isDialogMuted(-draft.channelId, 0)) {
            watchesInfo = LocaleController.getString(R.string.TgfeedChannelMutedNote) + "\n\n" + watchesInfo;
        }
        rows.add(new Row(T_INFO, 0, 0, watchesInfo));

        rows.add(new Row(T_HEADER, 0, 0, LocaleController.getString(R.string.TgfeedRuleCondition)));
        rows.add(new Row(T_MODE, 0, 0, null));
        if (textMode) {
            rows.add(new Row(T_TEXT, 0, 0, null));
            rows.add(new Row(T_SYNTAX, 0, 0, null));
        } else if (builder.isEmpty()) {
            rows.add(new Row(T_ADD_FIRST, 0, 0, null));
        } else {
            for (int g = 0; g < builder.groups.size(); g++) {
                if (g > 0) {
                    rows.add(new Row(T_OR, g, 0, null));
                }
                List<RuleBuilder.BuilderTerm> group = builder.groups.get(g);
                for (int i = 0; i < group.size(); i++) {
                    rows.add(new Row(T_TERM, g, i, null));
                }
                rows.add(new Row(T_ADD_AND, g, 0, null));
            }
            rows.add(new Row(T_ADD_OR, 0, 0, null));
        }
        rows.add(new Row(T_DRYRUN, 0, 0, null));
        String conditionInfo = currentCondition().isEmpty() ? LocaleController.getString(R.string.TgfeedRuleNoCondition) : LocaleController.getString(R.string.TgfeedRuleConditionInfo);
        rows.add(new Row(T_INFO, 0, 0, dryRunResult != null ? dryRunResult + "\n\n" + conditionInfo : conditionInfo));

        rows.add(new Row(T_HEADER, 0, 0, LocaleController.getString(R.string.TgfeedRuleNotification)));
        rows.add(new Row(T_PRIORITY, Rule.PRIORITY_SILENT, 0, null));
        rows.add(new Row(T_PRIORITY, Rule.PRIORITY_NORMAL, 0, null));
        rows.add(new Row(T_PRIORITY, Rule.PRIORITY_URGENT, 0, null));
        rows.add(new Row(T_INFO, 0, 0, priorityInfo(draft.priority)));

        rows.add(new Row(T_READ_ALOUD, 0, 0, null));
        rows.add(new Row(T_SCHEDULE, 0, 0, null));
        if (draft.schedule != null) {
            rows.add(new Row(T_DAYS, 0, 0, null));
            rows.add(new Row(T_FROM, 0, 0, null));
            rows.add(new Row(T_TO, 0, 0, null));
        }
        rows.add(new Row(T_INFO, 0, 0, LocaleController.getString(R.string.TgfeedScheduleInfo)));

        if (ruleId != 0) {
            rows.add(new Row(T_DELETE, 0, 0, null));
            rows.add(new Row(T_SHADOW, 0, 0, null));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static String priorityInfo(int priority) {
        switch (priority) {
            case Rule.PRIORITY_SILENT:
                return LocaleController.getString(R.string.TgfeedPrioritySilentInfo);
            case Rule.PRIORITY_URGENT:
                return LocaleController.getString(R.string.TgfeedPriorityUrgentInfo);
            default:
                return LocaleController.getString(R.string.TgfeedPriorityNormalInfo);
        }
    }

    /** The condition as it stands, in the rule syntax. */
    private String currentCondition() {
        return textMode ? (draft.condition == null ? "" : draft.condition.trim()) : builder.toText();
    }

    private void syncCondition() {
        if (!textMode) {
            draft.condition = builder.toText();
        }
    }

    // ---------------------------------------------------------------- taps

    private void onRowClick(Row row, View view) {
        switch (row.type) {
            case T_NAME:
                TgfeedAlerts.promptText(this, LocaleController.getString(R.string.TgfeedRuleName), LocaleController.getString(R.string.TgfeedRuleName), draft.name, name -> {
                    draft.name = name;
                    updateRows();
                });
                break;
            case T_ENABLED:
                draft.enabled = !draft.enabled;
                ((TextCheckCell) view).setChecked(draft.enabled);
                break;
            case T_CHANNEL:
                pickChannel();
                break;
            case T_FEED:
                pickFeed();
                break;
            case T_MODE:
                toggleMode();
                break;
            case T_TEXT:
                TgfeedAlerts.promptCondition(this, LocaleController.getString(R.string.TgfeedRuleCondition), draft.condition, text -> {
                    draft.condition = text;
                    dryRunResult = null;
                    updateRows();
                });
                break;
            case T_SYNTAX:
                TgfeedAlerts.showSyntax(this);
                break;
            case T_ADD_FIRST:
                editTerm(-1, -1);
                break;
            case T_ADD_AND:
                editTerm(row.group, -1);
                break;
            case T_ADD_OR:
                editTerm(builder.groups.size(), -1);
                break;
            case T_TERM:
                editTerm(row.group, row.index);
                break;
            case T_DRYRUN:
                dryRun();
                break;
            case T_PRIORITY:
                if (draft.priority != row.group) {
                    draft.priority = row.group;
                    updateRows();
                    if (row.group == Rule.PRIORITY_URGENT) {
                        TgfeedAlerts.offerDoNotDisturb(this);
                    }
                }
                break;
            case T_READ_ALOUD:
                draft.readAloud = !draft.readAloud;
                ((TextCheckCell) view).setChecked(draft.readAloud);
                break;
            case T_SCHEDULE:
                if (draft.schedule == null) {
                    draft.schedule = new Schedule(Schedule.ALL_WEEK, 9 * 60, 18 * 60);
                } else {
                    draft.schedule = null;
                }
                updateRows();
                break;
            case T_DAYS:
                pickDays();
                break;
            case T_FROM:
                pickTime(true);
                break;
            case T_TO:
                pickTime(false);
                break;
            case T_DELETE:
                if (original != null) {
                    RulesActivity.confirmDelete(this, original, this::finishFragment);
                }
                break;
            default:
                break;
        }
    }

    /** The channels the account follows, by name. */
    private List<TLRPC.Chat> followedChannels() {
        List<TLRPC.Chat> out = new ArrayList<>();
        for (TLRPC.Dialog dialog : getMessagesController().getAllDialogs()) {
            if (dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
            if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !chat.left && !chat.kicked) {
                out.add(chat);
            }
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    private void pickChannel() {
        List<TLRPC.Chat> channels = followedChannels();
        if (channels.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.TgfeedNoChannelsToAdd)).show();
            return;
        }
        CharSequence[] labels = new CharSequence[channels.size()];
        int selected = -1;
        for (int i = 0; i < channels.size(); i++) {
            labels[i] = channels.get(i).title;
            if (channels.get(i).id == draft.channelId) {
                selected = i;
            }
        }
        TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedRuleChannel), labels, selected, index -> {
            draft.channelId = channels.get(index).id;
            Feed feed = FeedsController.getInstance(currentAccount).getFeed(draft.feedId);
            if (feed != null && !feed.channelIds.contains(draft.channelId)) {
                draft.feedId = 0;
            }
            dryRunResult = null;
            updateRows();
        });
    }

    private void pickFeed() {
        if (draft.channelId == 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.TgfeedPickChannelFirst)).show();
            return;
        }
        List<Feed> feeds = FeedsController.getInstance(currentAccount).getFeedsOfChannel(draft.channelId);
        CharSequence[] labels = new CharSequence[feeds.size() + 1];
        labels[0] = LocaleController.getString(R.string.TgfeedNoFeed);
        int selected = 0;
        for (int i = 0; i < feeds.size(); i++) {
            labels[i + 1] = feeds.get(i).name;
            if (feeds.get(i).id == draft.feedId) {
                selected = i + 1;
            }
        }
        TgfeedAlerts.choose(this, LocaleController.getString(R.string.TgfeedRuleFeed), labels, selected, index -> {
            draft.feedId = index == 0 ? 0 : feeds.get(index - 1).id;
            dryRunResult = null;
            updateRows();
        });
    }

    private void toggleMode() {
        if (textMode) {
            RuleBuilder parsed = RuleBuilder.fromText(draft.condition);
            if (parsed == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.TgfeedConditionTooNested)).show();
                return;
            }
            builder = parsed;
            textMode = false;
        } else {
            draft.condition = builder.toText();
            textMode = true;
        }
        updateRows();
    }

    /** Edits or adds a term: {@code index} -1 adds to group {@code group}, a group past the end adds a group. */
    private void editTerm(int group, int index) {
        RuleBuilder.BuilderTerm initial = group >= 0 && index >= 0 ? builder.groups.get(group).get(index) : new RuleBuilder.BuilderTerm();
        TgfeedAlerts.editTerm(this, initial, index >= 0, term -> {
            if (term == null) {
                List<RuleBuilder.BuilderTerm> g = builder.groups.get(group);
                g.remove(index);
                if (g.isEmpty()) {
                    builder.groups.remove(group);
                }
            } else if (index >= 0) {
                builder.groups.get(group).set(index, term);
            } else if (group < 0 || group >= builder.groups.size()) {
                List<RuleBuilder.BuilderTerm> g = new ArrayList<>();
                g.add(term);
                builder.groups.add(g);
            } else {
                builder.groups.get(group).add(term);
            }
            syncCondition();
            dryRunResult = null;
            updateRows();
        });
    }

    private void pickDays() {
        String[] names = weekdayNames();
        CharSequence[] labels = new CharSequence[7];
        boolean[] checked = new boolean[7];
        for (int d = 1; d <= 7; d++) {
            labels[d - 1] = names[d - 1];
            checked[d - 1] = draft.schedule.weekdays.contains(d);
        }
        TgfeedAlerts.chooseMany(this, LocaleController.getString(R.string.TgfeedScheduleDays), labels, checked, result -> {
            Set<Integer> days = new HashSet<>();
            for (int d = 1; d <= 7; d++) {
                if (result[d - 1]) days.add(d);
            }
            draft.schedule = new Schedule(days, draft.schedule.from, draft.schedule.to);
            updateRows();
        });
    }

    /** The names of the ISO weekdays, Monday first, in the interface language. */
    private static String[] weekdayNames() {
        String[] calendarNames = new DateFormatSymbols(LocaleController.getInstance().getCurrentLocale()).getWeekdays();
        String[] out = new String[7];
        for (int d = 1; d <= 7; d++) {
            int calendarDay = d == 7 ? Calendar.SUNDAY : d + 1;
            out[d - 1] = calendarNames[calendarDay];
        }
        return out;
    }

    private String daysLabel() {
        if (draft.schedule.weekdays.size() == 7) {
            return LocaleController.getString(R.string.TgfeedEveryDay);
        }
        if (draft.schedule.weekdays.isEmpty()) {
            return LocaleController.getString(R.string.TgfeedNoDays);
        }
        String[] shortNames = new DateFormatSymbols(LocaleController.getInstance().getCurrentLocale()).getShortWeekdays();
        StringBuilder b = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            if (draft.schedule.weekdays.contains(d)) {
                if (b.length() > 0) b.append(", ");
                b.append(shortNames[d == 7 ? Calendar.SUNDAY : d + 1]);
            }
        }
        return b.toString();
    }

    private void pickTime(boolean from) {
        int minutes = from ? draft.schedule.from : draft.schedule.to;
        TimePickerDialog dialog = new TimePickerDialog(getParentActivity(), (picker, hour, minute) -> {
            int value = hour * 60 + minute;
            draft.schedule = from ? new Schedule(draft.schedule.weekdays, value, draft.schedule.to) : new Schedule(draft.schedule.weekdays, draft.schedule.from, value);
            updateRows();
        }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(getParentActivity()));
        showDialog(dialog);
    }

    // ---------------------------------------------------------------- dry run

    /** Matches the draft against the channel's latest posts, as if it were on and without its schedule. */
    private void dryRun() {
        if (dryRunning) {
            return;
        }
        if (draft.channelId == 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.TgfeedPickChannelFirst)).show();
            return;
        }
        syncCondition();
        if (!currentCondition().isEmpty()) {
            try {
                RuleParser.parse(currentCondition());
            } catch (RuleParser.SyntaxException e) {
                dryRunResult = TgfeedAlerts.describe(e);
                updateRows();
                return;
            }
        }
        TLRPC.InputPeer peer = getMessagesController().getInputPeer(-draft.channelId);
        if (peer == null) {
            return;
        }
        dryRunning = true;
        dryRunResult = LocaleController.getString(R.string.TgfeedDryRunTesting);
        updateRows();
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = DRY_RUN_POSTS;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            dryRunning = false;
            if (!(response instanceof TLRPC.messages_Messages)) {
                dryRunResult = error != null ? error.text : "";
                updateRows();
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);
            Rule probe = new Rule(draft);
            probe.condition = currentCondition();
            probe.enabled = true;
            probe.schedule = null;
            probe.createdAt = 0;
            List<Rule> rules = Collections.singletonList(probe);
            RuleMatcher matcher = new RuleMatcher();
            Calendar now = Calendar.getInstance();
            LinkedHashMap<Long, List<MessageObject>> albums = new LinkedHashMap<>();
            int checked = 0;
            int matched = 0;
            for (TLRPC.Message message : res.messages) {
                if (message instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                MessageObject object = new MessageObject(currentAccount, message, false, false);
                if (object.hasValidGroupId()) {
                    List<MessageObject> parts = albums.get(object.getGroupId());
                    if (parts == null) {
                        parts = new ArrayList<>();
                        albums.put(object.getGroupId(), parts);
                    }
                    parts.add(object);
                    continue;
                }
                if (PostFilter.isServiceNote(object)) {
                    continue;
                }
                checked++;
                if (matcher.evaluate(rules, controller().feedScopes(), draft.channelId, PostFilter.describe(object), message.date, now) != null) {
                    matched++;
                }
            }
            for (List<MessageObject> parts : albums.values()) {
                List<FeedFilter.Post> posts = new ArrayList<>();
                List<Integer> ids = new ArrayList<>();
                for (MessageObject part : parts) {
                    posts.add(PostFilter.describe(part));
                    ids.add(part.getId());
                }
                checked++;
                if (matcher.evaluateAlbum(rules, controller().feedScopes(), draft.channelId, posts, ids, parts.get(0).messageOwner.date, now) != null) {
                    matched++;
                }
            }
            dryRunResult = matched == 0
                    ? LocaleController.formatPluralString("TgfeedDryRunNoMatch", checked)
                    : LocaleController.formatString(R.string.TgfeedDryRunMatches, matched, checked);
            updateRows();
        }));
    }

    // ---------------------------------------------------------------- save and leave

    private boolean isDirty() {
        syncCondition();
        Rule current = new Rule(draft);
        current.condition = currentCondition();
        Rule start = new Rule(original);
        start.condition = start.condition == null ? "" : start.condition.trim();
        return !current.equals(start);
    }

    /** True when the screen may close; otherwise asks whether to drop the changes. */
    private boolean checkDiscard() {
        if (!isDirty()) {
            return true;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.TgfeedDiscardTitle));
        b.setMessage(LocaleController.getString(R.string.TgfeedDiscardText));
        b.setNegativeButton(LocaleController.getString(R.string.TgfeedKeepEditing), null);
        b.setPositiveButton(LocaleController.getString(R.string.TgfeedDiscard), (d, w) -> finishFragment());
        AlertDialog dialog = b.create();
        showDialog(dialog);
        View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
        return false;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isDirty()) {
            if (invoked) {
                checkDiscard();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return !isDirty();
    }

    private void fail(int textRes) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(textRes)).show();
    }

    private void save() {
        syncCondition();
        draft.condition = currentCondition();
        if (draft.name == null || draft.name.trim().isEmpty()) {
            fail(R.string.TgfeedRuleNoName);
            return;
        }
        if (draft.channelId == 0) {
            fail(R.string.TgfeedPickChannelFirst);
            return;
        }
        if (!draft.condition.isEmpty()) {
            try {
                RuleParser.parse(draft.condition);
            } catch (RuleParser.SyntaxException e) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, TgfeedAlerts.describe(e)).show();
                return;
            }
        }
        if (draft.schedule != null && draft.schedule.weekdays.isEmpty()) {
            fail(R.string.TgfeedScheduleNoDays);
            return;
        }
        draft.name = draft.name.trim();
        boolean first = controller().getRules().isEmpty();
        if (ruleId == 0) {
            controller().createRule(draft);
        } else {
            controller().updateRule(new Rule(draft));
        }
        original = new Rule(draft);
        Runnable afterPermission = () -> {
            if (draft.enabled && getMessagesController().isDialogMuted(-draft.channelId, 0)) {
                offerUnmute();
            } else {
                finishFragment();
            }
        };
        if (first && needsNotificationPermission()) {
            askNotificationPermission(afterPermission);
        } else {
            afterPermission.run();
        }
    }

    private boolean needsNotificationPermission() {
        Activity activity = getParentActivity();
        return Build.VERSION.SDK_INT >= 33 && activity != null && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    /** Telegram pushes only unmuted channels: offers to unmute the rule's channel. */
    private void offerUnmute() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.formatString(R.string.TgfeedUnmuteTitle, channelTitle(draft.channelId)));
        b.setMessage(LocaleController.getString(R.string.TgfeedUnmuteText));
        b.setNegativeButton(LocaleController.getString(R.string.TgfeedKeepMuted), (d, w) -> finishFragment());
        b.setPositiveButton(LocaleController.getString(R.string.TgfeedUnmute), (d, w) -> {
            getNotificationsController().muteDialog(-draft.channelId, 0, false);
            finishFragment();
        });
        AlertDialog dialog = b.create();
        dialog.setCanceledOnTouchOutside(false);
        showDialog(dialog);
    }

    private void askNotificationPermission(Runnable then) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.TgfeedNotifyAskTitle));
        b.setMessage(LocaleController.getString(R.string.TgfeedNotifyAskText));
        b.setNegativeButton(LocaleController.getString(R.string.TgfeedNotNow), (d, w) -> then.run());
        b.setPositiveButton(LocaleController.getString(R.string.TgfeedAllow), (d, w) -> {
            Activity activity = getParentActivity();
            if (activity != null && Build.VERSION.SDK_INT >= 33) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            then.run();
        });
        AlertDialog dialog = b.create();
        dialog.setCanceledOnTouchOutside(false);
        showDialog(dialog);
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            int type = rows.get(position).type;
            switch (type) {
                case T_NAME:
                case T_CHANNEL:
                case T_FEED:
                case T_MODE:
                case T_TERM:
                case T_ADD_AND:
                case T_ADD_OR:
                case T_ADD_FIRST:
                case T_TEXT:
                case T_SYNTAX:
                case T_DRYRUN:
                case T_DAYS:
                case T_FROM:
                case T_TO:
                case T_DELETE:
                    return T_NAME;
                case T_ENABLED:
                case T_READ_ALOUD:
                case T_SCHEDULE:
                    return T_ENABLED;
                default:
                    return type;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            int type = rows.get(position).type;
            return type != T_SHADOW && type != T_HEADER && type != T_INFO && type != T_OR;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case T_NAME: {
                    TextCell cell = new TextCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case T_ENABLED: {
                    TextCheckCell cell = new TextCheckCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case T_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case T_PRIORITY: {
                    RadioCell cell = new RadioCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case T_INFO: {
                    view = new TextInfoPrivacyCell(context);
                    break;
                }
                case T_OR: {
                    TextView text = new TextView(context);
                    text.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    text.setTypeface(AndroidUtilities.bold());
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                    text.setGravity(Gravity.CENTER);
                    text.setPadding(0, dp(6), 0, dp(6));
                    text.setText(LocaleController.getString(R.string.TgfeedOr));
                    view = text;
                    break;
                }
                case T_SHADOW:
                default:
                    view = new ShadowSectionCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            View view = holder.itemView;
            if (view instanceof TextCell) {
                bindText((TextCell) view, row, position);
            } else if (view instanceof TextCheckCell) {
                TextCheckCell cell = (TextCheckCell) view;
                if (row.type == T_ENABLED) {
                    cell.setTextAndValueAndCheck(LocaleController.getString(R.string.TgfeedRuleEnabled), LocaleController.getString(R.string.TgfeedRuleEnabledInfo), draft.enabled, true, false);
                } else if (row.type == T_READ_ALOUD) {
                    cell.setTextAndCheck(LocaleController.getString(R.string.TgfeedReadAloud), draft.readAloud, true);
                } else {
                    cell.setTextAndCheck(LocaleController.getString(R.string.TgfeedSchedule), draft.schedule != null, draft.schedule != null);
                }
            } else if (view instanceof HeaderCell) {
                ((HeaderCell) view).setText(row.text);
            } else if (view instanceof RadioCell) {
                ((RadioCell) view).setText(RulesActivity.priorityName(row.group), draft.priority == row.group, row.group != Rule.PRIORITY_URGENT);
            } else if (view instanceof TextInfoPrivacyCell) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) view;
                cell.setText(row.text);
                boolean last = position + 1 < rows.size() && rows.get(position + 1).type == T_HEADER || position == rows.size() - 1;
                cell.setBackground(Theme.getThemedDrawableByKey(view.getContext(), last || position + 1 >= rows.size() ? R.drawable.greydivider_bottom : R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
            }
        }

        private void bindText(TextCell cell, Row row, int position) {
            cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
            boolean divider = position + 1 < rows.size() && rows.get(position + 1).type != T_INFO && rows.get(position + 1).type != T_SHADOW && rows.get(position + 1).type != T_HEADER;
            switch (row.type) {
                case T_NAME:
                    cell.setTextAndValue(LocaleController.getString(R.string.TgfeedRuleName), draft.name == null || draft.name.isEmpty() ? LocaleController.getString(R.string.TgfeedRuleNameNone) : draft.name, divider);
                    break;
                case T_CHANNEL:
                    cell.setTextAndValue(LocaleController.getString(R.string.TgfeedRuleChannel), channelTitle(draft.channelId), divider);
                    break;
                case T_FEED: {
                    Feed feed = FeedsController.getInstance(currentAccount).getFeed(draft.feedId);
                    cell.setTextAndValue(LocaleController.getString(R.string.TgfeedRuleFeed), feed != null ? feed.name : LocaleController.getString(R.string.TgfeedNoFeed), divider);
                    break;
                }
                case T_MODE:
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(textMode ? R.string.TgfeedUseBuilder : R.string.TgfeedUseText), textMode ? R.drawable.msg_list : R.drawable.msg_edit, divider);
                    break;
                case T_TEXT: {
                    String condition = currentCondition();
                    cell.setTextAndValue(condition.isEmpty() ? LocaleController.getString(R.string.TgfeedEveryPost) : condition, LocaleController.getString(R.string.Edit), divider);
                    break;
                }
                case T_SYNTAX:
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedSyntaxTitle), R.drawable.msg_help, divider);
                    break;
                case T_TERM: {
                    RuleBuilder.BuilderTerm term = builder.groups.get(row.group).get(row.index);
                    StringBuilder flags = new StringBuilder();
                    if (!term.wholeWord) flags.append(LocaleController.getString(R.string.TgfeedTermPartShort));
                    if (term.caseSensitive) {
                        if (flags.length() > 0) flags.append(", ");
                        flags.append(LocaleController.getString(R.string.TgfeedTermCaseShort));
                    }
                    String prefix = row.index > 0 ? LocaleController.getString(R.string.TgfeedAnd) + " " : "";
                    String text = prefix + (term.negated ? LocaleController.getString(R.string.TgfeedNotPrefix) + " " : "") + "“" + term.text + "”";
                    cell.setTextAndValue(text, flags.toString(), divider);
                    break;
                }
                case T_ADD_AND:
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedAndAnotherWord), R.drawable.msg_add, divider);
                    break;
                case T_ADD_OR:
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedOrAlternative), R.drawable.msg_add, divider);
                    break;
                case T_ADD_FIRST:
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedAddTerm), R.drawable.msg_add, divider);
                    break;
                case T_DRYRUN:
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedDryRun), R.drawable.msg_search, divider);
                    break;
                case T_DAYS:
                    cell.setTextAndValue(LocaleController.getString(R.string.TgfeedScheduleDays), daysLabel(), divider);
                    break;
                case T_FROM:
                    cell.setTextAndValue(LocaleController.getString(R.string.TgfeedScheduleFrom), Schedule.formatTime(draft.schedule.from), divider);
                    break;
                case T_TO: {
                    String value = Schedule.formatTime(draft.schedule.to);
                    if (draft.schedule.wrapsMidnight()) {
                        value += " " + LocaleController.getString(R.string.TgfeedNextDay);
                    }
                    cell.setTextAndValue(LocaleController.getString(R.string.TgfeedScheduleTo), value, divider);
                    break;
                }
                case T_DELETE:
                    cell.setColors(Theme.key_text_RedBold, Theme.key_text_RedBold);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedDeleteRule), R.drawable.msg_delete, false);
                    break;
                default:
                    break;
            }
        }
    }
}
