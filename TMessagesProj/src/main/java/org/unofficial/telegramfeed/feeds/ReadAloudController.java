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
            if (SharedConfig.tgfeedRulesPaused || TextUtils.isEmpty(text)) {
                return;
            }
            String key = account + "_" + dialogId + "_" + messageId;
            if (!taken.add(key)) {
                return;
            }
            while (taken.size() > 500) {
                taken.remove(taken.iterator().next());
            }
            queue.add(new Item(account, dialogId, messageId, channelTitle, text));
            log("queued " + key + ", waiting " + queue.size());
            changed();
            start();
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
        item.spoken = SpeechText.prepare(item.text, item.channelTitle, SpeechText.DEFAULT_MAX_CHARS,
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
            log("audio focus refused, waiting");
            AndroidUtilities.runOnUIThread(retry, CALL_RETRY_MS);
            return;
        }
        String language = item.language == null || UNDETERMINED.equals(item.language) ? interfaceLanguage() : item.language;
        Locale locale = Locale.forLanguageTag(language);
        int available = tts.isLanguageAvailable(locale);
        if (available >= TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(locale);
        } else {
            tts.setLanguage(Locale.forLanguageTag(interfaceLanguage()));
        }
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

    private void changed() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.tgfeedReadAloudChanged);
    }

    /** "en" or "uk": the interface language when the fork has its words, English otherwise. */
    private static String interfaceLanguage() {
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
