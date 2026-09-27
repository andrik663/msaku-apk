package com.sholatbre.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Receives alarm broadcasts from AlarmManager.
 * Starts the AlarmAudioService to play the adzan audio.
 * Also reschedules the alarm for tomorrow (daily repeat).
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received!");

        // Acquire a temporary wake lock to ensure the device stays awake
        // long enough to start the foreground service
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SholatBre::AlarmReceiverWake"
            );
            wakeLock.acquire(60 * 1000L); // 60 seconds max
        }

        try {
            String name = intent.getStringExtra("name");
            String label = intent.getStringExtra("label");
            int hour = intent.getIntExtra("hour", 0);
            int minute = intent.getIntExtra("minute", 0);
            String audioUrl = intent.getStringExtra("audioUrl");
            int volume = intent.getIntExtra("volume", 80);
            int requestCode = intent.getIntExtra("requestCode", 0);

            Log.d(TAG, "Prayer alarm: " + name + " audioUrl: " + audioUrl + " volume: " + volume);

            if (audioUrl != null && !audioUrl.isEmpty()) {
                // Start foreground service to play audio
                Intent serviceIntent = new Intent(context, AlarmAudioService.class);
                serviceIntent.setAction("PLAY");
                serviceIntent.putExtra("label", label != null ? label : name);
                serviceIntent.putExtra("name", name);
                serviceIntent.putExtra("audioUrl", audioUrl);
                serviceIntent.putExtra("volume", volume);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }

            // Reschedule for tomorrow (daily repeating alarm)
            if (audioUrl != null && !audioUrl.isEmpty()) {
                AlarmScheduler.schedule(context, requestCode, name, label != null ? label : name,
                        hour, minute, audioUrl, volume);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onReceive: " + e.getMessage(), e);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
}
