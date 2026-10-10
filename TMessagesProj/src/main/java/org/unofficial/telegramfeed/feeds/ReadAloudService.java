package org.unofficial.telegramfeed.feeds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

/**
 * The media foreground service that keeps the process alive while posts are read aloud, so speech
 * goes on with the screen off and after a push. It runs only while the queue has work.
 */
public class ReadAloudService extends Service {

    private static final String CHANNEL = "tgfeed_read_aloud";
    private static final int NOTIFICATION_ID = 0x7f0e1a01;
    private static boolean running;

    static void start() {
        if (running) {
            return;
        }
        try {
            Context context = ApplicationLoader.applicationContext;
            Intent intent = new Intent(context, ReadAloudService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            running = true;
        } catch (Throwable e) {
            // Android refuses a foreground service started from the background without an exemption;
            // speech still runs while the process lives.
            FileLog.e(e);
        }
    }

    static void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext, ReadAloudService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (!ReadAloudController.hasWork()) {
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static Notification buildNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, LocaleController.getString(R.string.TgfeedReadAloudChannel), NotificationManager.IMPORTANCE_LOW);
                channel.setSound(null, null);
                channel.enableVibration(false);
                manager.createNotificationChannel(channel);
            }
        }
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.TgfeedReadingAloud))
                .setContentIntent(content)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
