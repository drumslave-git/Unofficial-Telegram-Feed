package org.unofficial.telegramfeed.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageKeyData;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Debug builds only (declared in the debug manifest, sendable by the shell alone): makes the push
 * Telegram sends for a channel post, encrypts it with the account's push key as Telegram's server
 * does, and hands it to {@link PushListenerController#processRemoteMessage} as Firebase would, so
 * a push is tested end to end without the server.
 *
 * <pre>adb shell am broadcast -n &lt;package&gt;/org.unofficial.telegramfeed.debug.DebugPushReceiver
 *     --es channel_id 1352726486 --es msg_id 135830 [--es text "..."] [--es title "..."]</pre>
 */
public class DebugPushReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String channelId = intent.getStringExtra("channel_id");
        String messageId = intent.getStringExtra("msg_id");
        if (channelId == null || messageId == null) {
            FileLog.d("tgfeed debug push: channel_id and msg_id are required");
            return;
        }
        String text = intent.getStringExtra("text");
        String title = intent.getStringExtra("title");
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SharedConfig.loadConfig();
                if (SharedConfig.pushAuthKey == null) {
                    FileLog.d("tgfeed debug push: no push key, the app has not registered for pushes");
                    return;
                }
                JSONObject custom = new JSONObject();
                custom.put("channel_id", channelId);
                custom.put("msg_id", messageId);
                JSONArray args = new JSONArray();
                args.put(title != null ? title : "Channel");
                if (text != null) {
                    args.put(text);
                }
                JSONObject json = new JSONObject();
                json.put("loc_key", text != null ? "CHANNEL_MESSAGE_TEXT" : "CHANNEL_MESSAGE_NOTEXT");
                json.put("loc_args", args);
                json.put("custom", custom);
                // no user_id: Telegram's code then takes the selected account, once the app has loaded it
                String payload = encrypt(SharedConfig.pushAuthKey, json.toString().getBytes(StandardCharsets.UTF_8));
                FileLog.d("tgfeed debug push: channel " + channelId + " message " + messageId);
                PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, payload, System.currentTimeMillis());
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                pending.finish();
            }
        }, "tgfeed-debug-push").start();
    }

    /** The encrypted push: the key id, the message key and the AES-IGE body, as the server sends it. */
    private static String encrypt(byte[] authKey, byte[] json) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        plain.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.length).array());
        plain.write(json);
        int padding = 16 - (plain.size() % 16);
        if (padding < 12) {
            padding += 16;
        }
        byte[] random = new byte[padding];
        new SecureRandom().nextBytes(random);
        plain.write(random);
        byte[] body = plain.toByteArray();

        byte[] full = Utilities.computeSHA256(slice(authKey, 88 + 8, 32), body);
        byte[] messageKey = slice(full, 8, 16);
        MessageKeyData keys = MessageKeyData.generateMessageKeyData(authKey, messageKey, true, 2);
        Utilities.aesIgeEncryptionByteArray(body, keys.aesKey, keys.aesIv, true, false, 0, body.length);

        byte[] keyHash = Utilities.computeSHA1(authKey);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(slice(keyHash, keyHash.length - 8, 8));
        out.write(messageKey);
        out.write(body);
        return Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, offset, out, 0, length);
        return out;
    }
}
