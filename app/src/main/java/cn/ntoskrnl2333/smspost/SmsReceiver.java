package cn.ntoskrnl2333.smspost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null && intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            Log.d(TAG, "SMS received, forwarding to Service...");
            
            Intent serviceIntent = new Intent(context, ForwardingService.class);
            serviceIntent.setAction(ForwardingService.ACTION_SEND_SMS);
            serviceIntent.putExtras(intent.getExtras());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
