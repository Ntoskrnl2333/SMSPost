package cn.ntoskrnl2333.smspost;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 桌面小部件：转发状态展示 + 系统每 30 分钟的周期唤醒。
 *
 * 为什么它能保活：res/xml/widget_status_info.xml 里 updatePeriodMillis = 1800000
 * （Android 允许的最小值，30 分钟）。桌面存在本小部件时，系统会周期性地向这个
 * manifest 接收器发 APPWIDGET_UPDATE —— 即使进程已经被杀，系统也会创建进程来收广播，
 * onUpdate 里再调用 KeepAliveManager.startAll() 把服务和排期恢复起来。
 *
 * 点击走 PendingIntent.getForegroundService()：与 widget 交互属于 Android 12+
 * 允许后台启动前台服务的豁免场景，可以冷启动 ForwardingService。
 */
public class StatusWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "SmsPostWidget";

    private static final int REQUEST_WIDGET_START = 3001;
    private static final int REQUEST_WIDGET_OPEN = 3002;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.i(TAG, "onUpdate: " + appWidgetIds.length + " widget(s)");
        KeepAliveManager.startAll(context);
        updateAll(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // 系统只发 APPWIDGET_UPDATE；这里额外处理开机等场景下的主动刷新由调用方完成。
    }

    @Override
    public void onEnabled(Context context) {
        Log.i(TAG, "first widget added");
        KeepAliveManager.startAll(context);
    }

    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, StatusWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            updateAll(context, mgr, ids);
        }
    }

    private static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_status);

        boolean alive = KeepAliveManager.isServiceAlive(context);
        long beatWall = KeepAliveManager.lastHeartbeatWall(context);
        if (alive) {
            String time = beatWall > 0
                    ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(beatWall))
                    : "--:--";
            views.setTextViewText(R.id.widget_status,
                    context.getString(R.string.widget_status_alive, time));
        } else {
            views.setTextViewText(R.id.widget_status,
                    context.getString(R.string.widget_status_dead));
        }
        views.setTextViewText(R.id.widget_hint,
                context.getString(R.string.widget_restarts, KeepAliveManager.getRestartCount(context)));

        Intent start = new Intent(context, ForwardingService.class)
                .setAction(ForwardingService.ACTION_KEEPALIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent startPi = PendingIntent.getForegroundService(context, REQUEST_WIDGET_START, start, flags);
        views.setOnClickPendingIntent(R.id.widget_root, startPi);

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(context, REQUEST_WIDGET_OPEN, open, flags);
        views.setOnClickPendingIntent(R.id.widget_hint, openPi);

        return views;
    }
}
