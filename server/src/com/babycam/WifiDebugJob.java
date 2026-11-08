package com.babycam;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

// Turns Wireless debugging on once wifi is up, so the PC starter can start the camera server.
// The starter turns it back off after the server is running.
public class WifiDebugJob extends JobService {
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        js.schedule(new JobInfo.Builder(1, new ComponentName(c, WifiDebugJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setOverrideDeadline(60_000)
            .build());
    }

    static boolean enable(Context c) {
        try {
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override public boolean onStartJob(JobParameters p) {
        enable(this);
        return false;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }
}
