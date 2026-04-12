package cn.ntoskrnl2333.smspost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null && intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    LogDatabaseHelper dbHelper = new LogDatabaseHelper(context);
                    ConfigManager configManager = new ConfigManager(context);
                    
                    for (Object pdu : pdus) {
                        SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu, bundle.getString("format"));
                        String sender = smsMessage.getDisplayOriginatingAddress();
                        String messageBody = smsMessage.getMessageBody();
                        long timestamp = smsMessage.getTimestampMillis();

                        Log.d(TAG, "SMS received from: " + sender);
                        postSmsToServer(context, dbHelper, configManager, sender, messageBody, timestamp);
                    }
                }
            }
        }
    }

    private void postSmsToServer(Context context, LogDatabaseHelper dbHelper, ConfigManager config, String sender, String content, long timestamp) {
        String url = config.getUrl();
        if (url == null || url.isEmpty()) {
            dbHelper.addLog(timestamp, sender, content, context.getString(R.string.status_url_not_set));
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

        RequestBody body = RequestBody.create(json.toString(), JSON);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);

        String user = config.getUser();
        String pass = config.getPass();

        if (user != null && !user.isEmpty()) {
            requestBuilder.header("Authorization", Credentials.basic(user, pass));
        }

        client.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to post SMS", e);
                dbHelper.addLog(timestamp, sender, content, context.getString(R.string.status_failed, e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String status;
                if (response.isSuccessful()) {
                    status = context.getString(R.string.status_success);
                } else {
                    status = context.getString(R.string.status_failed_code, response.code());
                }

                String ispasswd;
                if(pass == null)
                    ispasswd = "(null)";
                else if(pass.isEmpty())
                    ispasswd = "(empty)";
                else if(pass.isBlank())
                    ispasswd = "(whitespaces)";
                else
                    ispasswd="(exist)";

                String targetLog = "url:" + url + ", user:" + user + ", pass:" + ispasswd;
                Log.d(TAG, targetLog);

                dbHelper.addLog(timestamp, sender, content, status + ", " + targetLog);
                response.close();
            }
        });
    }
}
