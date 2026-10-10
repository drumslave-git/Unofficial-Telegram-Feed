package org.unofficial.telegramfeed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.unofficial.telegramfeed.core.Feed;
import org.unofficial.telegramfeed.core.Rule;
import org.unofficial.telegramfeed.feeds.FeedsController;
import org.unofficial.telegramfeed.feeds.RulesController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules list: every rule under its channel, with the feed's name where a feed scopes it.
 * Opened for all rules, for one channel's or for one feed's. The switch of a row turns the rule
 * on or off; a tap opens the editor, a long press offers to delete.
 */
public class RulesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_NEW = 0;
    private static final int ROW_HEADER = 1;
    private static final int ROW_RULE = 2;
    private static final int ROW_SHADOW = 3;
    private static final int ROW_EMPTY = 4;
    private static final int ROW_BATTERY = 5;

    private final long channelId;
    private final long feedId;
    private RecyclerListView listView;
    private Adapter adapter;

    private static final class Row {
        final int type;
        final long channelId;
        final Rule rule;
        final boolean divider;

        Row(int type, long channelId, Rule rule, boolean divider) {
            this.type = type;
            this.channelId = channelId;
            this.rule = rule;
            this.divider = divider;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    /** Every rule of the account. */
    public RulesActivity() {
        this(0, 0);
    }

    public static RulesActivity ofChannel(long channelId) {
        return new RulesActivity(channelId, 0);
    }

    public static RulesActivity ofFeed(long feedId) {
        return new RulesActivity(0, feedId);
    }

    private RulesActivity(long channelId, long feedId) {
        this.channelId = channelId;
        this.feedId = feedId;
    }

    private RulesController controller() {
        return getAccountInstance().getRulesController();
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.tgfeedRulesChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.tgfeedFeedsChanged);
        controller().loadRules();
        FeedsController.getInstance(currentAccount).loadFeeds();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.tgfeedRulesChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.tgfeedFeedsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.tgfeedRulesChanged || id == NotificationCenter.tgfeedFeedsChanged) {
            updateRows();
        }
    }

    private String channelTitle(long id) {
        TLRPC.Chat chat = getMessagesController().getChat(id);
        return chat != null ? chat.title : String.valueOf(id);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.TgfeedRules));
        if (channelId != 0) {
            actionBar.setSubtitle(channelTitle(channelId));
        } else if (feedId != 0) {
            Feed feed = FeedsController.getInstance(currentAccount).getFeed(feedId);
            if (feed != null) {
                actionBar.setSubtitle(feed.name);
            }
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
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
        listView.setOnItemClickListener((RecyclerListView.OnItemClickListenerExtended) (view, position, x, y) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.type == ROW_BATTERY) {
                requestBatteryExemption();
            } else if (row.type == ROW_NEW) {
                presentFragment(new RuleEditActivity(0, channelId, feedId));
            } else if (row.type == ROW_RULE) {
                boolean onSwitch = LocaleController.isRTL && x <= dp(76) || !LocaleController.isRTL && x >= view.getMeasuredWidth() - dp(76);
                if (onSwitch) {
                    Rule copy = new Rule(row.rule);
                    copy.enabled = !copy.enabled;
                    controller().updateRule(copy);
                } else {
                    presentFragment(new RuleEditActivity(row.rule.id, 0, 0));
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || rows.get(position).type != ROW_RULE) {
                return false;
            }
            confirmDelete(this, rows.get(position).rule, null);
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateRows();
        return fragmentView;
    }

    /** Asks before deleting a rule; {@code afterDelete} runs once it is gone, when given. */
    static void confirmDelete(BaseFragment fragment, Rule rule, Runnable afterDelete) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.formatString(R.string.TgfeedDeleteRuleTitle, rule.name));
        builder.setMessage(LocaleController.getString(R.string.TgfeedDeleteRuleText));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            fragment.getAccountInstance().getRulesController().deleteRule(rule.id);
            if (afterDelete != null) {
                afterDelete.run();
            }
        });
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private List<Rule> shownRules() {
        if (channelId != 0) {
            return controller().getRulesOfChannel(channelId);
        }
        if (feedId != 0) {
            return controller().getRulesOfFeed(feedId);
        }
        return controller().getRules();
    }

    /** Whether Android may hold the app back while the phone sleeps. */
    private boolean batteryOptimized() {
        if (Build.VERSION.SDK_INT < 23 || getParentActivity() == null) {
            return false;
        }
        PowerManager power = (PowerManager) getParentActivity().getSystemService(Context.POWER_SERVICE);
        return power != null && !power.isIgnoringBatteryOptimizations(getParentActivity().getPackageName());
    }

    private void requestBatteryExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getParentActivity().getPackageName()));
            getParentActivity().startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRows();
    }

    private void updateRows() {
        rows.clear();
        if (!shownRules().isEmpty() && batteryOptimized()) {
            rows.add(new Row(ROW_BATTERY, 0, null, false));
            rows.add(new Row(ROW_SHADOW, 0, null, false));
        }
        rows.add(new Row(ROW_NEW, 0, null, false));
        LinkedHashMap<Long, List<Rule>> byChannel = new LinkedHashMap<>();
        for (Rule rule : shownRules()) {
            List<Rule> list = byChannel.get(rule.channelId);
            if (list == null) {
                list = new ArrayList<>();
                byChannel.put(rule.channelId, list);
            }
            list.add(rule);
        }
        rows.add(new Row(ROW_SHADOW, 0, null, false));
        if (byChannel.isEmpty()) {
            rows.add(new Row(ROW_EMPTY, 0, null, false));
        }
        for (Map.Entry<Long, List<Rule>> e : byChannel.entrySet()) {
            rows.add(new Row(ROW_HEADER, e.getKey(), null, false));
            List<Rule> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                rows.add(new Row(ROW_RULE, e.getKey(), list.get(i), i < list.size() - 1));
            }
            rows.add(new Row(ROW_SHADOW, 0, null, false));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** The second line of a rule: its condition, priority, feed and schedule. */
    String summary(Rule rule) {
        StringBuilder b = new StringBuilder();
        if (rule.isBroken()) {
            b.append(LocaleController.getString(R.string.TgfeedRuleBroken));
        } else if (rule.matchesEverything()) {
            b.append(LocaleController.getString(R.string.TgfeedEveryPost));
        } else {
            b.append(rule.condition.trim());
        }
        b.append(" · ").append(priorityName(rule.priority));
        if (rule.feedId != 0) {
            Feed feed = FeedsController.getInstance(currentAccount).getFeed(rule.feedId);
            if (feed != null) {
                b.append(" · ").append(feed.name);
            }
        }
        if (rule.schedule != null) {
            b.append(" · ").append(LocaleController.getString(R.string.TgfeedScheduled));
        }
        if (rule.readAloud) {
            b.append(" · ").append(LocaleController.getString(R.string.TgfeedReadAloudShort));
        }
        return b.toString();
    }

    static String priorityName(int priority) {
        switch (priority) {
            case Rule.PRIORITY_SILENT:
                return LocaleController.getString(R.string.TgfeedPrioritySilent);
            case Rule.PRIORITY_URGENT:
                return LocaleController.getString(R.string.TgfeedPriorityUrgent);
            default:
                return LocaleController.getString(R.string.TgfeedPriorityNormal);
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == ROW_NEW || type == ROW_RULE || type == ROW_BATTERY;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case ROW_NEW: {
                    TextCell cell = new TextCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    cell.setTextAndIcon(LocaleController.getString(R.string.TgfeedNewRule), R.drawable.msg_add, false);
                    view = cell;
                    break;
                }
                case ROW_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case ROW_RULE: {
                    NotificationsCheckCell cell = new NotificationsCheckCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case ROW_EMPTY: {
                    TextView text = new TextView(context);
                    text.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                    text.setGravity(Gravity.CENTER);
                    text.setPadding(dp(24), dp(20), dp(24), dp(24));
                    text.setText(LocaleController.getString(R.string.TgfeedRulesEmpty));
                    view = text;
                    break;
                }
                case ROW_BATTERY: {
                    LinearLayout box = new LinearLayout(context);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    box.setPadding(dp(21), dp(14), dp(21), dp(10));
                    TextView text = new TextView(context);
                    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    text.setText(LocaleController.getString(R.string.TgfeedBatteryBanner));
                    box.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                    TextView allow = new TextView(context);
                    allow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    allow.setTypeface(AndroidUtilities.bold());
                    allow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                    allow.setText(LocaleController.getString(R.string.TgfeedAllow));
                    box.addView(allow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT, 0, 8, 0, 0));
                    view = box;
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
            Row row = rows.get(position);
            if (row.type == ROW_HEADER) {
                ((HeaderCell) holder.itemView).setText(channelTitle(row.channelId));
            } else if (row.type == ROW_RULE) {
                ((NotificationsCheckCell) holder.itemView).setTextAndValueAndCheck(row.rule.name, summary(row.rule), row.rule.enabled, 0, true, row.divider);
            }
        }
    }
}
