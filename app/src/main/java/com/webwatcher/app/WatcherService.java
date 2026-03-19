package com.webwatcher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WatcherService extends Service {

    private static final String TAG = "WatcherService";
    private static final String TARGET_URL = "https://06c7986a-71b1-420c-b544-33e265b64739-00-17v9tmtd8zwc7.janeway.replit.dev/";

    private static final String CHANNEL_ID_FOREGROUND = "watcher_foreground";
    private static final String CHANNEL_ID_ALERT = "watcher_alert";
    private static final int FOREGROUND_NOTIF_ID = 1;
    private static final int ALERT_NOTIF_ID = 2;

    private static final String PREFS_NAME = "WebWatcherPrefs";
    private static final String KEY_LAST_HASH = "last_content_hash";
    private static final String KEY_CHECK_INTERVAL = "check_interval_minutes";

    private Handler handler;
    private ExecutorService executor;
    private Runnable checkRunnable;
    private boolean isRunning = false;

    public static int getCheckInterval(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getInt(KEY_CHECK_INTERVAL, 5);
    }

    public static void setCheckInterval(Context ctx, int minutes) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(KEY_CHECK_INTERVAL, minutes).apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification());
        if (!isRunning) {
            isRunning = true;
            scheduleNextCheck(0);
        }
        return START_STICKY;
    }

    private void scheduleNextCheck(long delayMs) {
        checkRunnable = () -> executor.execute(this::checkForChanges);
        handler.postDelayed(checkRunnable, delayMs);
    }

    private void checkForChanges() {
        try {
            String content = fetchPageContent(TARGET_URL);
            if (content == null) {
                Log.w(TAG, "Failed to fetch content");
                scheduleNextCheckOnMain();
                return;
            }

            String newHash = md5(content);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String lastHash = prefs.getString(KEY_LAST_HASH, null);

            if (lastHash == null) {
                // First run - just save the hash
                prefs.edit().putString(KEY_LAST_HASH, newHash).apply();
                Log.d(TAG, "First check complete. Hash saved.");
            } else if (!lastHash.equals(newHash)) {
                // Content changed!
                prefs.edit().putString(KEY_LAST_HASH, newHash).apply();
                Log.d(TAG, "Change detected! Sending notification.");
                handler.post(this::sendChangeNotification);
            } else {
                Log.d(TAG, "No changes detected.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error checking for changes", e);
        }

        scheduleNextCheckOnMain();
    }

    private void scheduleNextCheckOnMain() {
        handler.post(() -> {
            int intervalMinutes = getCheckInterval(this);
            long delayMs = intervalMinutes * 60 * 1000L;
            scheduleNextCheck(delayMs);
        });
    }

    private String fetchPageContent(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 WebWatcherApp/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP error: " + responseCode);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "Fetch error", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }

    private void sendChangeNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🔔 사이트 변경 감지!")
                .setContentText("모니터링 중인 사이트에 변화가 있습니다. 탭하여 확인하세요.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("모니터링 중인 사이트의 콘텐츠가 변경되었습니다.\n탭하여 앱에서 확인하세요."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(new long[]{0, 500, 200, 500});

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(ALERT_NOTIF_ID, builder.build());
        }
    }

    private Notification buildForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("WebWatcher 실행 중")
                .setContentText("사이트 변경을 감시하고 있습니다.")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Foreground service channel (silent)
            NotificationChannel foreground = new NotificationChannel(
                    CHANNEL_ID_FOREGROUND,
                    "WebWatcher 백그라운드 서비스",
                    NotificationManager.IMPORTANCE_MIN);
            foreground.setDescription("앱이 백그라운드에서 실행 중임을 알려주는 알림");
            foreground.setShowBadge(false);
            nm.createNotificationChannel(foreground);

            // Alert channel (high priority with sound)
            NotificationChannel alert = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    "사이트 변경 알림",
                    NotificationManager.IMPORTANCE_HIGH);
            alert.setDescription("사이트에 변화가 감지되었을 때 알림");
            alert.enableVibration(true);
            alert.setVibrationPattern(new long[]{0, 500, 200, 500});
            nm.createNotificationChannel(alert);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (handler != null && checkRunnable != null) {
            handler.removeCallbacks(checkRunnable);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
