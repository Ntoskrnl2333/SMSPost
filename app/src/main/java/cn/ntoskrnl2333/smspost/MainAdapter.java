package cn.ntoskrnl2333.smspost;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter whose first item is the pre-inflated screen header
 * (hero + configuration + delivery-log heading). The remaining items are log cards.
 */
public class MainAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_LOG = 1;

    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_ERROR = 1;
    private static final int STATUS_WARNING = 2;
    private static final int STATUS_NEUTRAL = 3;

    private final Context context;
    private final View headerView;
    private final List<LogEntry> logs = new ArrayList<>();

    public MainAdapter(Context context, View headerView) {
        this.context = context;
        this.headerView = headerView;
    }

    public void setLogs(List<LogEntry> entries) {
        logs.clear();
        if (entries != null) {
            logs.addAll(entries);
        }
        notifyDataSetChanged();
    }

    public int getLogCount() {
        return logs.size();
    }

    @Override
    public int getItemCount() {
        return logs.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_LOG;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(headerView);
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof LogViewHolder) {
            ((LogViewHolder) holder).bind(logs.get(position - 1));
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        HeaderViewHolder(View itemView) {
            super(itemView);
        }
    }

    class LogViewHolder extends RecyclerView.ViewHolder {
        private final TextView sender;
        private final TextView content;
        private final TextView relativeTime;
        private final TextView absTime;
        private final TextView status;
        private final View statusDot;

        LogViewHolder(View itemView) {
            super(itemView);
            sender = itemView.findViewById(R.id.tv_sender);
            content = itemView.findViewById(R.id.tv_content);
            relativeTime = itemView.findViewById(R.id.tv_relative_time);
            absTime = itemView.findViewById(R.id.tv_abs_time);
            status = itemView.findViewById(R.id.tv_status);
            statusDot = itemView.findViewById(R.id.status_dot);
        }

        void bind(LogEntry entry) {
            sender.setText(entry.sender.isEmpty()
                    ? context.getString(R.string.unknown_sender)
                    : entry.sender);
            content.setText(entry.content);

            long now = System.currentTimeMillis();
            relativeTime.setText(DateUtils.getRelativeTimeSpanString(
                    entry.timestamp, now, DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE));
            absTime.setText(formatTimestamp(entry.timestamp));

            switch (classify(entry.status)) {
                case STATUS_SUCCESS:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_success);
                    status.setTextColor(ContextCompat.getColor(context, R.color.status_success));
                    break;
                case STATUS_ERROR:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_error);
                    status.setTextColor(ContextCompat.getColor(context, R.color.status_error));
                    break;
                case STATUS_WARNING:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_warning);
                    status.setTextColor(ContextCompat.getColor(context, R.color.status_warning_text));
                    break;
                default:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_neutral);
                    status.setTextColor(ContextCompat.getColor(context, R.color.status_neutral));
                    break;
            }

            String displayStatus = displayStatus(entry.status);
            status.setText(displayStatus);
            // Keep the full text available to screen readers when it is ellipsized.
            status.setContentDescription(entry.status);
        }
    }

    /**
     * Strips the endpoint/credential suffix written by older builds so legacy rows do not
     * leak configuration details into the list.
     */
    private String displayStatus(String status) {
        int legacySuffix = status.indexOf(", url:");
        return legacySuffix > 0 ? status.substring(0, legacySuffix) : status;
    }

    private int classify(String status) {
        String display = displayStatus(status);
        // Locale-independent fallbacks first, covering both shipped languages and legacy rows.
        if (display.contains("Success") || display.contains("成功")) {
            return STATUS_SUCCESS;
        }
        if (display.contains("URL not set") || display.contains("未设置")) {
            return STATUS_WARNING;
        }
        if (display.contains("Failed") || display.contains("失败")) {
            return STATUS_ERROR;
        }
        // Current-locale strings, in case a translated build changes the wording.
        if (display.equals(context.getString(R.string.status_success))) {
            return STATUS_SUCCESS;
        }
        String noUrl = context.getString(R.string.status_url_not_set);
        if (display.equals(noUrl)) {
            return STATUS_WARNING;
        }
        return STATUS_NEUTRAL;
    }

    private String formatTimestamp(long timestamp) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(timestamp);
        String pattern = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                ? "MM-dd HH:mm"
                : "yyyy-MM-dd HH:mm";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(timestamp));
    }
}
