package com.msaku.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;
import java.util.concurrent.Executor;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JavaScript interface exposed to the WebView.
 * JS calls window.NativeAlarm.scheduleAlarms(json) to schedule native Android alarms.
 */
public class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final String PREFS_NAME = "prayer_alarm_prefs";
    private static final String ALARM_NOTIF_CHANNEL_ID = "js_alarm_notif_channel";
    private static final int JS_ALARM_NOTIF_ID = 3003;


    private final Context context;
    
    // Cache to prevent unnecessary ongoing notification updates (battery optimization)
    private static String lastOngoingTitle = "";
    private static String lastOngoingMessage = "";
    
    // Text-to-Speech for navigation guidance
    private TextToSpeech tts;
    private boolean ttsReady = false;

    public NativeBridge(Context context) {
        this.context = context;
        createAlarmNotifChannel();
        AppNotifier.ensureChannels(context);
        initTTS();
    }
    
    private void initTTS() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Set Indonesian language
                int result = tts.setLanguage(new Locale("id", "ID"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to default
                    tts.setLanguage(Locale.getDefault());
                }
                ttsReady = true;
                Log.d(TAG, "TTS initialized successfully");
            } else {
                Log.e(TAG, "TTS initialization failed");
            }
        });
    }

    private void createAlarmNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALARM_NOTIF_CHANNEL_ID,
                    "Alarm Sholat Aktif",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifikasi saat alarm adzan sedang berbunyi");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.enableVibration(true);
            channel.setBypassDnd(true);

            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }



    /**
     * Called from JS when prayer alarm settings or prayer times change.
     * Expects JSON array:
     * [
     *   {
     *     "name": "Subuh",
     *     "hour": 5,
     *     "minute": 15,
     *     "enabled": true,
     *     "audioUrl": "https://..../blob-url.mp3",
     *     "volume": 80,
     *     "label": "Subuh"
     *   },
     *   ...
     * ]
     */
    @JavascriptInterface
    public void scheduleAlarms(String jsonData) {
        try {
            Log.d(TAG, "scheduleAlarms called with: " + jsonData);

            // Save to SharedPreferences for persistence across reboots
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString("alarm_data", jsonData).apply();

            JSONArray alarms = new JSONArray(jsonData);

            // Cancel all existing alarms first
            AlarmScheduler.cancelAll(context);

            // Schedule each enabled alarm
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

            Log.d(TAG, "All alarms scheduled: " + scheduled + "/" + alarms.length());
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling alarms: " + e.getMessage(), e);
        }
    }

    /**
     * Called from JS to cancel all native alarms.
     */
    @JavascriptInterface
    public void cancelAllAlarms() {
        Log.d(TAG, "cancelAllAlarms called");
        AlarmScheduler.cancelAll(context);
    }

    /**
     * Called from JS to stop currently playing alarm audio.
     */
    @JavascriptInterface
    public void stopAlarm() {
        Log.d(TAG, "stopAlarm called");
        AlarmAudioService.stopService(context);
    }

    /**
     * Check if native alarm scheduling is available.
     */
    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    /**
     * Called from JS when an alarm fires to show an Android notification with a Stop button.
     */
    @JavascriptInterface
    public void showAlarmNotification(String title, String message) {
        try {
            Log.d(TAG, "showAlarmNotification called: " + title + " - " + message);

            // Open app when notification is tapped
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPending = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Stop action: opens MainActivity with DISMISS_ALARM flag
            // so it can evaluate JS in WebView to stop the HTML Audio
            Intent stopIntent = new Intent(context, MainActivity.class);
            stopIntent.setAction("com.msaku.app.DISMISS_ALARM");
            stopIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent stopPending = PendingIntent.getActivity(
                    context, 100, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification notification = new NotificationCompat.Builder(context, ALARM_NOTIF_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_stat_onesignal_default)
                    .setContentIntent(openPending)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .addAction(android.R.drawable.ic_media_pause, "Matikan", stopPending)
                    .setAutoCancel(false)
                    .build();

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(JS_ALARM_NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing alarm notification: " + e.getMessage(), e);
        }
    }

    /**
     * Heads-up notification for DM / chat / marketplace orders.
     * Does NOT use the alarm channel, so prayer alarms stay independent.
     * Called from JS: window.NativeAlarm.showAppNotification(title, body, kind, deeplink, tag)
     */
    @JavascriptInterface
    public void showAppNotification(String title, String body, String kind, String deeplink, String tag) {
        AppNotifier.show(context, title, body, kind, deeplink, tag);
    }

    /** Persist the logged-in user so InboxSyncReceiver can poll while the app is closed. */
    @JavascriptInterface
    public void registerInboxUser(String userId) {
        try {
            InboxSyncReceiver.registerUser(context, userId);
        } catch (Exception e) {
            Log.e(TAG, "registerInboxUser failed", e);
        }
    }

    @JavascriptInterface
    public void unregisterInboxUser() {
        try {
            InboxSyncReceiver.unregister(context);
        } catch (Exception e) {
            Log.e(TAG, "unregisterInboxUser failed", e);
        }
    }

    /** Density-independent px of system bars for CSS --sat/--sab. */
    @JavascriptInterface
    public String getSystemInsets() {
        try {
            if (!(context instanceof MainActivity)) {
                return "{\"top\":0,\"bottom\":0}";
            }
            MainActivity activity = (MainActivity) context;
            android.view.View decor = activity.getWindow().getDecorView();
            androidx.core.view.WindowInsetsCompat insets =
                    androidx.core.view.ViewCompat.getRootWindowInsets(decor);
            if (insets == null) return "{\"top\":0,\"bottom\":0}";
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()
                            | androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            );
            float density = context.getResources().getDisplayMetrics().density;
            int top = Math.round(bars.top / density);
            int bottom = Math.round(bars.bottom / density);
            return "{\"top\":" + top + ",\"bottom\":" + bottom + "}";
        } catch (Exception e) {
            Log.e(TAG, "getSystemInsets failed", e);
            return "{\"top\":0,\"bottom\":0}";
        }
    }

    /**
     * Called from JS when alarm is dismissed to remove the notification.
     */
    @JavascriptInterface
    public void hideAlarmNotification() {
        try {
            Log.d(TAG, "hideAlarmNotification called");
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(JS_ALARM_NOTIF_ID);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error hiding alarm notification: " + e.getMessage(), e);
        }
    }

    /**
     * Called from JS to update the ongoing notification (e.g. Next prayer schedule).
     * Uses caching to prevent unnecessary updates and save battery.
     */
    @JavascriptInterface
    public void updateOngoingNotification(String title, String message) {
        try {
            // Skip if content hasn't changed (battery optimization)
            if (title.equals(lastOngoingTitle) && message.equals(lastOngoingMessage)) {
                return;
            }
            
            // Update cache
            lastOngoingTitle = title;
            lastOngoingMessage = message;
            
            Log.d(TAG, "updateOngoingNotification called: " + title + " - " + message);
            PrayerOngoingService.update(context, title, message);
        } catch (Exception e) {
            Log.e(TAG, "Error updating ongoing notification: " + e.getMessage(), e);
        }
    }
    
    /**
     * Text-to-Speech for navigation voice guidance.
     * Called from JS: window.NativeAlarm.speak("Belok kiri")
     */
    @JavascriptInterface
    public void speak(String text) {
        try {
            if (tts != null && ttsReady) {
                // Stop any ongoing speech
                tts.stop();
                
                // Speak the text
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navigation_tts");
                } else {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                }
                Log.d(TAG, "TTS speaking: " + text);
            } else {
                Log.w(TAG, "TTS not ready, cannot speak: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS error: " + e.getMessage(), e);
        }
    }
    
    /**
     * Stop any ongoing TTS speech.
     */
    @JavascriptInterface
    public void stopSpeaking() {
        try {
            if (tts != null) {
                tts.stop();
                Log.d(TAG, "TTS stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping TTS: " + e.getMessage(), e);
        }
    }
    
    /**
     * Cleanup TTS when no longer needed.
     */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
    }
    
    /**
     * Check if camera permission is granted.
     * Called from JS: window.NativeAlarm.hasCameraPermission()
     */
    @JavascriptInterface
    public boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * Check if media/storage permission is granted.
     * Called from JS: window.NativeAlarm.hasMediaPermission()
     */
    @JavascriptInterface
    public boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    /**
     * Check if microphone permission is granted.
     * Called from JS: window.NativeAlarm.hasMicrophonePermission()
     */
    @JavascriptInterface
    public boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * Request camera and media permissions.
     * This will reset the permission step and re-trigger the permission flow.
     * Called from JS: window.NativeAlarm.requestMediaPermissions()
     */
    @JavascriptInterface
    public void requestMediaPermissions() {
        try {
            // Reset permission step to camera/media step
            SharedPreferences prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE);
            prefs.edit().putInt("permission_step", 2).apply(); // STEP_CAMERA_MEDIA = 2
            
            // Trigger permission flow on main thread
            if (context instanceof MainActivity) {
                ((MainActivity) context).runOnUiThread(() -> {
                    try {
                        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("runPermissionStep");
                        method.setAccessible(true);
                        method.invoke(context);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to invoke runPermissionStep", e);
                    }
                });
            }
            Log.d(TAG, "requestMediaPermissions triggered");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting media permissions", e);
        }
    }
    
    /**
     * Get all permissions status as JSON.
     * Called from JS: window.NativeAlarm.getPermissionsStatus()
     */
    @JavascriptInterface
    public String getPermissionsStatus() {
        try {
            org.json.JSONObject status = new org.json.JSONObject();
            status.put("camera", hasCameraPermission());
            status.put("media", hasMediaPermission());
            status.put("microphone", hasMicrophonePermission());
            status.put("location", ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                status.put("notification", ContextCompat.checkSelfPermission(context,
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
            } else {
                status.put("notification", true);
            }
            return status.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error getting permissions status", e);
            return "{}";
        }
    }

    /**
     * Update the system status-bar icon appearance to match the app theme.
     * Called from JS: window.NativeAlarm.setStatusBarStyle("light") or "dark"
     * Passes lightBackground=true when theme is "light" so the system uses
     * dark icons (visible on a light background), and false for dark theme
     * so the system uses light icons (visible on a dark background).
     */
    @JavascriptInterface
    public void setStatusBarStyle(String style) {
        if (!(context instanceof MainActivity)) return;
        final MainActivity activity = (MainActivity) context;
        // lightBackground=true → dark icons (needed on a light/white background)
        // lightBackground=false → light icons (needed on a dark/black background)
        final boolean lightBackground = "light".equalsIgnoreCase(style);
        activity.runOnUiThread(() -> activity.applyStatusBarStyle(lightBackground));
    }
    
    /**
     * Open URL in external browser (outside WebView).
     * Called from JS: window.NativeAlarm.openExternalUrl("https://msaku.id/drama/123")
     * This opens the URL in Chrome or the default browser app, not in WebView.
     */
    @JavascriptInterface
    public void openExternalUrl(String url) {
        try {
            Log.d(TAG, "openExternalUrl called: " + url);
            
            if (url == null || url.isEmpty()) {
                Log.w(TAG, "Empty URL provided to openExternalUrl");
                return;
            }
            
            // Create intent to open URL in external browser
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // Try to open with Chrome first, fallback to default browser
            try {
                browserIntent.setPackage("com.android.chrome");
                context.startActivity(browserIntent);
                Log.d(TAG, "Opened URL in Chrome: " + url);
            } catch (Exception e) {
                // Chrome not available, use default browser
                browserIntent.setPackage(null);
                context.startActivity(browserIntent);
                Log.d(TAG, "Opened URL in default browser: " + url);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening external URL: " + e.getMessage(), e);
        }
    }

    /**
     * Whether the device can prompt for fingerprint / device credential.
     * Called from JS: window.NativeAlarm.hasBiometric()
     */
    @JavascriptInterface
    public boolean hasBiometric() {
        try {
            BiometricManager manager = BiometricManager.from(context);
            int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.BIOMETRIC_WEAK;
            int result = manager.canAuthenticate(authenticators);
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            Log.e(TAG, "hasBiometric failed", e);
            return false;
        }
    }

    /**
     * Prompt the system biometric dialog, then call back into JS:
     * window.__msakuOnBiometric(requestId, ok, error)
     */
    @JavascriptInterface
    public void authenticateBiometric(String requestId) {
        if (!(context instanceof FragmentActivity)) {
            deliverBiometricResult(requestId, false, "Aktivitas native tidak tersedia");
            return;
        }
        final FragmentActivity activity = (FragmentActivity) context;
        activity.runOnUiThread(() -> {
            try {
                Executor executor = ContextCompat.getMainExecutor(activity);
                BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                                deliverBiometricResult(requestId, true, "");
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                                deliverBiometricResult(requestId, false, String.valueOf(errString));
                            }

                            @Override
                            public void onAuthenticationFailed() {
                                // Keep the prompt open; success/error will still fire.
                            }
                        });

                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Masuk ke MSAKU")
                        .setSubtitle("Gunakan sidik jari untuk masuk")
                        .setNegativeButtonText("Batal")
                        .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG
                                        | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build();
                prompt.authenticate(info);
            } catch (Exception e) {
                Log.e(TAG, "authenticateBiometric failed", e);
                deliverBiometricResult(requestId, false, e.getMessage() != null ? e.getMessage() : "Gagal membuka sidik jari");
            }
        });
    }

    private void deliverBiometricResult(String requestId, boolean ok, String error) {
        if (!(context instanceof MainActivity)) return;
        final MainActivity activity = (MainActivity) context;
        final String safeId = requestId == null ? "" : requestId.replace("\\", "\\\\").replace("'", "\\'");
        final String safeError = error == null ? "" : error.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        final String js = "(function(){try{if(window.__msakuOnBiometric){window.__msakuOnBiometric('"
                + safeId + "'," + (ok ? "true" : "false") + ",'" + safeError + "');}}catch(e){}})();";
        activity.runOnUiThread(() -> {
            WebView webView = activity.getBridge() != null ? activity.getBridge().getWebView() : null;
            if (webView != null) {
                webView.evaluateJavascript(js, null);
            }
        });
    }
}
