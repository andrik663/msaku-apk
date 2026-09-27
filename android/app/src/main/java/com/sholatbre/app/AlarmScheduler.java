package com.sholatbre.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * Schedules exact alarms using Android's AlarmManager.
 * Each prayer alarm gets a unique request code (0-5).
 * Alarms are set for today if the time hasn't passed, otherwise for tomorrow.
 */
public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";
    private static final int MAX_ALARMS = 10; // 5 prayers + buffer

    /**
     * Schedule an exact alarm for a specific prayer time.
     */
    public static void schedule(
            Context context,
            int requestCode,
            String name,
            String label,
            int hour,
            int minute,
            String audioUrl,
            int volume
    ) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager not available");
            return;
        }

        // Calculate target time
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        // If time has passed today, schedule for tomorrow
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Create intent for the BroadcastReceiver
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("name", name);
        intent.putExtra("label", label);
        intent.putExtra("hour", hour);
        intent.putExtra("minute", minute);
        intent.putExtra("audioUrl", audioUrl);
        intent.putExtra("volume", volume);
        intent.putExtra("requestCode", requestCode);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Use setAlarmClock for highest priority (shows alarm icon in status bar)
        // This survives Doze mode and is the most reliable method
        try {
            AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(
                    calendar.getTimeInMillis(),
                    getOpenAppIntent(context)
            );
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent);

            Log.d(TAG, "Alarm scheduled: " + name + " at " + hour + ":" + String.format("%02d", minute)
                    + " (in " + ((calendar.getTimeInMillis() - System.currentTimeMillis()) / 60000) + " min)");
        } catch (SecurityException e) {
            // Fallback to setExactAndAllowWhileIdle
            Log.w(TAG, "setAlarmClock failed, trying setExactAndAllowWhileIdle: " + e.getMessage());
            try {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.getTimeInMillis(),
                        pendingIntent
                );
            } catch (SecurityException e2) {
                Log.e(TAG, "All alarm scheduling methods failed: " + e2.getMessage());
            }
        }
    }

    /**
     * Cancel all scheduled alarms.
     */
    public static void cancelAll(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        for (int i = 0; i < MAX_ALARMS; i++) {
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    i,
                    intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent);
                pendingIntent.cancel();
            }
        }
        Log.d(TAG, "All alarms cancelled");
    }

    /**
     * Create a PendingIntent that opens the app when tapping the alarm clock icon.
     */
    private static PendingIntent getOpenAppIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context, 999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
