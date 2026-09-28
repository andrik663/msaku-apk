package com.msaku.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that plays adzan audio using native MediaPlayer.
 * Works even when the app is in background or the screen is off.
 * 
 * Flow:
 * 1. AlarmReceiver starts this service with PLAY action + audioUrl
 * 2. Service creates foreground notification (required by Android)
 * 3. MediaPlayer streams audio from local file URI (Capacitor Filesystem)
 * 4. Notification shows Stop button to dismiss
 * 5. Auto-stops when playback completes
 */
public class AlarmAudioService extends Service {

    private static final String TAG = "AlarmAudioService";
    private static final String CHANNEL_ID = "alarm_audio_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_STOP = "com.msaku.app.ACTION_STOP";

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        // Handle STOP action from notification button
        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "Stop action received");
            stopPlayback();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Handle PLAY action
        if ("PLAY".equals(action)) {
            String label = intent.getStringExtra("label");
            String name = intent.getStringExtra("name");
            String audioUrl = intent.getStringExtra("audioUrl");
            int volume = intent.getIntExtra("volume", 80);

            if (label == null) label = "Alarm Sholat";
            if (audioUrl == null || audioUrl.isEmpty()) {
                Log.e(TAG, "No audio URL provided");
                stopSelf();
                return START_NOT_STICKY;
            }

            Log.d(TAG, "Starting playback: " + name + " url: " + audioUrl);

            // Show foreground notification immediately (required within 5s of startForeground)
            startForeground(NOTIFICATION_ID, buildNotification(label, name));

            // Acquire wake lock to keep CPU running during playback
            acquireWakeLock();

            // Play the audio
            playAudio(audioUrl, volume);
        }

        return START_NOT_STICKY;
    }

    private void playAudio(String url, int volume) {
        // Stop any existing playback
        stopPlayback();

        try {
            mediaPlayer = new MediaPlayer();

            // Use ALARM stream so it plays at alarm volume, not media volume
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            mediaPlayer.setAudioAttributes(attrs);
            mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);

            // Set volume (0.0 to 1.0)
            float vol = Math.max(0f, Math.min(1f, volume / 100f));
            mediaPlayer.setVolume(vol, vol);

            // Set data source from URL (AWS S3 URL)
            mediaPlayer.setDataSource(url);

            // Prepare async to avoid blocking
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "MediaPlayer prepared, starting playback");
                mp.start();
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "Playback completed");
                stopPlayback();
                stopSelf();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                stopPlayback();
                stopSelf();
                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "Error setting up MediaPlayer: " + e.getMessage(), e);
            stopPlayback();
            stopSelf();
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping MediaPlayer: " + e.getMessage());
            }
            mediaPlayer = null;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SholatBre::AudioPlaybackWake"
            );
            wakeLock.acquire(30 * 60 * 1000L); // Max 30 minutes
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private Notification buildNotification(String label, String name) {
        // Open app intent
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Stop action intent
        Intent stopIntent = new Intent(this, AlarmAudioService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Waktu " + (label != null ? label : "Sholat"))
                .setContentText("Adzan sedang diputar")
                .setSmallIcon(R.drawable.ic_stat_onesignal_default)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_pause, "Matikan", stopPending)
                .setAutoCancel(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alarm Sholat",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifikasi saat alarm sholat berbunyi");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.enableVibration(true);
            channel.setBypassDnd(true); // Bypass Do Not Disturb

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Static helper to stop the service from anywhere.
     */
    public static void stopService(Context context) {
        Intent intent = new Intent(context, AlarmAudioService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPlayback();
        releaseWakeLock();
        // Remove the notification
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        super.onDestroy();
    }
}
