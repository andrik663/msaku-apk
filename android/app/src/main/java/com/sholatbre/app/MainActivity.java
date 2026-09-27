package com.sholatbre.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "SholatBre";
    private static final String PREFS_NAME = "permission_prefs";
    private static final String KEY_STEP = "permission_step";

    // Permission steps
    private static final int STEP_LOCATION = 0;
    private static final int STEP_NOTIFICATION = 1;
    private static final int STEP_CAMERA_MEDIA = 2;  // Camera + Media access
    private static final int STEP_BATTERY = 3;
    private static final int STEP_EXACT_ALARM = 4;
    private static final int STEP_DONE = 5;

    private static final int RC_LOCATION = 1001;
    private static final int RC_NOTIFICATION = 1002;
    private static final int RC_FILE_CHOOSER = 1003;
    private static final int RC_CAMERA_MEDIA = 1004;

    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest audioFocusRequest;
    private boolean isReturningFromSettings = false;
    private ValueCallback<Uri[]> fileUploadCallback;
    private Uri cameraPhotoUri;
    private Uri cameraVideoUri;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        // Default: light icons for initial dark theme (lightBackground=false)
        applyStatusBarStyle(false);

        // Acquire partial wake lock so the alarm checker keeps running
        acquireWakeLock();

        // Request audio focus for alarm playback
        requestAudioFocus();

        // Enable mixed content and media autoplay in WebView
        configureWebView();

        // Cold-start Google auth deep link (Chrome → app)
        handleGoogleAuthIntent(getIntent());
        handleDeeplinkIntent(getIntent());

        // Start or resume the permission chain with a small delay
        // so that Capacitor's BridgeActivity finishes its own init first
        new Handler(Looper.getMainLooper()).postDelayed(this::runPermissionStep, 800);
    }

    // ── Status bar appearance ──────────────────────────────────────────────

    /**
     * Update system status-bar and navigation-bar icon appearance.
     * lightBackground=true  → dark icons (visible on a light/white app background)
     * lightBackground=false → light icons (visible on a dark app background)
     * Called from NativeBridge.setStatusBarStyle() on theme change.
     */
    public void applyStatusBarStyle(boolean lightBackground) {
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(lightBackground);
        insetsController.setAppearanceLightNavigationBars(lightBackground);
    }

    // ── Permission step machine ──────────────────────────────────────────────

    private int getCurrentStep() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getInt(KEY_STEP, STEP_LOCATION);
    }

    private void advanceStep(int nextStep) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(KEY_STEP, nextStep).apply();
    }

    public void runPermissionStep() {
        int step = getCurrentStep();
        Log.d(TAG, "runPermissionStep: step=" + step);

        switch (step) {
            case STEP_LOCATION:
                doLocationPermission();
                break;
            case STEP_NOTIFICATION:
                doNotificationPermission();
                break;
            case STEP_CAMERA_MEDIA:
                doCameraMediaPermission();
                break;
            case STEP_BATTERY:
                doBatteryOptimization();
                break;
            case STEP_EXACT_ALARM:
                doExactAlarmPermission();
                break;
            case STEP_DONE:
            default:
                Log.d(TAG, "All permissions done");
                break;
        }
    }

    // ── Step 0: Location ─────────────────────────────────────────────────────

    private void doLocationPermission() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fine || !coarse) {
            Log.d(TAG, "Requesting location permission");
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    RC_LOCATION);
        } else {
            Log.d(TAG, "Location already granted, advancing");
            advanceStep(STEP_NOTIFICATION);
            runPermissionStep();
        }
    }

    // ── Step 1: Notification (Android 13+) ───────────────────────────────────

    private void doNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting notification permission");
                ActivityCompat.requestPermissions(this,
                        new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                        RC_NOTIFICATION);
                return;
            }
        }
        Log.d(TAG, "Notification already granted or not needed, advancing");
        advanceStep(STEP_CAMERA_MEDIA);
        runPermissionStep();
    }

    // ── Step 2: Camera + Media Storage (Android 6.0+ / 13+) ──────────────────

    private void doCameraMediaPermission() {
        java.util.List<String> permissionsNeeded = new java.util.ArrayList<>();

        // Camera permission
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }
        
        // Microphone permission (for video recording with audio)
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        // Media/Storage permissions - different for Android 13+ vs older
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular media permissions
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Android 12 and below uses READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.d(TAG, "Requesting camera, microphone, and media permissions: " + permissionsNeeded.size() + " permissions");
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    RC_CAMERA_MEDIA);
        } else {
            Log.d(TAG, "Camera, microphone, and media already granted, advancing");
            advanceStep(STEP_BATTERY);
            runPermissionStep();
        }
    }

    // ── Step 2: Battery optimization ─────────────────────────────────────────

    private void doBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Log.d(TAG, "Requesting battery optimization exemption");
                try {
                    isReturningFromSettings = true;
                    advanceStep(STEP_EXACT_ALARM); // advance BEFORE opening settings
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Battery optimization intent failed", e);
                }
            }
        }
        advanceStep(STEP_EXACT_ALARM);
        runPermissionStep();
    }

    // ── Step 3: Exact alarm (Android 12+) ────────────────────────────────────

    private void doExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                Log.d(TAG, "Requesting exact alarm permission");
                try {
                    isReturningFromSettings = true;
                    advanceStep(STEP_DONE); // advance BEFORE opening settings
                    Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Exact alarm intent failed", e);
                }
            }
        }
        advanceStep(STEP_DONE);
        Log.d(TAG, "All permission steps complete");
    }

    // ── Permission result handler ────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult: code=" + requestCode);

        // Use a small delay to ensure the dialog has fully dismissed
        // before showing the next permission dialog
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            switch (requestCode) {
                case RC_LOCATION:
                    advanceStep(STEP_NOTIFICATION);
                    runPermissionStep();
                    break;
                case RC_NOTIFICATION:
                    advanceStep(STEP_CAMERA_MEDIA);
                    runPermissionStep();
                    break;
                case RC_CAMERA_MEDIA:
                    advanceStep(STEP_BATTERY);
                    runPermissionStep();
                    break;
            }
        }, 500);
    }

    // When user returns from system Settings screens (battery, exact alarm)
    // or when "Matikan" notification button is pressed
    @Override
    public void onResume() {
        super.onResume();
        getSharedPreferences(InboxSyncReceiver.PREFS, MODE_PRIVATE)
                .edit().putBoolean("foreground", true).apply();
        if (isReturningFromSettings) {
            isReturningFromSettings = false;
            new Handler(Looper.getMainLooper()).postDelayed(this::runPermissionStep, 500);
        }
    }

    @Override
    public void onPause() {
        getSharedPreferences(InboxSyncReceiver.PREFS, MODE_PRIVATE)
                .edit().putBoolean("foreground", false).apply();
        InboxSyncReceiver.schedule(this, 20_000L);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == RC_FILE_CHOOSER) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK) {
                    // Check if result is from camera (photo)
                    if (cameraPhotoUri != null && (data == null || data.getData() == null)) {
                        // Camera photo was taken
                        results = new Uri[]{ cameraPhotoUri };
                        Log.d(TAG, "Camera photo captured: " + cameraPhotoUri);
                    }
                    // Check if result is from camera (video)
                    else if (data != null && data.getData() != null) {
                        // File from gallery or camera video
                        if (data.getClipData() != null) {
                            // Multiple files selected
                            int count = data.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = data.getClipData().getItemAt(i).getUri();
                            }
                            Log.d(TAG, "Multiple files selected: " + count);
                        } else {
                            results = new Uri[]{ data.getData() };
                            Log.d(TAG, "Single file selected: " + data.getData());
                        }
                    }
                } else {
                    Log.d(TAG, "File chooser cancelled or failed");
                }
                
                // Clear camera URIs
                cameraPhotoUri = null;
                cameraVideoUri = null;
                
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        }
    }
    
    // Helper method to create media files for camera
    private File createMediaFile(String prefix, String suffix) {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = prefix + timeStamp;
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (storageDir != null && !storageDir.exists()) {
                storageDir.mkdirs();
            }
            return File.createTempFile(fileName, suffix, storageDir);
        } catch (IOException e) {
            Log.e(TAG, "Error creating media file", e);
            return null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDismissAlarmIntent(intent);
        handleGoogleAuthIntent(intent);
        handleDeeplinkIntent(intent);
    }

    private void handleGoogleAuthIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        if (!"com.sholatbre.app".equals(data.getScheme()) || !"auth".equals(data.getHost())) return;

        String payload = data.getQueryParameter("payload");
        if (payload == null || payload.isEmpty()) return;

        final String encoded = payload.replace("\\", "\\\\").replace("'", "\\'");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                WebView webView = getBridge().getWebView();
                if (webView != null) {
                    webView.evaluateJavascript(
                            "(function(){try{window.dispatchEvent(new CustomEvent('msaku:google-auth',{detail:'" + encoded + "'}));}catch(e){}})();",
                            null
                    );
                    Log.d(TAG, "Dispatched msaku:google-auth to WebView");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to dispatch Google auth payload", e);
            }
        }, 400);
    }

    private void handleDeeplinkIntent(Intent intent) {
        if (intent == null) return;
        if (!"com.sholatbre.app.OPEN_DEEPLINK".equals(intent.getAction())) return;

        String deeplink = intent.getStringExtra("deeplink");
        if (deeplink == null || deeplink.isEmpty()) return;

        final String encoded = deeplink.replace("\\", "\\\\").replace("'", "\\'");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                WebView webView = getBridge().getWebView();
                if (webView != null) {
                    webView.evaluateJavascript(
                            "(function(){try{window.dispatchEvent(new CustomEvent('msaku:open-deeplink',{detail:'"
                                    + encoded + "'}));}catch(e){}})();",
                            null
                    );
                    Log.d(TAG, "Dispatched msaku:open-deeplink: " + deeplink);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to dispatch deeplink", e);
            }
        }, 400);
        intent.setAction(null);
    }

    private void handleDismissAlarmIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("com.sholatbre.app.DISMISS_ALARM".equals(action)) {
            Log.d(TAG, "DISMISS_ALARM intent received, stopping WebView audio");

            // Cancel the JS alarm notification
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(3003); // JS_ALARM_NOTIF_ID from NativeBridge
            }

            // Also stop native AlarmAudioService if it's running
            AlarmAudioService.stopService(this);

            // Evaluate JS in WebView to dismiss the alarm
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    WebView webView = getBridge().getWebView();
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('nativeStopAlarm'));",
                                null
                        );
                        Log.d(TAG, "Dispatched nativeStopAlarm event to WebView");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to evaluate JS for alarm dismiss", e);
                }
            }, 300);

            // Clear the intent action to prevent re-triggering
            intent.setAction(null);
        }
    }

    // ── Wake lock, audio focus, WebView ──────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SholatBre::AlarmWakeLock"
            );
            wakeLock.acquire(8 * 60 * 60 * 1000L);
        }
    }

    private void requestAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // Audio focus change handled by WebView audio
                    })
                    .build();

            audioManager.requestAudioFocus(audioFocusRequest);
        }
    }

    private void configureWebView() {
        try {
            WebView webView = getBridge().getWebView();
            if (webView != null) {
                // Enable hardware acceleration for video playback
                webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
                
                WebSettings settings = webView.getSettings();
                
                // Critical for video playback in WebView
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                
                // Enable caching for better video buffering
                settings.setCacheMode(WebSettings.LOAD_DEFAULT);
                // Note: setAppCacheEnabled is removed in API 33+, DOM storage is sufficient
                
                // Enable geolocation for navigation features
                settings.setGeolocationEnabled(true);
                
                // Allow file access for uploads
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setAllowFileAccessFromFileURLs(true);
                settings.setAllowUniversalAccessFromFileURLs(true);
                
                // Allow mixed content (HTTP on HTTPS) - required for some video CDNs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                }
                
                // Enable wide viewport for proper video scaling
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                
                // Set WebChromeClient with file chooser and geolocation support
                webView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onGeolocationPermissionsShowPrompt(String origin, 
                            android.webkit.GeolocationPermissions.Callback callback) {
                        Log.d(TAG, "Geolocation permission requested from: " + origin);
                        // Auto-grant geolocation permission for the app
                        callback.invoke(origin, true, false);
                    }

                    @Override
                    public boolean onShowFileChooser(WebView webView,
                            ValueCallback<Uri[]> filePathCallback,
                            FileChooserParams fileChooserParams) {
                        // Cancel any existing callback
                        if (fileUploadCallback != null) {
                            fileUploadCallback.onReceiveValue(null);
                        }
                        fileUploadCallback = filePathCallback;
                        cameraPhotoUri = null;
                        cameraVideoUri = null;

                        try {
                            String[] acceptTypes = fileChooserParams.getAcceptTypes();
                            boolean acceptsImage = false;
                            boolean acceptsVideo = false;
                            boolean acceptsAudio = false;

                            if (acceptTypes != null) {
                                for (String type : acceptTypes) {
                                    if (type != null) {
                                        if (type.startsWith("image") || type.contains("image")) acceptsImage = true;
                                        if (type.startsWith("video") || type.contains("video")) acceptsVideo = true;
                                        if (type.startsWith("audio") || type.contains("audio")) acceptsAudio = true;
                                    }
                                }
                            }
                            // If no specific type, accept all
                            if (!acceptsImage && !acceptsVideo && !acceptsAudio) {
                                acceptsImage = true;
                                acceptsVideo = true;
                            }

                            java.util.ArrayList<Intent> intentList = new java.util.ArrayList<>();

                            // Add camera photo intent if images are accepted and camera permission granted
                            if (acceptsImage && ContextCompat.checkSelfPermission(MainActivity.this,
                                    Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                                if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                                    File photoFile = createMediaFile("IMG_", ".jpg");
                                    if (photoFile != null) {
                                        cameraPhotoUri = FileProvider.getUriForFile(MainActivity.this,
                                                getPackageName() + ".fileprovider", photoFile);
                                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                                        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                        intentList.add(takePictureIntent);
                                    }
                                }
                            }

                            // Add camera video intent if videos are accepted and camera permission granted
                            if (acceptsVideo && ContextCompat.checkSelfPermission(MainActivity.this,
                                    Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                                if (takeVideoIntent.resolveActivity(getPackageManager()) != null) {
                                    // Limit video duration to 2 minutes for status, 10 seconds for avatar
                                    takeVideoIntent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 120);
                                    takeVideoIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
                                    intentList.add(takeVideoIntent);
                                }
                            }

                            // Create gallery/file picker intent
                            Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                            contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                            
                            if (acceptsImage && acceptsVideo) {
                                contentIntent.setType("*/*");
                                contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                            } else if (acceptsImage) {
                                contentIntent.setType("image/*");
                            } else if (acceptsVideo) {
                                contentIntent.setType("video/*");
                            } else if (acceptsAudio) {
                                contentIntent.setType("audio/*");
                            } else {
                                contentIntent.setType("*/*");
                            }

                            boolean allowMultiple = fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                            contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);

                            // Create chooser with camera options
                            Intent chooserIntent = Intent.createChooser(contentIntent, "Pilih Sumber");
                            if (!intentList.isEmpty()) {
                                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentList.toArray(new Intent[0]));
                            }

                            startActivityForResult(chooserIntent, RC_FILE_CHOOSER);
                            return true;

                        } catch (Exception e) {
                            Log.e(TAG, "File chooser failed", e);
                            fileUploadCallback = null;
                            return false;
                        }
                    }
                });

                // Register the JavaScript bridge for native alarm scheduling
                NativeBridge bridge = new NativeBridge(this);
                webView.addJavascriptInterface(bridge, "NativeAlarm");
                // Also register as NativeExternal for opening URLs in external browser
                webView.addJavascriptInterface(bridge, "NativeExternal");
                
                Log.d(TAG, "WebView configured with geolocation, file upload, and JS bridges (NativeAlarm, NativeExternal)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure WebView", e);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        }
        super.onDestroy();
    }
}
