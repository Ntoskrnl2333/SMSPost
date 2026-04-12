package cn.ntoskrnl2333.smspost;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigManager {
    private static final String PREF_NAME = "smspost_config";
    private static final String KEY_URL = "post_url";
    private static final String KEY_USER = "auth_user";
    private static final String KEY_PASS = "auth_pass";

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveConfig(String url, String user, String pass) {
        prefs.edit()
                .putString(KEY_URL, url)
                .putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .apply();
    }

    public String getUrl() {
        return prefs.getString(KEY_URL, "");
    }

    public String getUser() {
        return prefs.getString(KEY_USER, "");
    }

    public String getPass() {
        return prefs.getString(KEY_PASS, "");
    }
}
