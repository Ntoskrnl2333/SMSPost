package cn.ntoskrnl2333.smspost;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ForwardingService extends Service {
    private static final String TAG = "ForwardingService";
    private static final String CHANNEL_ID = "SMSForwardingChannelV5";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_SEND_SMS = "cn.ntoskrnl2333.smspost.SEND_SMS";
    private static final String ACTION_RESTART = "cn.ntoskrnl2333.smspost.RESTART_SERVICE";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private LogDatabaseHelper dbHelper;
    private ConfigManager configManager;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build();

    static {
        System.loadLibrary("keep_alive");
    }

    private native void initNativeDaemon(String lockPath, String packageName);

    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        dbHelper = new LogDatabaseHelper(this);
        configManager = new ConfigManager(this);
        createNotificationChannel();
        
        // 1. CPU WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMSPost::ForwardingServiceWakeLock");
            wakeLock.acquire();
        }

        // 2. WiFi Lock
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SMSPost::WifiLock");
            wifiLock.acquire();
        }

        // 3. Native Daemon
        String lockFile = getFilesDir().getAbsolutePath() + "/.daemon_lock";
        initNativeDaemon(lockFile, getPackageName());

        scheduleHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        
        startForeground(NOTIFICATION_ID, getNotification());

        if (intent != null && ACTION_SEND_SMS.equals(intent.getAction())) {
            handleSmsIntent(intent);
        }

        scheduleHeartbeat();
        return START_STICKY;
    }

    private static String lastSmsFingerprint = ""; // 用于简单的去重

    private void handleSmsIntent(Intent intent) {
        // 1. 使用系统 API 获取合并后的短信对象数组
        android.telephony.SmsMessage[] messages = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        // 2. 将所有分段合并成完整内容
        StringBuilder fullBody = new StringBuilder();
        for (android.telephony.SmsMessage msg : messages) {
            if (msg != null && msg.getDisplayMessageBody() != null) {
                fullBody.append(msg.getDisplayMessageBody());
            }
        }

        String sender = messages[0].getDisplayOriginatingAddress();
        long timestamp = messages[0].getTimestampMillis();
        String body = fullBody.toString();

        // 3. 简单的去重逻辑：如果发送者、内容和时间戳与上一条完全一致，则跳过
        String fingerprint = sender + "|" + body + "|" + timestamp;
        if (fingerprint.equals(lastSmsFingerprint)) {
            Log.d(TAG, "检测到重复短信，已跳过处理");
            return;
        }
        lastSmsFingerprint = fingerprint;

        // 4. 发送到服务器
        postSmsToServer(sender, body, timestamp);
    }

    private void postSmsToServer(String sender, String content, long timestamp) {
        String url = configManager.getUrl();
        if (url == null || url.isEmpty()) {
            dbHelper.addLog(timestamp, sender, content, getString(R.string.status_url_not_set));
            return;
        }

        JSONObject json = new JSONObject();
        try {
            json.put("sender", sender);
            json.put("content", content);
            json.put("timestamp", timestamp);
        } catch (JSONException e) {
            Log.e(TAG, "JSON creation error", e);
        }

        RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);

        String user = configManager.getUser();
        if (user != null && !user.isEmpty()) {
            requestBuilder.header("Authorization", Credentials.basic(user, configManager.getPass()));
        }

        client.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                dbHelper.addLog(timestamp, sender, content, getString(R.string.status_failed, e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String status = response.isSuccessful() ? getString(R.string.status_success) : getString(R.string.status_failed_code, response.code());
                dbHelper.addLog(timestamp, sender, content, status);
                response.close();
            }
        });
    }

    private Notification getNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_content))
                .setSmallIcon(smallIconRes())
                .setColor(getColor(R.color.notification_accent))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * HONOR/MagicOS renders notification small icons in full colour, so there the launcher
     * artwork is used and the shade icon looks exactly like the desktop one. Stock Android
     * keeps only the alpha channel and tints it white, so other devices get the monochrome
     * status icon instead of a solid white square.
     */
    private int smallIconRes() {
        String manufacturer = Build.MANUFACTURER == null
                ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        boolean rendersColour = manufacturer.contains("honor") || manufacturer.contains("huawei");
        return rendersColour ? R.drawable.ic_stat_smspost_color : R.drawable.ic_stat_smspost;
    }

    private void scheduleHeartbeat() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(this, BootReceiver.class);
        intent.setAction(ACTION_RESTART);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        long interval = 3 * 60 * 1000; // 3 minutes
        long triggerAtMillis = System.currentTimeMillis() + interval;

        // Android 12+ may deny SCHEDULE_EXACT_ALARM (Android 13+ never grants it by
        // default), in which case setExact* throws SecurityException and would crash
        // the service. Fall back to an inexact idle-tolerant alarm instead.
        boolean canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarmManager.canScheduleExactAlarms();
        try {
            if (canUseExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm not permitted, using inexact heartbeat", e);
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } catch (Exception ignored) {
                // Nothing else we can do here; the foreground service keeps the process alive.
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();
        
        Intent restartIntent = new Intent(ACTION_RESTART);
        sendBroadcast(restartIntent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed, restarting...");
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartServiceIntent);
        } else {
            startService(restartServiceIntent);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    // DEFAULT keeps the persistent status-bar icon without the heads-up
                    // banner that HIGH produced on every service start.
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            serviceChannel.setDescription(getString(R.string.notif_content));
            serviceChannel.enableVibration(false);
            serviceChannel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
