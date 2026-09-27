package com.sholatbre.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Re-schedules all prayer alarms after device reboot.
 * AlarmManager alarms are cleared on reboot, so we need to restore them
 * from SharedPreferences where they were saved by NativeBridge.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String PREFS_NAME = "prayer_alarm_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.d(TAG, "Device booted or app updated, rescheduling alarms");
            rescheduleAlarms(context);
            InboxSyncReceiver.schedule(context, 12_000L);
        }
    }

    private void rescheduleAlarms(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String jsonData = prefs.getString("alarm_data", null);

            if (jsonData == null || jsonData.isEmpty()) {
                Log.d(TAG, "No saved alarm data found");
                return;
            }

            JSONArray alarms = new JSONArray(jsonData);

            // Cancel existing and reschedule
            AlarmScheduler.cancelAll(context);

            int scheduled = 0;
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.getJSONObject(i);

                boolean enabled = alarm.optBoolean("enabled", false);
                if (!enabled) continue;

                String audioUrl = alarm.optString("audioUrl", "");
                if (audioUrl.isEmpty()) continue;

                String name = alarm.optString("name", "Alarm");
                int hour = alarm.optInt("hour", 0);
                int minute = alarm.optInt("minute", 0);
                int volume = alarm.optInt("volume", 80);
                String label = alarm.optString("label", name);

                AlarmScheduler.schedule(context, i, name, label, hour, minute, audioUrl, volume);
                scheduled++;
            }

            Log.d(TAG, "Rescheduled " + scheduled + " alarms after boot");

        } catch (Exception e) {
            Log.e(TAG, "Error rescheduling alarms: " + e.getMessage(), e);
        }
    }
}
