package cn.ntoskrnl2333.smspost;

import android.annotation.SuppressLint;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * 常驻前台服务：持有 WakeLock / WiFiLock、原生守护、3 分钟心跳，并负责补发队列。
 *
 * 2.2.0 的改动：
 *  - Android 14+ 改用 specialUse 前台服务类型（dataSync 在 Android 15 有 6h/24h 配额）
 *  - 心跳时间戳写入 SharedPreferences，配合同步的存活判定
 *  - 启动时补发积压短信（队列由 SmsReceiver 写入）
 *  - 通知栏增加"停止"操作；停止时取消所有保活排期并标记用户主动停止
 *  - onTaskRemoved / onDestroy 通过一次性闹钟兜底复活
 */
public class ForwardingService extends Service {

    private static final String TAG = "ForwardingService";
    private static final String CHANNEL_ID = "SMSForwardingChannelV5";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_KEEPALIVE = "cn.ntoskrnl2333.smspost.KEEPALIVE";
    public static final String ACTION_FLUSH = "cn.ntoskrnl2333.smspost.FLUSH";
    public static final String ACTION_STOP = "cn.ntoskrnl2333.smspost.STOP";

    private static final int REQUEST_NOTIFICATION_OPEN = 100;
    private static final int REQUEST_NOTIFICATION_STOP = 101;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            KeepAliveManager.noteHeartbeat(ForwardingService.this);
            StatusWidgetProvider.refreshAll(ForwardingService.this);
            handler.postDelayed(this, KeepAliveManager.HEARTBEAT_WRITE_MS);
        }
    };

    static {
        System.loadLibrary("keep_alive");
    }

    private native void initNativeDaemon(String lockPath, String packageName);

    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        createNotificationChannel();

        // 1. CPU WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "SMSPost::ForwardingServiceWakeLock");
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

        KeepAliveManager.noteHeartbeat(this);
        KeepAliveManager.scheduleHeartbeat(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.d(TAG, "Service onStartCommand, action=" + action);

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "stop requested by user");
            KeepAliveManager.stopAll(this);
            handler.removeCallbacks(heartbeatTask);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification());

        KeepAliveManager.noteHeartbeat(this);
        KeepAliveManager.scheduleHeartbeat(this);
        KeepAliveManager.scheduleWatchdogJob(this);
        SmsFlusher.flushAsync(this);
        StatusWidgetProvider.refreshAll(this);

        handler.removeCallbacks(heartbeatTask);
        handler.post(heartbeatTask);

        return START_STICKY;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+：specialUse 不受 dataSync 的 6h/24h 配额限制
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, REQUEST_NOTIFICATION_OPEN,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ForwardingService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, REQUEST_NOTIFICATION_STOP,
                stopIntent, PendingIntent.FLAG_IMMUTABLE);

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
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, getString(R.string.notif_action_stop), stopPendingIntent)
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

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        handler.removeCallbacks(heartbeatTask);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();

        // 一次性闹钟兜底：进程若在此后被杀，仍有机会被系统唤醒。
        KeepAliveManager.scheduleRevive(this, 15_000L);
        Intent restartIntent = new Intent(KeepAliveManager.ACTION_RESTART);
        restartIntent.setPackage(getPackageName());
        sendBroadcast(restartIntent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed, restarting...");
        KeepAliveManager.scheduleRevive(this, 5_000L);
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass())
                .setAction(ACTION_KEEPALIVE);
        restartServiceIntent.setPackage(getPackageName());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartServiceIntent);
            } else {
                startService(restartServiceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "restart after task removal failed", e);
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
