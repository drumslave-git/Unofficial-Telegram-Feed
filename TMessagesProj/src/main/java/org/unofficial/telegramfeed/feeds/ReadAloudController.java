package org.unofficial.telegramfeed.feeds;

import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.unofficial.telegramfeed.core.SpeechText;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Reads posts aloud, one at a time and in the order they came, with Android's TextToSpeech. Each
 * post is introduced with its channel, in the post's language when it is English or Ukrainian and
 * in the interface language otherwise, and is spoken with a voice of its detected language. While
 * speaking it holds a transient audio focus that lets other audio duck; a phone call or another
 * app taking the focus stops the post, which is read again from the start once the focus returns.
 * A media foreground service keeps the process speaking with the screen off or after a push.
 * All state lives on the UI thread.
 */
public final class ReadAloudController implements NotificationCenter.NotificationCenterDelegate {

    /** A post waiting to be read. */
    public static final class Item {
        public final int account;
        public final long dialogId;
        public final int messageId;
        public final String channelTitle;
        final String text;
        String language;
        String spoken;

        Item(int account, long dialogId, int messageId, String channelTitle, String text) {
            this.account = account;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.channelTitle = channelTitle;
            this.text = text;
        }
    }

    private static final long CALL_RETRY_MS = 3000;
    private static final long STOP_SETTLE_MS = 400;
    private static final String UNDETERMINED = "und";

    private static volatile ReadAloudController instance;

    public static ReadAloudController getInstance() {
        ReadAloudController local = instance;
        if (local == null) {
            synchronized (ReadAloudController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new ReadAloudController();
                }
            }
        }
        return local;
    }

    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> taken = new LinkedHashSet<>();
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;
    private Item current;
    private boolean speaking;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private int utteranceSerial;
    private int focusRefusals;
    private MediaSession keys;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> AndroidUtilities.runOnUIThread(() -> onFocusChange(change));
    private final Runnable retry = this::next;

    private ReadAloudController() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.tgfeedPauseChanged));
    }

    /** The post being read, or null. */
    public Item getCurrent() {
        return current;
    }

    /** How many posts wait after the current one. */
    public int getWaitingCount() {
        return queue.size();
    }

    /** Adds a post to the queue; a post already queued or read is not added again. Any thread. */
    public void enqueue(int account, long dialogId, int messageId, String channelTitle, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            if (SharedConfig.tgfeedRulesPaused) {
                return;
            }
            add(account, dialogId, messageId, channelTitle, text, false);
        });
    }

    private boolean add(int account, long dialogId, int messageId, String channelTitle, String text, boolean again) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String key = key(account, dialogId, messageId);
        restoreTaken();
        if (!taken.add(key) && !again) {
            return false;
        }
        while (taken.size() > 500) {
            taken.remove(taken.iterator().next());
        }
        saveTaken();
        queue.add(new Item(account, dialogId, messageId, channelTitle, text));
        log("queued " + key + ", waiting " + queue.size());
        changed();
        start();
        return true;
    }

    private boolean takenRestored;

    /** The posts queued or read survive the process, so "Listen" after a push knows what was read. */
    private void restoreTaken() {
        if (takenRestored) {
            return;
        }
        takenRestored = true;
        String saved = ApplicationLoader.applicationContext.getSharedPreferences("tgfeed_read_aloud", Context.MODE_PRIVATE).getString("taken", "");
        if (!saved.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(java.util.Arrays.asList(saved.split(",")));
            merged.addAll(taken);
            taken.clear();
            taken.addAll(merged);
        }
    }

    private void saveTaken() {
        ApplicationLoader.applicationContext.getSharedPreferences("tgfeed_read_aloud", Context.MODE_PRIVATE).edit().putString("taken", TextUtils.join(",", taken)).apply();
    }

    private static String key(int account, long dialogId, int messageId) {
        return account + "_" + dialogId + "_" + messageId;
    }

    /**
     * "Listen" on a notification: queues the posts it lists that were not read aloud yet, or all
     * of them again when every one was. Any thread.
     */
    public void listen(int account, long dialogId, String channelTitle, int[] messageIds, String[] texts) {
        AndroidUtilities.runOnUIThread(() -> {
            restoreTaken();
            boolean anyNew = false;
            for (int messageId : messageIds) {
                if (!taken.contains(key(account, dialogId, messageId))) {
                    anyNew = true;
                    break;
                }
            }
            for (int i = 0; i < messageIds.length && i < texts.length; i++) {
                if (!anyNew || !taken.contains(key(account, dialogId, messageIds[i]))) {
                    add(account, dialogId, messageIds[i], channelTitle, texts[i], true);
                }
            }
        });
    }

    /** Stops and drops the posts of one channel, or of every channel of the account for 0. Any thread. */
    public void stopDialog(int account, long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            boolean removed = queue.removeIf(item -> item.account == account && (dialogId == 0 || item.dialogId == dialogId));
            if (current != null && current.account == account && (dialogId == 0 || current.dialogId == dialogId)) {
                finishCurrent();
                AndroidUtilities.runOnUIThread(retry, STOP_SETTLE_MS);
            } else if (removed) {
                changed();
                if (current == null && queue.isEmpty()) {
                    idle();
                }
            }
        });
    }

    /** Stops the post being read and empties the queue. */
    public void stopAll() {
        AndroidUtilities.runOnUIThread(() -> {
            queue.clear();
            finishCurrent();
            idle();
        });
    }

    /** Stops the post being read and goes on with the next. */
    public void skipCurrent() {
        AndroidUtilities.runOnUIThread(() -> {
            finishCurrent();
            // The engine stops asynchronously; a post spoken at once would be cut by that stop.
            AndroidUtilities.runOnUIThread(retry, STOP_SETTLE_MS);
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.tgfeedPauseChanged && SharedConfig.tgfeedRulesPaused) {
            stopAll();
        }
    }

    private void start() {
        ReadAloudService.start();
        holdKeys();
        if (wakeLock == null) {
            PowerManager power = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tgfeed:readaloud");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }
        if (tts == null && !ttsFailed) {
            tts = new TextToSpeech(ApplicationLoader.applicationContext, status -> AndroidUtilities.runOnUIThread(() -> {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    tts.setAudioAttributes(audioAttributes());
                    tts.setOnUtteranceProgressListener(progress);
                    next();
                } else {
                    log("speech engine failed to start: " + status);
                    ttsFailed = true;
                    tts = null;
                    queue.clear();
                    idle();
                }
            }));
            return;
        }
        if (ttsReady && current == null) {
            next();
        }
    }

    private static AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** Reads the next post, or goes idle when there is none. */
    private void next() {
        AndroidUtilities.cancelRunOnUIThread(retry);
        if (!ttsReady || speaking) {
            return;
        }
        if (current == null) {
            current = queue.poll();
            changed();
        }
        if (current == null) {
            idle();
            return;
        }
        if (inCall()) {
            log("waiting for the call to end");
            AndroidUtilities.runOnUIThread(retry, CALL_RETRY_MS);
            return;
        }
        Item item = current;
        if (item.spoken != null) {
            speak(item);
            return;
        }
        LanguageDetector.detectLanguage(item.text, language -> AndroidUtilities.runOnUIThread(() -> {
            item.language = language;
            prepareAndSpeak(item);
        }), e -> AndroidUtilities.runOnUIThread(() -> {
            item.language = UNDETERMINED;
            prepareAndSpeak(item);
        }));
    }

    private void prepareAndSpeak(Item item) {
        if (item != current) {
            return;
        }
        String introLanguage = "en".equals(item.language) || "uk".equals(item.language) ? item.language : interfaceLanguage();
        Context words = localized(introLanguage);
        item.spoken = SpeechText.prepare(item.text, item.channelTitle, ReadAloudSettings.getMaxChars(),
                words.getString(R.string.TgfeedTtsLink), words.getString(R.string.TgfeedTtsMore),
                channel -> words.getString(R.string.TgfeedTtsIntro, channel));
        if (item.spoken.isEmpty()) {
            current = null;
            next();
            return;
        }
        speak(item);
    }

    private void speak(Item item) {
        if (!requestFocus()) {
            // Android refuses the focus to an app in the background without a foreground service,
            // which it lets start only after a push, a tap on a notification or with the battery
            // optimisation off. Outside a call the post is then spoken without the focus.
            if (focusRefusals++ < 1) {
                log("audio focus refused, trying again");
                AndroidUtilities.runOnUIThread(retry, CALL_RETRY_MS);
                return;
            }
            log("audio focus refused, speaking without it");
        }
        focusRefusals = 0;
        String language = item.language == null || UNDETERMINED.equals(item.language) ? fallbackLanguage() : item.language;
        int available = applyVoice(tts, language);
        tts.setSpeechRate(ReadAloudSettings.getRate());
        tts.setPitch(ReadAloudSettings.getPitch());
        speaking = true;
        String id = "tgfeed_" + (++utteranceSerial);
        Bundle params = new Bundle();
        log("speak " + item.dialogId + "/" + item.messageId + " [" + language + (available >= TextToSpeech.LANG_AVAILABLE ? "" : ", no voice") + "] " + item.spoken.length() + " chars");
        if (tts.speak(item.spoken, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
            speaking = false;
            current = null;
            abandonFocus();
            AndroidUtilities.runOnUIThread(retry, 500);
        }
    }

    private final UtteranceProgressListener progress = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
        }

        @Override
        public void onDone(String utteranceId) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!("tgfeed_" + utteranceSerial).equals(utteranceId)) {
                    return;
                }
                speaking = false;
                current = null;
                changed();
                if (queue.isEmpty()) {
                    idle();
                } else {
                    next();
                }
            });
        }

        @Override
        public void onError(String utteranceId) {
            onDone(utteranceId);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            log("speech error " + errorCode + " for " + utteranceId);
            onDone(utteranceId);
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            AndroidUtilities.runOnUIThread(() -> {
                if (("tgfeed_" + utteranceSerial).equals(utteranceId)) {
                    speaking = false;
                }
            });
        }
    };

    private void onFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (speaking && current != null) {
                log("focus lost, the post is read again later");
                utteranceSerial++;
                speaking = false;
                tts.stop();
                AndroidUtilities.runOnUIThread(retry, CALL_RETRY_MS);
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && current != null && !speaking) {
            next();
        }
    }

    private boolean requestFocus() {
        AudioManager audio = audioManager();
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(audioAttributes())
                        .setOnAudioFocusChangeListener(focusListener)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            result = audio.requestAudioFocus(focusRequest);
        } else {
            result = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        AudioManager audio = audioManager();
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) {
                audio.abandonAudioFocusRequest(focusRequest);
            }
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    /**
     * Takes the volume keys and a headset's buttons while posts are read or wait: Android gives
     * them to the media session that plays, and this one plays "remotely" through a volume
     * provider of its own, so volume down stops the speech instead of lowering the volume, also
     * with the screen off; volume up raises the media volume as usual. A headset's pause or stop
     * stops the speech too. Released when the queue is done, so the keys work as usual again.
     */
    private void holdKeys() {
        if (keys != null) {
            return;
        }
        try {
            AudioManager audio = audioManager();
            int stream = AudioManager.STREAM_MUSIC;
            VolumeProvider volume = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream)) {
                @Override
                public void onAdjustVolume(int direction) {
                    if (direction < 0) {
                        log("volume down stops the speech");
                        stopAll();
                    } else if (direction > 0) {
                        audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                        setCurrentVolume(audio.getStreamVolume(stream));
                    }
                }

                @Override
                public void onSetVolumeTo(int value) {
                    audio.setStreamVolume(stream, value, 0);
                    setCurrentVolume(audio.getStreamVolume(stream));
                }
            };
            keys = new MediaSession(ApplicationLoader.applicationContext, "tgfeed-read-aloud");
            keys.setPlaybackToRemote(volume);
            keys.setCallback(new MediaSession.Callback() {
                @Override
                public void onPause() {
                    log("headset pause stops the speech");
                    stopAll();
                }

                @Override
                public void onStop() {
                    stopAll();
                }
            });
            keys.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
            keys.setActive(true);
        } catch (Throwable e) {
            FileLog.e(e);
            keys = null;
        }
    }

    private void releaseKeys() {
        if (keys != null) {
            keys.release();
            keys = null;
        }
    }

    private static AudioManager audioManager() {
        return (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
    }

    private static boolean inCall() {
        int mode = audioManager().getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
    }

    private void finishCurrent() {
        AndroidUtilities.cancelRunOnUIThread(retry);
        utteranceSerial++;
        if (tts != null && speaking) {
            tts.stop();
        }
        speaking = false;
        current = null;
        changed();
    }

    private void idle() {
        AndroidUtilities.cancelRunOnUIThread(retry);
        releaseKeys();
        abandonFocus();
        ReadAloudService.stop();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        changed();
    }

    private final java.util.HashSet<String> readingDialogs = new java.util.HashSet<>();

    private void changed() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.tgfeedReadAloudChanged);
        // A rule notification shows "Stop" while a post of its channel is read or waits, "Listen" otherwise.
        java.util.HashSet<String> now = new java.util.HashSet<>();
        if (current != null) {
            now.add(current.account + "_" + current.dialogId);
        }
        for (Item item : queue) {
            now.add(item.account + "_" + item.dialogId);
        }
        if (!now.equals(readingDialogs)) {
            java.util.HashSet<Integer> accounts = new java.util.HashSet<>();
            for (String key : now) {
                if (!readingDialogs.contains(key)) accounts.add(Integer.parseInt(key.substring(0, key.indexOf('_'))));
            }
            for (String key : readingDialogs) {
                if (!now.contains(key)) accounts.add(Integer.parseInt(key.substring(0, key.indexOf('_'))));
            }
            synchronized (readingDialogs) {
                readingDialogs.clear();
                readingDialogs.addAll(now);
            }
            for (int account : accounts) {
                org.telegram.messenger.NotificationsController.getInstance(account).showNotifications();
            }
        }
    }

    /** Whether a post of the channel is read or waits; any thread, as of the last change. */
    public boolean isReadingNow(int account, long dialogId) {
        synchronized (readingDialogs) {
            return readingDialogs.contains(account + "_" + dialogId);
        }
    }

    /** The language for posts whose language is unknown: the chosen one, or the interface language. */
    private static String fallbackLanguage() {
        String chosen = ReadAloudSettings.getFallbackLanguage();
        return TextUtils.isEmpty(chosen) ? interfaceLanguage() : chosen;
    }

    /**
     * Sets the engine's voice for a language: the chosen voice when the engine still has it, the
     * engine's default voice for the language otherwise, and for the interface language when the
     * engine has none for it. Returns what {@link TextToSpeech#isLanguageAvailable} said.
     */
    public static int applyVoice(TextToSpeech tts, String language) {
        String voiceName = ReadAloudSettings.getVoice(language);
        if (voiceName != null) {
            try {
                for (android.speech.tts.Voice voice : tts.getVoices()) {
                    if (voiceName.equals(voice.getName())) {
                        tts.setVoice(voice);
                        return TextToSpeech.LANG_AVAILABLE;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        Locale locale = Locale.forLanguageTag(language);
        int available = tts.isLanguageAvailable(locale);
        tts.setLanguage(available >= TextToSpeech.LANG_AVAILABLE ? locale : Locale.forLanguageTag(interfaceLanguage()));
        return available;
    }

    /** "en" or "uk": the interface language when the fork has its words, English otherwise. */
    public static String interfaceLanguage() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && "uk".equals(locale.getLanguage()) ? "uk" : "en";
    }

    private static Context localized(String language) {
        Configuration config = new Configuration(ApplicationLoader.applicationContext.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        return ApplicationLoader.applicationContext.createConfigurationContext(config);
    }

    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("tgfeed read aloud: " + message);
        }
    }

    /** Lets the service know the controller exists, for a service started before any post. */
    static boolean hasWork() {
        ReadAloudController local = instance;
        return local != null && (local.current != null || !local.queue.isEmpty());
    }
}
