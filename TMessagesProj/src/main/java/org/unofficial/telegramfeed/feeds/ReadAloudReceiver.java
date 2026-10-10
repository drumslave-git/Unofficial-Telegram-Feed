package org.unofficial.telegramfeed.feeds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.UserConfig;

/** The "Listen" and "Stop" actions of a rule notification. */
public class ReadAloudReceiver extends BroadcastReceiver {

    public static final String ACTION_LISTEN = "org.unofficial.telegramfeed.READ_ALOUD_LISTEN";
    public static final String ACTION_STOP = "org.unofficial.telegramfeed.READ_ALOUD_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int account = intent.getIntExtra("account", UserConfig.selectedAccount);
        long dialogId = intent.getLongExtra("dialogId", 0);
        if (!UserConfig.isValidAccount(account) || dialogId == 0) {
            return;
        }
        if (ACTION_LISTEN.equals(intent.getAction())) {
            int[] ids = intent.getIntArrayExtra("messageIds");
            String[] texts = intent.getStringArrayExtra("texts");
            if (ids != null && texts != null) {
                ReadAloudController.getInstance().listen(account, dialogId, intent.getStringExtra("title"), ids, texts);
            }
        } else if (ACTION_STOP.equals(intent.getAction())) {
            ReadAloudController.getInstance().stopDialog(account, dialogId);
        }
    }
}
