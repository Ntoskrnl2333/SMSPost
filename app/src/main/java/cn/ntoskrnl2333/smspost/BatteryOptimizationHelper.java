package cn.ntoskrnl2333.smspost;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 电池优化白名单 / 厂商自启动页 / 精确闹钟设置 的引导。
 *
 * 这是唯一能真正提升存活率、又不违反平台规则的合法手段：
 * 用户关闭电池优化后，Android 12+ 对"后台启动前台服务"的限制会对本应用放开。
 */
public final class BatteryOptimizationHelper {

    private static final String TAG = "SmsPostBattery";

    private BatteryOptimizationHelper() {
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean requestIgnoreBatteryOptimizations(Activity activity) {
        if (isIgnoringBatteryOptimizations(activity)) {
            return false;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "request ignore battery optimization failed", e);
            return openAppDetails(activity);
        }
    }

    public static void openExactAlarmSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "open exact alarm settings failed", e);
            }
        }
        openAppDetails(activity);
    }

    /** 按厂商打开"自启动/后台管理"页面，找不到就退回应用详情页。 */
    public static void openAutoStartSettings(Activity activity) {
        ComponentName[] candidates = new ComponentName[]{
                new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                new ComponentName("com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                new ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                new ComponentName("com.meizu.safe",
                        "com.meizu.safe.permission.SmartBGActivity"),
                new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"),
                new ComponentName("com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        };
        for (ComponentName cn : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(cn);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // 该 ROM 没有这个页面，继续试下一个
            } catch (Exception e) {
                Log.w(TAG, "open " + cn.flattenToShortString() + " failed", e);
            }
        }
        openAppDetails(activity);
    }

    private static boolean openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open app details failed", e);
            return false;
        }
    }

    public static String romLabel() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String s = manufacturer + " " + brand;
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) {
            return "MIUI / HyperOS";
        }
        if (s.contains("huawei") || s.contains("honor")) {
            return "EMUI / MagicOS";
        }
        if (s.contains("oppo") || s.contains("oneplus") || s.contains("realme")) {
            return "ColorOS";
        }
        if (s.contains("vivo") || s.contains("iqoo")) {
            return "OriginOS";
        }
        if (s.contains("meizu")) {
            return "Flyme";
        }
        if (s.contains("samsung")) {
            return "One UI";
        }
        return Build.MANUFACTURER;
    }
}
