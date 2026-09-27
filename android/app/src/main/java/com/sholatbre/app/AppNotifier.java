package com.sholatbre.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Order / DM heads-up notifications.
 * Uses dedicated channels and a standalone notification sound — never the
 * prayer/adzan (ibadah) channel or alarm audio.
 */
public final class AppNotifier {

    private static final String TAG = "AppNotifier";

    // New IDs: Android will not update sound on an existing channel.
    public static final String MESSAGES_CHANNEL_ID = "msaku_messages_v2";
    public static final String ORDERS_CHANNEL_ID = "msaku_orders_v2";

    private static final int MESSAGE_NOTIF_BASE = 4100;
    private static final int ORDER_NOTIF_BASE = 4200;

    private AppNotifier() {}

    private static boolean wasRecentlyShown(Context context, String title, String body, String tag) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("msaku_notif_dedupe", Context.MODE_PRIVATE);
        String key = (tag == null ? "" : tag) + "|" + title + "|" + body;
        long now = System.currentTimeMillis();
        long last = prefs.getLong(key, 0L);
        if (now - last < 90_000L) return true;
        prefs.edit().putLong(key, now).apply();
        return false;
    }

    public static Uri notifySound(Context context) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.msaku_notify);
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Drop stale channels that had no sound / defaulted to ibadah audio.
        try {
            manager.deleteNotificationChannel("msaku_messages");
            manager.deleteNotificationChannel("msaku_orders");
            manager.deleteNotificationChannel("muslim_saku_messages");
            manager.deleteNotificationChannel("muslim_saku_default");
        } catch (Exception ignored) {}

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        Uri sound = notifySound(context);

        NotificationChannel messages = new NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "Pesan & DM",
                NotificationManager.IMPORTANCE_HIGH
        );
        messages.setDescription("Notifikasi chat dan DM masuk");
        messages.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        messages.enableVibration(true);
        messages.setVibrationPattern(new long[]{0, 180, 80, 180});
        messages.enableLights(true);
        messages.setLightColor(0xFF10B981);
        messages.setSound(sound, attrs);
        messages.setShowBadge(true);

        NotificationChannel orders = new NotificationChannel(
                ORDERS_CHANNEL_ID,
                "Pesanan Lapak",
                NotificationManager.IMPORTANCE_HIGH
        );
        orders.setDescription("Notifikasi pesanan marketplace");
        orders.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        orders.enableVibration(true);
        orders.setVibrationPattern(new long[]{0, 220, 90, 220});
        orders.enableLights(true);
        orders.setLightColor(0xFF10B981);
        orders.setSound(sound, attrs);
        orders.setShowBadge(true);

        manager.createNotificationChannel(messages);
        manager.createNotificationChannel(orders);
    }

    public static void show(Context context, String title, String body, String kind, String deeplink, String tag) {
        try {
            ensureChannels(context);
            if (title == null || title.isEmpty()) title = "msaku";
            if (body == null) body = "";
            if (wasRecentlyShown(context, title, body, tag)) return;
            if (kind == null) kind = "message";
            if (deeplink == null || deeplink.isEmpty()) {
                deeplink = "order".equalsIgnoreCase(kind) ? "/market/orders" : "/dm";
            }
            if (tag == null) tag = "";

            boolean isOrder = "order".equalsIgnoreCase(kind);
            String channelId = isOrder ? ORDERS_CHANNEL_ID : MESSAGES_CHANNEL_ID;
            int notifId = isOrder ? ORDER_NOTIF_BASE : MESSAGE_NOTIF_BASE;
            if (!tag.isEmpty()) {
                notifId += Math.abs(tag.hashCode() % 800);
            }

            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setAction("com.sholatbre.app.OPEN_DEEPLINK");
            openIntent.putExtra("deeplink", deeplink);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPending = PendingIntent.getActivity(
                    context,
                    notifId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Uri sound = notifySound(context);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_stat_onesignal_default)
                    .setContentIntent(openPending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setSound(sound)
                    .setVibrate(isOrder ? new long[]{0, 220, 90, 220} : new long[]{0, 180, 80, 180})
                    .setCategory(isOrder ? NotificationCompat.CATEGORY_STATUS : NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(notifId, builder.build());
            }
            Log.d(TAG, "show kind=" + kind + " id=" + notifId);
        } catch (Exception e) {
            Log.e(TAG, "Error showing app notification: " + e.getMessage(), e);
        }
    }
}
