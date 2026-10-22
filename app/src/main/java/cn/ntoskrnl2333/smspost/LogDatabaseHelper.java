package cn.ntoskrnl2333.smspost;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class LogDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "/storage/emulated/0/smspost_log.db";
    private static final int DATABASE_VERSION = 1;
    private static final int MAX_LOG_ROWS = 500;

    public static final String TABLE_LOGS = "sms_logs";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_SENDER = "sender";
    public static final String COLUMN_CONTENT = "content";
    public static final String COLUMN_STATUS = "status";

    private static final String TABLE_CREATE =
            "CREATE TABLE " + TABLE_LOGS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TIMESTAMP + " INTEGER, " +
                    COLUMN_SENDER + " TEXT, " +
                    COLUMN_CONTENT + " TEXT, " +
                    COLUMN_STATUS + " TEXT);";

    public LogDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(TABLE_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_LOGS);
        onCreate(db);
    }

    public synchronized void addLog(long timestamp, String sender, String content, String status) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TIMESTAMP, timestamp);
        values.put(COLUMN_SENDER, sender);
        values.put(COLUMN_CONTENT, content);
        values.put(COLUMN_STATUS, status);
        db.insert(TABLE_LOGS, null, values);
    }

    /**
     * Returns the delivery log, newest first. Reads raw columns so the UI can render
     * structured cards instead of a single formatted string.
     */
    public List<LogEntry> getLogEntries() {
        List<LogEntry> entries = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_LOGS,
                new String[]{COLUMN_TIMESTAMP, COLUMN_SENDER, COLUMN_CONTENT, COLUMN_STATUS},
                null, null, null, null,
                COLUMN_ID + " DESC",
                String.valueOf(MAX_LOG_ROWS));

        try {
            while (cursor.moveToNext()) {
                long timestamp = cursor.getLong(0);
                String sender = cursor.getString(1);
                String content = cursor.getString(2);
                String status = cursor.getString(3);
                entries.add(new LogEntry(timestamp, sender, content, status));
            }
        } finally {
            cursor.close();
        }
        return entries;
    }
}
