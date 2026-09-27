package com.sholatbre.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class PrayerOngoingService extends Service {

    public static final String ACTION_UPDATE = "com.sholatbre.app.action.UPDATE_PRAYER";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "prayer_ongoing_channel";
    private static final int NOTIFICATION_ID = 2002;
    
    // Cache to prevent unnecessary notification updates (saves battery)
    private static String lastTitle = "";
    private static String lastMessage = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = "Jadwal Sholat";
        String message = "Menunggu jadwal...";

        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            if (intent.hasExtra(EXTRA_TITLE)) {
                title = intent.getStringExtra(EXTRA_TITLE);
            }
            if (intent.hasExtra(EXTRA_MESSAGE)) {
                message = intent.getStringExtra(EXTRA_MESSAGE);
            }
        }

        // Skip update if content hasn't changed (battery optimization)
        if (title.equals(lastTitle) && message.equals(lastMessage)) {
            return START_STICKY;
        }
        
        // Update cache
        lastTitle = title;
        lastMessage = message;

        Notification notification = buildNotification(title, message);
        
        // Start foreground service if not already
        startForeground(NOTIFICATION_ID, notification);

        // Keep the service running
        return START_STICKY;
    }

    private Notification buildNotification(String title, String message) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_onesignal_default)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Cannot be swiped away
                .setPriority(NotificationCompat.PRIORITY_LOW) // Doesn't make a sound
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Jadwal Sholat Aktif",
                    NotificationManager.IMPORTANCE_LOW 
            );
            serviceChannel.setDescription("Notifikasi yang selalu menampilkan jadwal sholat berikutnya");
            serviceChannel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Not using bound service
        return null;
    }

    /**
     * Helper method to start or update the ongoing service
     */
    public static void update(Context context, String title, String message) {
        Intent intent = new Intent(context, PrayerOngoingService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_MESSAGE, message);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
