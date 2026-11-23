package cn.ntoskrnl2333.smspost;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 所有系统入口的统一接收器：
 *  - BOOT_COMPLETED / QUICKBOOT_POWERON：开机
 *  - USER_PRESENT：解锁
 *  - 3 分钟心跳闹钟（自定义 action RESTART_SERVICE）
 *  - MY_PACKAGE_REPLACED：应用更新后
 *  - TIME_SET / TIMEZONE_CHANGED：系统时间变化（厂商常顺手清理定时器）
 *  - SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED：精确闹钟权限变化
 *
 * 收到后统一交给 KeepAliveManager：补排 Job、按心跳判断是否需要拉起服务、刷新小部件。
 * 用户主动停止后所有入口都会静默返回，避免"停不掉"。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.d(TAG, "Received action: " + action);

        if (KeepAliveManager.isUserStopped(context)) {
            Log.d(TAG, "forwarding stopped by user, ignore " + action);
            return;
        }

        boolean wakeUp = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || KeepAliveManager.ACTION_RESTART.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);

        if (!wakeUp) {
            return;
        }

        // 补排周期 Job + 小部件，并做一次心跳判定后的拉起。
        KeepAliveManager.startAll(context);

        boolean coldBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (coldBoot) {
            // 开机/更新后把积压短信补发掉。
            KeepAliveManager.requestFlushJob(context);
        }
    }
}
