package cn.ntoskrnl2333.smspost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d("BootReceiver", "Received action: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_USER_PRESENT.equals(action) ||
            "cn.ntoskrnl2333.smspost.RESTART_SERVICE".equals(action)) {
            
            Log.d("BootReceiver", "Starting ForwardingService...");
            Intent serviceIntent = new Intent(context, ForwardingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
