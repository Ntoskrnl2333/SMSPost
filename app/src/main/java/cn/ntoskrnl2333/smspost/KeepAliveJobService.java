package cn.ntoskrnl2333.smspost;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * 周期 Job（jobId=2001，15 分钟）与补发 Job（jobId=2002，有网就跑）。
 *
 * 周期 Job 是官方的"系统定时唤醒"通道：进程被杀后，系统到点创建进程执行 onStartJob，
 * 于是 {@link KeepAliveManager#ensureServiceRunning} 有机会把前台服务重新拉起来。
 * setPersisted(true) 让任务在重启后依然存在，不必依赖 BOOT_COMPLETED。
 */
public class KeepAliveJobService extends JobService {

    private static final String TAG = "SmsPostJob";

    @Override
    public boolean onStartJob(final JobParameters params) {
        final int jobId = params.getJobId();
        Log.i(TAG, "job start: " + jobId);

        new Thread(() -> {
            try {
                if (jobId == KeepAliveManager.JOB_FLUSH) {
                    SmsFlusher.flushBlocking(KeepAliveJobService.this);
                } else {
                    KeepAliveManager.ensureServiceRunning(KeepAliveJobService.this, "job_" + jobId);
                    KeepAliveManager.scheduleWatchdogJob(KeepAliveJobService.this);
                }
            } catch (Throwable t) {
                Log.w(TAG, "job work failed: " + jobId, t);
            } finally {
                jobFinished(params, false);
            }
        }, "smspost-job-" + jobId).start();

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "job stop: " + params.getJobId());
        return true;
    }
}
