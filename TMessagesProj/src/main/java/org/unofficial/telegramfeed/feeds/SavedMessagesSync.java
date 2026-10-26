package org.unofficial.telegramfeed.feeds;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.unofficial.telegramfeed.core.FeedsConfig;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps an account's feeds and rules in sync with one JSON file in its Saved Messages
 * ({@link ConfigFile}). Every local change is written to the file a few seconds later, by
 * replacing the file of the same message; a newer file, sent by another device, replaces the
 * local feeds and rules when the chat list opens and as soon as its edit arrives. The newer of
 * the two wins whole. A device that never synced and has feeds or rules of its own asks whether
 * to use the copy, keep its own or merge both. Runs on the UI thread.
 */
public class SavedMessagesSync extends BaseController implements NotificationCenter.NotificationCenterDelegate {

    private static final SavedMessagesSync[] Instance = new SavedMessagesSync[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static SavedMessagesSync getInstance(int num) {
        SavedMessagesSync localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new SavedMessagesSync(num);
                }
            }
        }
        return localInstance;
    }

    private static final long UPLOAD_DELAY_MS = 3000;
    private static final int PART_SIZE = 64 * 1024;
    private static final int DOWNLOAD_LIMIT = 512 * 1024;

    private static final int CHOICE_COPY = 0;
    private static final int CHOICE_KEEP = 1;
    private static final int CHOICE_MERGE = 2;

    private final SharedPreferences prefs;
    /** The copy's message in Saved Messages, 0 when not known. */
    private int messageId;
    /** The copy's file this device matches, 0 before its first sync. */
    private long documentId;
    /** Local changes the copy does not have yet. */
    private boolean dirty;
    /** Server time of the last local change. */
    private int changedAt;
    /** Server time this device last matched the copy, for the settings row. */
    private int syncedAt;

    private boolean started;
    private boolean checkWhenLoaded;
    private boolean busy;
    private boolean checkAgain;
    private boolean failed;
    /** Counts local changes, so that a change made while the copy was read or written is not lost. */
    private int changes;
    private int changesAtCheck;
    /** Bumped by {@link #cleanup}, so that answers to an earlier session's requests are dropped. */
    private int generation;
    private FeedsConfig pendingCopy;
    private long pendingDocumentId;
    private boolean choiceShowing;
    private final Runnable uploadLater = this::check;

    public SavedMessagesSync(int account) {
        super(account);
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("tgfeed_sync" + account, Context.MODE_PRIVATE);
        messageId = prefs.getInt("message_id", 0);
        documentId = prefs.getLong("document_id", 0);
        dirty = prefs.getBoolean("dirty", false);
        changedAt = prefs.getInt("changed_at", 0);
        syncedAt = prefs.getInt("synced_at", 0);
    }

    private void save() {
        prefs.edit()
                .putInt("message_id", messageId)
                .putLong("document_id", documentId)
                .putBoolean("dirty", dirty)
                .putInt("changed_at", changedAt)
                .putInt("synced_at", syncedAt)
                .apply();
    }

    /** The chat list showed: syncs once per process, again after a failure, and asks a pending choice. */
    public void onChatListResumed(BaseFragment fragment) {
        if (!getUserConfig().isClientActivated()) {
            return;
        }
        if (!started) {
            start();
        } else if (failed) {
            check();
        }
        showChoice(fragment);
    }

    /** The "Sync with Saved Messages" row: asks a pending choice, or syncs now. */
    public void syncNow(BaseFragment fragment) {
        if (pendingCopy != null) {
            showChoice(fragment);
        } else if (!started) {
            start();
        } else {
            check();
        }
    }

    /** What the settings row shows on its right. */
    public String status() {
        if (busy) {
            return LocaleController.getString(R.string.TgfeedSyncing);
        } else if (pendingCopy != null) {
            return LocaleController.getString(R.string.TgfeedSyncChoose);
        } else if (failed) {
            return LocaleController.getString(R.string.TgfeedSyncFailed);
        } else if (syncedAt == 0) {
            return LocaleController.getString(R.string.TgfeedSyncNever);
        }
        return LocaleController.stringForMessageListDate(syncedAt);
    }

    private void start() {
        started = true;
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        check();
    }

    /** The feeds and rules are both loaded (called on every snapshot of them). */
    public void onLoaded() {
        if (checkWhenLoaded) {
            checkWhenLoaded = false;
            check();
        }
    }

    /** A feed or rule was created, changed or deleted on this device. */
    public void localChanged() {
        dirty = true;
        changes++;
        changedAt = getConnectionsManager().getCurrentTime();
        save();
        if (started) {
            AndroidUtilities.cancelRunOnUIThread(uploadLater);
            AndroidUtilities.runOnUIThread(uploadLater, UPLOAD_DELAY_MS);
        }
    }

    private boolean loaded() {
        FeedsController feeds = FeedsController.getInstance(currentAccount);
        RulesController rules = RulesController.getInstance(currentAccount);
        feeds.loadFeeds();
        rules.loadRules();
        return feeds.isLoaded() && rules.isLoaded();
    }

    private FeedsConfig localConfig() {
        return new FeedsConfig(FeedsController.getInstance(currentAccount).getFeeds(), RulesController.getInstance(currentAccount).getRules());
    }

    private void check() {
        AndroidUtilities.cancelRunOnUIThread(uploadLater);
        if (!started || pendingCopy != null) {
            return;
        }
        if (busy) {
            checkAgain = true;
            return;
        }
        if (!loaded()) {
            checkWhenLoaded = true;
            return;
        }
        busy = true;
        failed = false;
        changesAtCheck = changes;
        changed();
        findCopy(this::decide);
    }

    private void finish(boolean ok) {
        busy = false;
        failed = !ok;
        changed();
        if (checkAgain) {
            checkAgain = false;
            check();
        }
    }

    private void changed() {
        getNotificationCenter().postNotificationName(NotificationCenter.tgfeedSyncChanged);
    }

    private boolean isCopy(TLRPC.Message message) {
        return message instanceof TLRPC.TL_message && message.peer_id != null && message.peer_id.user_id == getUserConfig().getClientUserId()
                && message.media instanceof TLRPC.TL_messageMediaDocument && message.media.document != null
                && ConfigFile.FILE_NAME.equals(FileLoader.getDocumentFileName(message.media.document));
    }

    private static int lastChange(TLRPC.Message message) {
        return Math.max(message.date, message.edit_date);
    }

    /** Finds the copy: the known message, else the newest such file in Saved Messages. Null when there is none. */
    private void findCopy(Utilities.Callback<TLRPC.Message> callback) {
        int session = generation;
        if (messageId != 0) {
            TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
            req.id.add(messageId);
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (session != generation) {
                    return;
                }
                if (response instanceof TLRPC.messages_Messages) {
                    for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                        if (message.id == messageId && isCopy(message)) {
                            callback.run(message);
                            return;
                        }
                    }
                    searchCopy(callback);
                } else {
                    finish(false);
                }
            }));
        } else {
            searchCopy(callback);
        }
    }

    private void searchCopy(Utilities.Callback<TLRPC.Message> callback) {
        int session = generation;
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.q = ConfigFile.FILE_NAME;
        req.filter = new TLRPC.TL_inputMessagesFilterDocument();
        req.limit = 20;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (session != generation) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.Message newest = null;
                for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                    if (isCopy(message) && (newest == null || lastChange(message) > lastChange(newest))) {
                        newest = message;
                    }
                }
                messageId = newest == null ? 0 : newest.id;
                save();
                callback.run(newest);
            } else {
                finish(false);
            }
        }));
    }

    private void decide(TLRPC.Message copy) {
        if (copy == null) {
            FeedsConfig local = localConfig();
            if (!local.isEmpty() || dirty) {
                upload(local);
            } else {
                finish(true);
            }
            return;
        }
        TLRPC.Document document = copy.media.document;
        if (document.id == documentId) {
            if (dirty) {
                upload(localConfig());
            } else {
                finish(true);
            }
            return;
        }
        if (documentId != 0 && dirty && changedAt > lastChange(copy)) {
            upload(localConfig());
            return;
        }
        download(document, remote -> {
            FeedsConfig local = localConfig();
            if (changes != changesAtCheck) {
                checkAgain = true;
                finish(true);
            } else if (documentId == 0 && !local.isEmpty() && !remote.sameAs(local)) {
                if (remote.isEmpty()) {
                    documentId = document.id;
                    upload(local);
                } else {
                    pendingCopy = remote;
                    pendingDocumentId = document.id;
                    finish(true);
                    showChoice(LaunchActivity.getSafeLastFragment());
                }
            } else {
                apply(remote, document.id);
                finish(true);
            }
        });
    }

    /** Makes the copy this device's feeds and rules. */
    private void apply(FeedsConfig remote, long remoteDocumentId) {
        if (!remote.sameAs(localConfig())) {
            FeedsController.getInstance(currentAccount).replaceAll(remote);
        }
        documentId = remoteDocumentId;
        dirty = false;
        syncedAt = getConnectionsManager().getCurrentTime();
        save();
    }

    private void download(TLRPC.Document document, Utilities.Callback<FeedsConfig> callback) {
        downloadPart(document, new ByteArrayOutputStream(), generation, callback);
    }

    private void downloadPart(TLRPC.Document document, ByteArrayOutputStream out, int session, Utilities.Callback<FeedsConfig> callback) {
        TLRPC.TL_inputDocumentFileLocation location = new TLRPC.TL_inputDocumentFileLocation();
        location.id = document.id;
        location.access_hash = document.access_hash;
        location.file_reference = document.file_reference == null ? new byte[0] : document.file_reference;
        location.thumb_size = "";
        TLRPC.TL_upload_getFile req = new TLRPC.TL_upload_getFile();
        req.location = location;
        req.offset = out.size();
        req.limit = DOWNLOAD_LIMIT;
        getConnectionsManager().sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_upload_file) {
                NativeByteBuffer bytes = ((TLRPC.TL_upload_file) response).bytes;
                byte[] chunk = new byte[bytes.limit()];
                bytes.position(0);
                bytes.readBytes(chunk, false);
                out.write(chunk, 0, chunk.length);
                if (chunk.length == DOWNLOAD_LIMIT && out.size() < document.size) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (session == generation) {
                            downloadPart(document, out, session, callback);
                        }
                    });
                    return;
                }
                FeedsConfig config;
                try {
                    config = ConfigFile.decode(out.toByteArray());
                } catch (Exception e) {
                    FileLog.e(e);
                    config = null;
                }
                FeedsConfig result = config;
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != generation) {
                        return;
                    }
                    if (result != null) {
                        callback.run(result);
                    } else {
                        finish(false);
                    }
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (session == generation) {
                        finish(false);
                    }
                });
            }
        }, null, null, ConnectionsManager.RequestFlagForceDownload | ConnectionsManager.RequestFlagFailOnServerErrors, document.dc_id, ConnectionsManager.ConnectionTypeGeneric, true);
    }

    /** Writes the config into the copy's message, or into a new one when there is none. */
    private void upload(FeedsConfig config) {
        byte[] bytes;
        try {
            bytes = ConfigFile.encode(config);
        } catch (Exception e) {
            FileLog.e(e);
            finish(false);
            return;
        }
        int changesAtUpload = changes;
        long fileId = Utilities.random.nextLong();
        int parts = Math.max(1, (bytes.length + PART_SIZE - 1) / PART_SIZE);
        uploadPart(bytes, fileId, 0, parts, generation, () -> sendFile(fileId, parts, messageId != 0, changesAtUpload, generation));
    }

    private void uploadPart(byte[] bytes, long fileId, int part, int parts, int session, Runnable done) {
        TLRPC.TL_upload_saveFilePart req = new TLRPC.TL_upload_saveFilePart();
        req.file_id = fileId;
        req.file_part = part;
        int offset = part * PART_SIZE;
        int length = Math.min(PART_SIZE, bytes.length - offset);
        try {
            req.bytes = new NativeByteBuffer(length);
            req.bytes.writeBytes(bytes, offset, length);
        } catch (Exception e) {
            FileLog.e(e);
            finish(false);
            return;
        }
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (session != generation) {
                return;
            }
            if (response instanceof TLRPC.TL_boolTrue) {
                if (part + 1 < parts) {
                    uploadPart(bytes, fileId, part + 1, parts, session, done);
                } else {
                    done.run();
                }
            } else {
                finish(false);
            }
        }), 0, ConnectionsManager.ConnectionTypeUpload);
    }

    private void sendFile(long fileId, int parts, boolean edit, int changesAtUpload, int session) {
        TLRPC.TL_inputFile file = new TLRPC.TL_inputFile();
        file.id = fileId;
        file.parts = parts;
        file.name = ConfigFile.FILE_NAME;
        file.md5_checksum = "";
        TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        media.file = file;
        media.mime_type = ConfigFile.MIME_TYPE;
        media.force_file = true;
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = ConfigFile.FILE_NAME;
        media.attributes.add(name);
        String caption = LocaleController.getString(R.string.TgfeedSyncCaption);
        TLObject request;
        if (edit) {
            TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
            req.peer = new TLRPC.TL_inputPeerSelf();
            req.id = messageId;
            req.message = caption;
            req.media = media;
            req.flags |= 2048 | 16384;
            request = req;
        } else {
            TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
            req.peer = new TLRPC.TL_inputPeerSelf();
            req.media = media;
            req.message = caption;
            req.random_id = Utilities.random.nextLong();
            request = req;
        }
        getConnectionsManager().sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                TLRPC.Updates updates = (TLRPC.Updates) response;
                TLRPC.Message sent = sentMessage(updates);
                // the state is set on the UI thread before the updates below announce the message
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != generation) {
                        return;
                    }
                    if (sent != null) {
                        messageId = sent.id;
                        documentId = sent.media.document.id;
                    }
                    dirty = changes != changesAtUpload;
                    syncedAt = getConnectionsManager().getCurrentTime();
                    save();
                    finish(true);
                    if (dirty) {
                        check();
                    }
                });
                getMessagesController().processUpdates(updates, false);
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != generation) {
                        return;
                    }
                    if (edit && error != null && error.text != null && error.text.startsWith("MESSAGE_ID_INVALID")) {
                        messageId = 0;
                        save();
                        sendFile(fileId, parts, false, changesAtUpload, session);
                    } else {
                        finish(false);
                    }
                });
            }
        });
    }

    private TLRPC.Message sentMessage(TLRPC.Updates updates) {
        List<TLRPC.Update> list = new ArrayList<>(updates.updates);
        if (updates.update != null) {
            list.add(updates.update);
        }
        for (TLRPC.Update update : list) {
            TLRPC.Message message = null;
            if (update instanceof TL_update.TL_updateNewMessage) {
                message = ((TL_update.TL_updateNewMessage) update).message;
            } else if (update instanceof TL_update.TL_updateEditMessage) {
                message = ((TL_update.TL_updateEditMessage) update).message;
            }
            if (message != null && isCopy(message)) {
                return message;
            }
        }
        return null;
    }

    private void showChoice(BaseFragment fragment) {
        if (pendingCopy == null || choiceShowing || fragment == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        FeedsConfig local = localConfig();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.TgfeedSyncChoiceTitle));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.TgfeedSyncChoiceText,
                LocaleController.formatPluralString("TgfeedSyncFeeds", pendingCopy.feeds.size()),
                LocaleController.formatPluralString("TgfeedSyncRules", pendingCopy.rules.size()),
                LocaleController.formatPluralString("TgfeedSyncFeeds", local.feeds.size()),
                LocaleController.formatPluralString("TgfeedSyncRules", local.rules.size()))));
        builder.setPositiveButton(LocaleController.getString(R.string.TgfeedSyncMerge), (dialog, which) -> choose(CHOICE_MERGE));
        builder.setNegativeButton(LocaleController.getString(R.string.TgfeedSyncKeepLocal), (dialog, which) -> choose(CHOICE_KEEP));
        builder.setNeutralButton(LocaleController.getString(R.string.TgfeedSyncUseCopy), (dialog, which) -> choose(CHOICE_COPY));
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> choiceShowing = false);
        if (fragment.showDialog(dialog) != null) {
            choiceShowing = true;
        }
    }

    private void choose(int choice) {
        if (pendingCopy == null) {
            return;
        }
        FeedsConfig copy = pendingCopy;
        long copyDocumentId = pendingDocumentId;
        pendingCopy = null;
        pendingDocumentId = 0;
        if (choice == CHOICE_COPY) {
            apply(copy, copyDocumentId);
            changed();
            return;
        }
        FeedsConfig result = localConfig();
        if (choice == CHOICE_MERGE) {
            result = copy.merge(result);
            FeedsController.getInstance(currentAccount).replaceAll(result);
        }
        documentId = copyDocumentId;
        save();
        busy = true;
        failed = false;
        changed();
        upload(result);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDeleted) {
            if (messageId != 0 && args[0] instanceof ArrayList && args[1] instanceof Long && (Long) args[1] == 0 && ((ArrayList<?>) args[0]).contains(messageId)) {
                messageId = 0;
                save();
            }
            return;
        }
        if (!(args[0] instanceof Long) || (long) args[0] != getUserConfig().getClientUserId() || !(args[1] instanceof ArrayList)) {
            return;
        }
        if (id == NotificationCenter.didReceiveNewMessages && (boolean) args[2]) {
            return;
        }
        for (Object object : (ArrayList<?>) args[1]) {
            if (!(object instanceof MessageObject)) {
                continue;
            }
            TLRPC.Message message = ((MessageObject) object).messageOwner;
            if (isCopy(message) && message.media.document.id != documentId && message.media.document.id != pendingDocumentId) {
                messageId = message.id;
                save();
                check();
                return;
            }
        }
    }

    /** The account logged out: forgets the copy's state; the copy stays in Saved Messages. */
    public void cleanup() {
        generation++;
        AndroidUtilities.cancelRunOnUIThread(uploadLater);
        if (started) {
            getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
            getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
            getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        }
        started = false;
        checkWhenLoaded = false;
        busy = false;
        checkAgain = false;
        failed = false;
        pendingCopy = null;
        pendingDocumentId = 0;
        messageId = 0;
        documentId = 0;
        dirty = false;
        changedAt = 0;
        syncedAt = 0;
        prefs.edit().clear().apply();
        changed();
    }
}
