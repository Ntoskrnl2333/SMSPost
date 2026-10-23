package cn.ntoskrnl2333.smspost;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int STORAGE_PERMISSION_CODE = 102;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 103;

    private LogDatabaseHelper dbHelper;
    private ConfigManager configManager;
    private MainAdapter adapter;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private View root;
    private View statusScrim;
    private RecyclerView rvMain;
    private View headerView;
    private View permissionBanner;
    private View emptyState;
    private View statusDot;
    private View authFields;
    private TextView tvHeroStatus;
    private TextView tvLogCount;
    private TextView tvEmptyTitle;
    private TextView tvEmptyBody;
    private TextView tvBadgeRequired;
    private ImageButton btnRefresh;
    private TextInputLayout tilUrl;
    private TextInputEditText etUrl;
    private TextInputEditText etUser;
    private TextInputEditText etPass;
    private MaterialSwitch switchAuth;

    private boolean serviceStartAttempted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        dbHelper = new LogDatabaseHelper(this);
        configManager = new ConfigManager(this);

        root = findViewById(R.id.root);
        statusScrim = findViewById(R.id.status_scrim);
        rvMain = findViewById(R.id.rv_main);

        // The header item is inflated against the RecyclerView, so a LayoutManager
        // must exist before inflation (RecyclerView#generateLayoutParams throws otherwise).
        rvMain.setLayoutManager(new LinearLayoutManager(this));

        applyWindowInsets();
        applySystemBarAppearance();

        headerView = getLayoutInflater().inflate(R.layout.header_main, rvMain, false);
        bindHeaderViews();

        adapter = new MainAdapter(this, headerView);
        rvMain.setAdapter(adapter);

        populateConfig();
        wireInteractions();

        checkAndRequestPermissions();
        updateStatus();
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            int left = Math.max(bars.left, cutout.left);
            int top = Math.max(bars.top, cutout.top);
            int right = Math.max(bars.right, cutout.right);
            int bottom = Math.max(bars.bottom, ime.bottom)
                    + getResources().getDimensionPixelSize(R.dimen.space_xxl);

            ViewGroupParamsHelper.setHeight(statusScrim, top);
            if (rvMain.getPaddingLeft() != left
                    || rvMain.getPaddingTop() != top
                    || rvMain.getPaddingRight() != right
                    || rvMain.getPaddingBottom() != bottom) {
                rvMain.setPadding(left, top, right, bottom);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * Small helper so the inset listener does not allocate layout params on every pass.
     */
    private static final class ViewGroupParamsHelper {
        static void setHeight(View view, int height) {
            if (view.getLayoutParams().height != height) {
                view.getLayoutParams().height = height;
                view.requestLayout();
            }
        }
    }

    private void applySystemBarAppearance() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        // The status bar always sits on the navy scrim, so its icons stay light.
        controller.setAppearanceLightStatusBars(false);
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        controller.setAppearanceLightNavigationBars(!night);
    }

    private void bindHeaderViews() {
        statusDot = headerView.findViewById(R.id.status_dot);
        tvHeroStatus = headerView.findViewById(R.id.tv_hero_status);
        tvLogCount = headerView.findViewById(R.id.tv_log_count);
        tvEmptyTitle = headerView.findViewById(R.id.tv_empty_title);
        tvEmptyBody = headerView.findViewById(R.id.tv_empty_body);
        tvBadgeRequired = headerView.findViewById(R.id.tv_badge_required);
        btnRefresh = headerView.findViewById(R.id.btn_refresh);
        permissionBanner = headerView.findViewById(R.id.permission_banner);
        emptyState = headerView.findViewById(R.id.empty_state);
        authFields = headerView.findViewById(R.id.auth_fields);
        tilUrl = headerView.findViewById(R.id.til_url);
        etUrl = headerView.findViewById(R.id.et_url);
        etUser = headerView.findViewById(R.id.et_user);
        etPass = headerView.findViewById(R.id.et_pass);
        switchAuth = headerView.findViewById(R.id.switch_auth);
    }

    private void populateConfig() {
        etUrl.setText(configManager.getUrl());
        etUser.setText(configManager.getUser());
        etPass.setText(configManager.getPass());

        boolean hasAuth = !configManager.getUser().isEmpty() || !configManager.getPass().isEmpty();
        switchAuth.setChecked(hasAuth);
        authFields.setVisibility(hasAuth ? View.VISIBLE : View.GONE);
    }

    private void wireInteractions() {
        headerView.findViewById(R.id.btn_save).setOnClickListener(v -> saveConfig());

        View authToggleRow = headerView.findViewById(R.id.auth_toggle_row);
        authToggleRow.setOnClickListener(v -> switchAuth.toggle());
        switchAuth.setOnCheckedChangeListener((buttonView, isChecked) ->
                authFields.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        btnRefresh.setOnClickListener(v -> {
            animateRefresh();
            refreshLogs();
        });
        headerView.findViewById(R.id.btn_grant).setOnClickListener(v -> onGrantClicked());

        etUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (tilUrl.getError() != null) {
                    tilUrl.setError(null);
                }
            }
        });
    }

    private void saveConfig() {
        String url = textOf(etUrl).trim();
        if (url.isEmpty()) {
            tilUrl.setError(getString(R.string.error_url_required));
            etUrl.requestFocus();
            return;
        }
        if (!isHttpUrl(url)) {
            tilUrl.setError(getString(R.string.error_url_invalid));
            etUrl.requestFocus();
            return;
        }
        tilUrl.setError(null);

        boolean useAuth = switchAuth.isChecked();
        String user = useAuth ? textOf(etUser).trim() : "";
        String pass = useAuth ? textOf(etPass) : "";

        configManager.saveConfig(url, user, pass);

        // Restart so the service picks the new endpoint up immediately.
        stopService(new Intent(this, ForwardingService.class));
        startSmsService();

        hideKeyboard();
        updateStatus();
        refreshLogs();
        Snackbar.make(root, R.string.snack_config_saved, Snackbar.LENGTH_SHORT).show();
    }

    private boolean isHttpUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
            focus.clearFocus();
        }
    }

    private void updateStatus() {
        boolean permissionsReady = hasSmsPermission() && hasNotificationPermission() && hasStoragePermission();
        String url = configManager.getUrl();
        boolean hasUrl = url != null && !url.isEmpty();

        if (!permissionsReady) {
            tvHeroStatus.setText(R.string.status_needs_setup);
            statusDot.setBackgroundResource(R.drawable.bg_dot_amber);
            permissionBanner.setVisibility(View.VISIBLE);
        } else if (!hasUrl) {
            tvHeroStatus.setText(R.string.status_needs_endpoint);
            statusDot.setBackgroundResource(R.drawable.bg_dot_amber);
            permissionBanner.setVisibility(View.GONE);
        } else {
            tvHeroStatus.setText(R.string.status_active);
            statusDot.setBackgroundResource(R.drawable.bg_dot_active);
            permissionBanner.setVisibility(View.GONE);
        }

        tvBadgeRequired.setVisibility(hasUrl ? View.GONE : View.VISIBLE);
    }

    private void refreshLogs() {
        if (!hasStoragePermission()) {
            renderLogs(new ArrayList<>(), true);
            return;
        }
        ioExecutor.execute(() -> {
            List<LogEntry> entries;
            try {
                entries = dbHelper.getLogEntries();
            } catch (Exception e) {
                entries = new ArrayList<>();
            }
            List<LogEntry> result = entries;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                renderLogs(result, false);
            });
        });
    }

    private void renderLogs(List<LogEntry> entries, boolean accessDenied) {
        adapter.setLogs(entries);
        int count = adapter.getLogCount();
        tvLogCount.setText(String.valueOf(count));
        tvLogCount.setContentDescription(getString(R.string.log_count_desc, count));

        emptyState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        tvEmptyTitle.setText(accessDenied ? R.string.empty_title_no_access : R.string.empty_title);
        tvEmptyBody.setText(accessDenied ? R.string.empty_body_no_access : R.string.empty_body);

        btnRefresh.setEnabled(true);
    }

    private void animateRefresh() {
        btnRefresh.setEnabled(false);
        btnRefresh.setRotation(0f);
        btnRefresh.animate()
                .rotation(360f)
                .setDuration(650)
                .withEndAction(() -> btnRefresh.setRotation(0f))
                .start();
    }

    private static String textOf(TextInputEditText editText) {
        Editable text = editText.getText();
        return text == null ? "" : text.toString();
    }

    private void checkAndRequestPermissions() {
        if (!hasSmsPermission() || !hasNotificationPermission()) {
            requestRuntimePermissions();
            return;
        }
        if (!hasStoragePermission()) {
            // The in-page banner explains this and offers a "Grant" action.
            return;
        }
        requestIgnoreBatteryOptimizations();
        startSmsService();
    }

    private void onGrantClicked() {
        if (!hasSmsPermission() || !hasNotificationPermission()) {
            requestRuntimePermissions();
            return;
        }
        if (!hasStoragePermission()) {
            requestStorageAccess();
            return;
        }
        requestIgnoreBatteryOptimizations();
        startSmsService();
        updateStatus();
        refreshLogs();
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECEIVE_SMS);
        permissions.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        ActivityCompat.requestPermissions(this,
                permissions.toArray(new String[0]),
                PERMISSION_REQUEST_CODE);
    }

    private void requestStorageAccess() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
            startActivityForResult(intent, STORAGE_PERMISSION_CODE);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivityForResult(intent, STORAGE_PERMISSION_CODE);
        }
    }

    private boolean startSmsService() {
        Intent serviceIntent = new Intent(this, ForwardingService.class);
        try {
            startForegroundService(serviceIntent);
            serviceStartAttempted = true;
            return true;
        } catch (Exception e) {
            try {
                startService(serviceIntent);
                serviceStartAttempted = true;
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private void showOpenSettingsSnackbar() {
        Snackbar.make(root, R.string.permission_denied_snack, Snackbar.LENGTH_LONG)
                .setAction(R.string.open_settings, v -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean allGranted = hasSmsPermission() && hasNotificationPermission() && hasStoragePermission();
        if (allGranted && !serviceStartAttempted) {
            // Same as the original flow: ask for the battery-optimisation exemption
            // before the first service start of this activity instance.
            requestIgnoreBatteryOptimizations();
            startSmsService();
        }
        updateStatus();
        refreshLogs();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }

        boolean smsGranted = hasSmsPermission();
        boolean notificationsGranted = hasNotificationPermission();
        if (smsGranted && notificationsGranted) {
            Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show();
            if (hasStoragePermission()) {
                requestIgnoreBatteryOptimizations();
                startSmsService();
            }
        } else {
            Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show();
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
                showOpenSettingsSnackbar();
            }
        }

        updateStatus();
        refreshLogs();
    }
}
