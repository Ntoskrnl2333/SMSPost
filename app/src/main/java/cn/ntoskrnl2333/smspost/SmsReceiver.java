package cn.ntoskrnl2333.smspost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * 短信接收器：只做"解析 + 入队"，不再直接发网络请求。
 *
 * 原实现把整个 intent 丢给前台服务，由服务解析并异步 POST；如果服务起不来
 * （Android 12+ 后台限制）或请求期间进程被杀，短信就丢了。
 * 现在：先落库（毫秒级），再尝试拉起服务补发，失败则排一个有网 Job。
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    /** 同一秒内可能重复投递，简单去重（跨进程重启后会重置）。 */
    private static String lastFingerprint = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SMS_RECEIVED.equals(intent.getAction())) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Intent received = intent;
        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                handle(app, received);
            } catch (Throwable t) {
                Log.e(TAG, "handle sms failed", t);
            } finally {
                pendingResult.finish();
            }
        }, "smspost-sms-receiver").start();
    }

    private void handle(Context context, Intent intent) {
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            return;
        }

        StringBuilder fullBody = new StringBuilder();
        for (SmsMessage message : messages) {
            if (message != null && message.getDisplayMessageBody() != null) {
                fullBody.append(message.getDisplayMessageBody());
            }
        }
        String sender = messages[0].getDisplayOriginatingAddress();
        long timestamp = messages[0].getTimestampMillis();
        String body = fullBody.toString();

        String fingerprint = sender + "|" + body + "|" + timestamp;
        synchronized (SmsReceiver.class) {
            if (fingerprint.equals(lastFingerprint)) {
                Log.d(TAG, "duplicate sms, skipped");
                return;
            }
            lastFingerprint = fingerprint;
        }

        Log.d(TAG, "SMS received from: " + sender);
        LogDatabaseHelper db = new LogDatabaseHelper(context);
        try {
            db.addPending(timestamp, sender, body);
        } finally {
            db.close();
        }

        if (KeepAliveManager.isUserStopped(context)) {
            Log.i(TAG, "forwarding stopped by user; sms queued for later");
        } else {
            boolean started = KeepAliveManager.ensureServiceRunning(context, "sms_received");
            if (!started) {
                KeepAliveManager.requestFlushJob(context);
            }
        }
        StatusWidgetProvider.refreshAll(context);
    }
}
