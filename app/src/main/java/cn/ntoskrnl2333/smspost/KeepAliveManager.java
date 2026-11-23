package cn.ntoskrnl2333.smspost;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.TimeUnit;

/**
 * 保活调度中心（2.2.0 新增的几层，和已有的 WakeLock / 原生守护 / 3 分钟闹钟互补）。
 *
 * 已有的机制（保持不变）：
 *  - ForwardingService 里的 PARTIAL_WAKE_LOCK、WiFiLock
 *  - cpp/keep_alive.cpp 的 double-fork 原生守护
 *  - 每 3 分钟一次的心跳闹钟（{@link #scheduleHeartbeat(Context)}）
 *
 * 本类新增：
 *  1. JobScheduler 周期任务（15 分钟，persisted）—— 官方通道，重启后仍有效
 *  2. 心跳时间戳 + 存活判定 —— 不再"盲启动"，只有心跳过期才拉起
 *  3. 桌面小部件 30 分钟系统唤醒（见 StatusWidgetProvider）
 *  4. 统一的拉起入口与失败兜底（后台起 FGS 被拒时发一条可点按的恢复通知）
 */
public final class KeepAliveManager {

    private static final String TAG = "SmsPostKeepAlive";

    /** 与历史实现保持一致的广播 action，BootReceiver 已经监听它。 */
    public static final String ACTION_RESTART = "cn.ntoskrnl2333.smspost.RESTART_SERVICE";

    public static final int JOB_WATCHDOG = 2001;
    public static final int JOB_FLUSH = 2002;

    /** JobScheduler 允许的最小周期。 */
    public static final long JOB_PERIOD_MS = TimeUnit.MINUTES.toMillis(15);
    /** 沿用原有 3 分钟心跳间隔。 */
    public static final long HEARTBEAT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(3);
    /** 超过这个时间没有心跳就认为服务已死。 */
    public static final long HEARTBEAT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);
    /** 服务内写心跳的间隔。 */
    public static final long HEARTBEAT_WRITE_MS = TimeUnit.SECONDS.toMillis(60);

    private static final String PREF = "smspost_keepalive";
    private static final String KEY_BEAT_WALL = "beat_wall";
    private static final String KEY_BEAT_ELAPSED = "beat_elapsed";
    private static final String KEY_RESTARTS = "restart_count";
    private static final String KEY_USER_STOPPED = "user_stopped";

    private static final int REQUEST_HEARTBEAT = 0;
    private static final int REQUEST_REVIVE = 4242;
    private static final int REQUEST_RESCUE = 4243;

    private static final String CHANNEL_RESCUE = "smspost_rescue";
    private static final int NOTIFICATION_RESCUE = 2002;

    private KeepAliveManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 用户开关

    /** 用户主动停止后，所有系统入口都不再自动拉起服务。 */
    public static boolean isUserStopped(Context context) {
        return prefs(context).getBoolean(KEY_USER_STOPPED, false);
    }

    public static void setUserStopped(Context context, boolean stopped) {
        prefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).apply();
    }

    // ------------------------------------------------------------ 统一入口

    /** 幂等：任何系统入口命中时调用，补排任务并尝试拉起服务。 */
    public static void startAll(Context context) {
        if (context == null || isUserStopped(context)) {
            return;
        }
        Context app = context.getApplicationContext();
        scheduleWatchdogJob(app);
        ensureServiceRunning(app, "startAll");
        StatusWidgetProvider.refreshAll(app);
    }

    /** 用户主动停止：取消所有排期并标记停止。 */
    public static void stopAll(Context context) {
        Context app = context.getApplicationContext();
        setUserStopped(app, true);

        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(heartbeatPendingIntent(app));
            am.cancel(revivePendingIntent(app));
        }
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            js.cancel(JOB_WATCHDOG);
            js.cancel(JOB_FLUSH);
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_RESCUE);
        }
        prefs(app).edit().putLong(KEY_BEAT_ELAPSED, 0L).apply();
        StatusWidgetProvider.refreshAll(app);
        Log.i(TAG, "keep-alive stopped by user");
    }

    // ------------------------------------------------------------ JobScheduler

    public static void scheduleWatchdogJob(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        // 已在排程就不要重建：重复 schedule 会重置周期，导致任务永远跑不到。
        try {
            if (js.getPendingJob(JOB_WATCHDOG) != null) {
                return;
            }
        } catch (Exception ignored) {
            // 继续尝试 schedule
        }
        JobInfo job = new JobInfo.Builder(JOB_WATCHDOG,
                new ComponentName(context, KeepAliveJobService.class))
                .setPeriodic(JOB_PERIOD_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();
        try {
            js.schedule(job);
        } catch (Exception e) {
            Log.w(TAG, "schedule watchdog job failed", e);
        }
    }

    /** 有网时尽快补发积压短信。 */
    public static void requestFlushJob(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        try {
            if (js.getPendingJob(JOB_FLUSH) != null) {
                return;
            }
        } catch (Exception ignored) {
            // ignore
        }
        JobInfo job = new JobInfo.Builder(JOB_FLUSH,
                new ComponentName(context, KeepAliveJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(TimeUnit.MINUTES.toMillis(5))
                .build();
        try {
            js.schedule(job);
        } catch (Exception e) {
            Log.w(TAG, "schedule flush job failed", e);
        }
    }

    // ------------------------------------------------------------ AlarmManager

    /** 原有的 3 分钟心跳闹钟：由 BootReceiver 接收后重新拉起服务。 */
    public static void scheduleHeartbeat(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS;
        PendingIntent pi = heartbeatPendingIntent(context);
        try {
            if (canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.d(TAG, "heartbeat alarm exact");
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.d(TAG, "heartbeat alarm inexact");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "exact alarm not permitted, falling back", e);
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (Exception ignored) {
                // 前台服务仍然活着时不影响功能
            }
        } catch (Exception e) {
            Log.w(TAG, "schedule heartbeat failed", e);
        }
    }

    /** 一次性复活闹钟（服务被销毁 / 任务被划掉时兜底）。 */
    public static void scheduleRevive(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null || isUserStopped(context)) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        PendingIntent pi = revivePendingIntent(context);
        try {
            if (canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "schedule revive failed", e);
        }
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent heartbeatPendingIntent(Context context) {
        Intent intent = new Intent(context, BootReceiver.class).setAction(ACTION_RESTART);
        return PendingIntent.getBroadcast(context, REQUEST_HEARTBEAT, intent,
                pendingIntentFlags());
    }

    private static PendingIntent revivePendingIntent(Context context) {
        Intent intent = new Intent(context, BootReceiver.class).setAction(ACTION_RESTART);
        return PendingIntent.getBroadcast(context, REQUEST_REVIVE, intent,
                pendingIntentFlags());
    }

    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    // ------------------------------------------------------------ 存活判定 / 拉起

    /** 服务里调用：刷新心跳时间。 */
    public static void noteHeartbeat(Context context) {
        prefs(context).edit()
                .putLong(KEY_BEAT_WALL, System.currentTimeMillis())
                .putLong(KEY_BEAT_ELAPSED, SystemClock.elapsedRealtime())
                .apply();
    }

    public static long lastHeartbeatWall(Context context) {
        return prefs(context).getLong(KEY_BEAT_WALL, 0L);
    }

    public static boolean isServiceAlive(Context context) {
        long beat = prefs(context).getLong(KEY_BEAT_ELAPSED, 0L);
        if (beat <= 0L) {
            return false;
        }
        long age = SystemClock.elapsedRealtime() - beat;
        return age >= 0 && age < HEARTBEAT_TIMEOUT_MS;
    }

    public static int getRestartCount(Context context) {
        return prefs(context).getInt(KEY_RESTARTS, 0);
    }

    /**
     * 尝试拉起前台服务。Android 12+ 后台启动 FGS 受限，失败时退化为"可点按的恢复通知"
     * （用户点通知属于系统允许的启动场景）。
     */
    public static boolean ensureServiceRunning(Context context, String reason) {
        Context app = context.getApplicationContext();
        if (isUserStopped(app)) {
            Log.d(TAG, "user stopped, skip start (" + reason + ")");
            return false;
        }
        if (isServiceAlive(app)) {
            Log.d(TAG, "service alive, reason=" + reason);
            return true;
        }
        Intent intent = new Intent(app, ForwardingService.class)
                .setAction(ForwardingService.ACTION_KEEPALIVE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
            int count = getRestartCount(app) + 1;
            prefs(app).edit().putInt(KEY_RESTARTS, count).apply();
            Log.i(TAG, "startForegroundService ok, reason=" + reason + ", restarts=" + count);
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "FGS start blocked (reason=" + reason + "): " + e);
            postRescueNotification(app);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "start service failed (reason=" + reason + ")", e);
            postRescueNotification(app);
            return false;
        }
    }

    private static void postRescueNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_RESCUE,
                    context.getString(R.string.rescue_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        Intent restart = new Intent(context, ForwardingService.class)
                .setAction(ForwardingService.ACTION_KEEPALIVE);
        int flags = pendingIntentFlags();
        PendingIntent pi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(context, REQUEST_RESCUE, restart, flags)
                : PendingIntent.getService(context, REQUEST_RESCUE, restart, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_RESCUE)
                .setSmallIcon(R.drawable.ic_stat_smspost)
                .setContentTitle(context.getString(R.string.rescue_title))
                .setContentText(context.getString(R.string.rescue_content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFICATION_RESCUE, notification);
        } catch (Exception e) {
            Log.w(TAG, "post rescue notification failed", e);
        }
    }
}
