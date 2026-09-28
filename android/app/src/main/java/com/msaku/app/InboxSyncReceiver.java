package com.msaku.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Polls the inbox while the app is closed so order / DM alerts still fire.
 * Prayer alarms stay on AlarmScheduler — this receiver never touches them.
 */
public class InboxSyncReceiver extends BroadcastReceiver {

    private static final String TAG = "InboxSync";
    public static final String ACTION = "com.msaku.app.SYNC_INBOX";
    public static final String PREFS = "msaku_inbox_prefs";
    public static final String KEY_USER_ID = "user_id";
    public static final String KEY_SEEN_IDS = "seen_ids";
    private static final String INBOX_URL = "https://www.msaku.id/api/notifications?unread=true";
    private static final int REQUEST_CODE = 7101;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action != null
                && !ACTION.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                sync(context);
            } catch (Exception e) {
                Log.e(TAG, "sync failed", e);
            } finally {
                InboxSyncReceiver.schedule(context, 90_000L);
                pending.finish();
            }
        }).start();
    }

    public static void registerUser(Context context, String userId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_USER_ID, userId == null ? "" : userId).apply();
        AppNotifier.ensureChannels(context);
        schedule(context, 8_000L);
    }

    public static void unregister(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_USER_ID).apply();
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pendingIntent(context));
        }
    }

    public static void schedule(Context context, long delayMs) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, "");
        if (userId == null || userId.isEmpty()) return;

        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = System.currentTimeMillis() + Math.max(15_000L, delayMs);
        android.app.PendingIntent pi = pendingIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "schedule failed", e);
        }
    }

    private static android.app.PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, InboxSyncReceiver.class);
        intent.setAction(ACTION);
        return android.app.PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void sync(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, "");
        if (userId == null || userId.isEmpty()) return;
        if (prefs.getBoolean("foreground", false)) return;

        URL url = new URL(INBOX_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(12_000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-user-id", userId);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            conn.disconnect();
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        if (code < 200 || code >= 300) {
            Log.w(TAG, "inbox HTTP " + code + " " + sb);
            return;
        }

        JSONObject json = new JSONObject(sb.toString());
        JSONArray items = json.optJSONArray("notifications");
        if (items == null || items.length() == 0) return;

        Set<String> seen = new HashSet<>();
        String seenRaw = prefs.getString(KEY_SEEN_IDS, "");
        if (!seenRaw.isEmpty()) {
            for (String id : seenRaw.split(",")) {
                if (!id.isEmpty()) seen.add(id);
            }
        }

        Set<String> keep = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject n = items.getJSONObject(i);
            String id = String.valueOf(n.optInt("id", 0));
            if (id.equals("0")) continue;
            keep.add(id);
            if (seen.contains(id)) continue;

            String type = n.optString("type", "");
            String title = n.optString("title", "msaku");
            String message = n.optString("message", "");
            String relatedType = n.optString("related_type", "");
            int relatedId = n.optInt("related_id", 0);
            boolean isOrder = type.startsWith("order") || "order".equals(relatedType);
            String kind = isOrder ? "order" : "message";
            String deeplink = isOrder
                    ? (relatedId > 0 ? "/market/orders/" + relatedId : "/market/orders")
                    : "/dm";
            AppNotifier.show(context, title, message, kind, deeplink, kind + "-" + id);
            seen.add(id);
        }

        // Keep only ids still unread so a re-unread later can alert again.
        seen.retainAll(keep);
        StringBuilder out = new StringBuilder();
        for (String id : seen) {
            if (out.length() > 0) out.append(",");
            out.append(id);
        }
        prefs.edit().putString(KEY_SEEN_IDS, out.toString()).apply();
    }
}
