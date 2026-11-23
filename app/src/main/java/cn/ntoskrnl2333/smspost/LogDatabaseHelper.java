package cn.ntoskrnl2333.smspost;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 投递日志 + 待发送队列（同一个 SQLite 表）。
 *
 * 2.2.0 的变化：
 *  - 不再把 "/storage/emulated/0/smspost_log.db" 当文件名交给 SQLiteOpenHelper
 *    （它只接受相对文件名，遇路径分隔符会抛 IllegalArgumentException，首次读写即崩）；
 *    改为直接 openOrCreateDatabase(File)：有"所有文件访问"权限时仍写外部文件，否则回退内部库。
 *  - 新增 queued / attempts 两列，短信先入队再由服务/Job 重试投递。
 */
public class LogDatabaseHelper {

    private static final String TAG = "SmsPostDb";
    private static final String DATABASE_NAME = "smspost_log.db";
    private static final int MAX_LOG_ROWS = 500;

    public static final String TABLE_LOGS = "sms_logs";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_SENDER = "sender";
    public static final String COLUMN_CONTENT = "content";
    public static final String COLUMN_STATUS = "status";
    public static final String COLUMN_QUEUED = "queued";
    public static final String COLUMN_ATTEMPTS = "attempts";

    /** 失败重试上限，超过后放弃，避免坏 URL 无限重试耗电。 */
    public static final int MAX_ATTEMPTS = 5;

    private static final String TABLE_CREATE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_LOGS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TIMESTAMP + " INTEGER, " +
                    COLUMN_SENDER + " TEXT, " +
                    COLUMN_CONTENT + " TEXT, " +
                    COLUMN_STATUS + " TEXT, " +
                    COLUMN_QUEUED + " INTEGER DEFAULT 0, " +
                    COLUMN_ATTEMPTS + " INTEGER DEFAULT 0);";

    private final Context context;
    private SQLiteDatabase db;

    public LogDatabaseHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    private synchronized SQLiteDatabase db() {
        if (db != null && db.isOpen()) {
            return db;
        }
        db = openDatabase();
        return db;
    }

    private SQLiteDatabase openDatabase() {
        // 1) 优先沿用旧版本写在外部存储的库文件（需要"所有文件访问"权限）
        try {
            if (Environment.isExternalStorageManager()) {
                File external = new File(Environment.getExternalStorageDirectory(), DATABASE_NAME);
                File parent = external.getParentFile();
                if (parent != null && (parent.exists() || parent.mkdirs())) {
                    SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(external, null);
                    initTables(database);
                    Log.i(TAG, "using external db: " + external);
                    return database;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "external db unavailable, fallback to internal", t);
        }
        // 2) 应用内部数据库（永远可用）
        File internal = context.getDatabasePath(DATABASE_NAME);
        File dir = internal.getParentFile();
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(internal, null);
        initTables(database);
        Log.i(TAG, "using internal db: " + internal);
        return database;
    }

    private void initTables(SQLiteDatabase database) {
        database.execSQL(TABLE_CREATE);
        // 兼容旧版本（只有 5 列）的库文件
        try {
            if (!hasColumn(database, TABLE_LOGS, COLUMN_QUEUED)) {
                database.execSQL("ALTER TABLE " + TABLE_LOGS + " ADD COLUMN " + COLUMN_QUEUED + " INTEGER DEFAULT 0");
            }
            if (!hasColumn(database, TABLE_LOGS, COLUMN_ATTEMPTS)) {
                database.execSQL("ALTER TABLE " + TABLE_LOGS + " ADD COLUMN " + COLUMN_ATTEMPTS + " INTEGER DEFAULT 0");
            }
        } catch (Throwable t) {
            Log.w(TAG, "migrate columns failed", t);
        }
    }

    private static boolean hasColumn(SQLiteDatabase database, String table, String column) {
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equalsIgnoreCase(cursor.getString(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 日志

    public synchronized void addLog(long timestamp, String sender, String content, String status) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_TIMESTAMP, timestamp);
        values.put(COLUMN_SENDER, sender);
        values.put(COLUMN_CONTENT, content);
        values.put(COLUMN_STATUS, status);
        values.put(COLUMN_QUEUED, 0);
        db().insert(TABLE_LOGS, null, values);
    }

    /**
     * 返回投递日志（最新在前）。读取原始列，UI 渲染结构化卡片。
     */
    public List<LogEntry> getLogEntries() {
        List<LogEntry> entries = new ArrayList<>();
        try (Cursor cursor = db().query(
                TABLE_LOGS,
                new String[]{COLUMN_TIMESTAMP, COLUMN_SENDER, COLUMN_CONTENT, COLUMN_STATUS},
                null, null, null, null,
                COLUMN_ID + " DESC",
                String.valueOf(MAX_LOG_ROWS))) {
            while (cursor.moveToNext()) {
                entries.add(new LogEntry(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3)));
            }
        }
        return entries;
    }

    // ------------------------------------------------------------ 待发送队列

    /** 收到短信后先入队（同步、极快），真正的 HTTP 发送交给前台服务 / Job。 */
    public synchronized long addPending(long timestamp, String sender, String content) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_TIMESTAMP, timestamp);
        values.put(COLUMN_SENDER, sender);
        values.put(COLUMN_CONTENT, content);
        values.put(COLUMN_STATUS, context.getString(R.string.status_pending));
        values.put(COLUMN_QUEUED, 1);
        values.put(COLUMN_ATTEMPTS, 0);
        return db().insert(TABLE_LOGS, null, values);
    }

    public synchronized List<PendingSms> getPending(int limit) {
        List<PendingSms> result = new ArrayList<>();
        String sql = "SELECT " + COLUMN_ID + ", " + COLUMN_TIMESTAMP + ", " + COLUMN_SENDER + ", "
                + COLUMN_CONTENT + " FROM " + TABLE_LOGS
                + " WHERE " + COLUMN_QUEUED + " = 1 ORDER BY " + COLUMN_ID + " ASC LIMIT ?";
        try (Cursor cursor = db().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                PendingSms sms = new PendingSms();
                sms.id = cursor.getLong(0);
                sms.timestamp = cursor.getLong(1);
                sms.sender = cursor.getString(2);
                sms.content = cursor.getString(3);
                result.add(sms);
            }
        }
        return result;
    }

    /** 回写结果：成功出队；失败累计次数，超过上限放弃。 */
    public synchronized void markResult(long id, boolean success, String status) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_STATUS, status);
        if (success) {
            values.put(COLUMN_QUEUED, 0);
        } else {
            int attempts = getAttempts(id) + 1;
            values.put(COLUMN_ATTEMPTS, attempts);
            if (attempts >= MAX_ATTEMPTS) {
                values.put(COLUMN_QUEUED, 0);
            }
        }
        db().update(TABLE_LOGS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    private int getAttempts(long id) {
        try (Cursor cursor = db().rawQuery("SELECT " + COLUMN_ATTEMPTS + " FROM " + TABLE_LOGS
                + " WHERE " + COLUMN_ID + " = ?", new String[]{String.valueOf(id)})) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }

    public synchronized void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    public static class PendingSms {
        public long id;
        public long timestamp;
        public String sender;
        public String content;
    }
}
