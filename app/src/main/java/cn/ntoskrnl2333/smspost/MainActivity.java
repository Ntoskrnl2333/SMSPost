package cn.ntoskrnl2333.smspost;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int STORAGE_PERMISSION_CODE = 102;
    private LogDatabaseHelper dbHelper;
    private ConfigManager configManager;
    private TextView tvLogs;
    private EditText etUrl, etUser, etPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        dbHelper = new LogDatabaseHelper(this);
        configManager = new ConfigManager(this);

        etUrl = findViewById(R.id.et_url);
        etUser = findViewById(R.id.et_user);
        etPass = findViewById(R.id.et_pass);
        tvLogs = findViewById(R.id.tv_logs);
        
        Button btnSave = findViewById(R.id.btn_save);
        Button btnRefresh = findViewById(R.id.btn_refresh);

        // Load existing config
        etUrl.setText(configManager.getUrl());
        etUser.setText(configManager.getUser());
        etPass.setText(configManager.getPass());

        btnSave.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String user = etUser.getText().toString().trim();
            String pass = etPass.getText().toString().trim();
            configManager.saveConfig(url, user, pass);
            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show();
        });

        btnRefresh.setOnClickListener(v -> refreshLogs());

        if (!hasSmsPermission() || !hasNotificationPermission() || !hasStoragePermission()) {
            requestPermissions();
        } else {
            startSmsService();
            refreshLogs();
        }
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_REQUEST_CODE);
            
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivityForResult(intent, STORAGE_PERMISSION_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, STORAGE_PERMISSION_CODE);
                }
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void refreshLogs() {
        List<String> logs = dbHelper.getAllLogs();
        StringBuilder sb = new StringBuilder();
        for (String log : logs) {
            sb.append(log).append("\n\n---\n\n");
        }
        if (sb.length() == 0) {
            tvLogs.setText(R.string.empty_logs);
        } else {
            tvLogs.setText(sb.toString());
        }
    }

    private void startSmsService() {
        Intent serviceIntent = new Intent(this, ForwardingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show();
                startSmsService();
            } else {
                Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}
