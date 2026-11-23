package cn.ntoskrnl2333.smspost;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 短信投递器：从待发送队列取数据、同步 POST、回写结果。
 *
 * 2.2.0 新增。原来的实现是在服务里对单条短信发异步请求，失败只写一行日志，
 * 弱网/断网时短信就永久丢失了。现在队列 + 重试（最多 5 次）+ 有网 Job 补发，
 * 保证"被系统拉起一次"就能把积压的消息送出去。
 */
public final class SmsFlusher {

    private static final String TAG = "SmsPostFlusher";
    private static final int BATCH_SIZE = 20;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 单飞锁：服务、Job、小部件可能同时触发补发，避免同一批短信被重复发送。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private SmsFlusher() {
    }

    public static void flushAsync(Context context) {
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "flush already running, skip async");
            return;
        }
        new Thread(() -> {
            try {
                flushInternal(app);
            } catch (Throwable t) {
                Log.w(TAG, "flush failed", t);
            } finally {
                RUNNING.set(false);
            }
        }, "smspost-flush").start();
    }

    public static int flushBlocking(Context context) {
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "flush already running, skip blocking");
            return 0;
        }
        try {
            return flushInternal(context);
        } finally {
            RUNNING.set(false);
        }
    }

    private static int flushInternal(Context context) {
        ConfigManager config = new ConfigManager(context);
        String url = config.getUrl();
        if (url == null || url.trim().isEmpty()) {
            Log.d(TAG, "url not configured, skip flush");
            return 0;
        }

        LogDatabaseHelper db = new LogDatabaseHelper(context);
        List<LogDatabaseHelper.PendingSms> pending = db.getPending(BATCH_SIZE);
        if (pending.isEmpty()) {
            return 0;
        }
        Log.i(TAG, "flushing " + pending.size() + " pending sms");

        int handled = 0;
        for (LogDatabaseHelper.PendingSms sms : pending) {
            Delivery delivery = postOne(context, config, url, sms);
            db.markResult(sms.id, delivery.ok, delivery.status);
            handled++;
        }
        return handled;
    }

    private static Delivery postOne(Context context, ConfigManager config, String url,
                                    LogDatabaseHelper.PendingSms sms) {
        JSONObject json = new JSONObject();
        try {
            json.put("sender", sms.sender);
            json.put("content", sms.content);
            json.put("timestamp", sms.timestamp);
        } catch (JSONException e) {
            return new Delivery(false,
                    context.getString(R.string.status_failed, String.valueOf(e.getMessage())));
        }

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request.Builder builder = new Request.Builder().url(url).post(body);
        String user = config.getUser();
        String pass = config.getPass();
        if (user != null && !user.isEmpty()) {
            builder.header("Authorization", Credentials.basic(user, pass));
        }

        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            if (response.isSuccessful()) {
                return new Delivery(true, context.getString(R.string.status_success));
            }
            return new Delivery(false,
                    context.getString(R.string.status_failed_code, response.code()));
        } catch (IOException e) {
            Log.e(TAG, "post failed for sms " + sms.id, e);
            return new Delivery(false,
                    context.getString(R.string.status_failed, String.valueOf(e.getMessage())));
        }
    }

    private static final class Delivery {
        final boolean ok;
        final String status;

        Delivery(boolean ok, String status) {
            this.ok = ok;
            this.status = status;
        }
    }
}
