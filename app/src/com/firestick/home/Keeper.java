package com.firestick.home;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

/**
 * Adapted from dumbtv home's Keeper (MIT, see LICENSE-dumbtv). Fire OS 6 only.
 *
 * Fire OS 6.7 refuses third-party accessibility services, so {@link Guard} cannot run there.
 * This foreground service instead reads the foreground app from UsageStats (granted with
 * {@code appops set com.firestick.home GET_USAGE_STATS allow}) and, when Amazon's home screen
 * comes up, moves My Home's task back to the front. moveTaskToFront is not an activity launch,
 * so Android's five-second app-switch delay does not apply.
 */
public final class Keeper extends Service {
    private static final String TAG = "MyHome";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long POLL_MS = 250;
    private static final long DEBOUNCE_MS = 1500;

    private static volatile boolean paused;
    private HandlerThread thread;
    private Handler worker;
    private UsageStatsManager usage;
    private long lastLaunch;
    private String topClass;

    /** Lets Amazon's home stay on screen until the user opens another app. */
    static void pause() {
        paused = true;
    }

    /** Fire OS 6 (Android 7.1); newer Fire OS uses the accessibility Guard. */
    static boolean legacy() {
        return Build.VERSION.SDK_INT < 26;
    }

    /** Turns off Home (CATEGORY_HOME) and turns on LegacyHome, once. */
    static void useLegacyHome(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        pm.setComponentEnabledSetting(new android.content.ComponentName(c, LegacyHome.class),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(new android.content.ComponentName(c, Home.class),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
    }

    /** Starts the keeper on Fire OS 6. */
    static void start(Context c) {
        if (!legacy()) return;
        useLegacyHome(c);
        c.startService(new Intent(c, Keeper.class));
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                check();
            } catch (Throwable ignored) {
                // Never let one bad query stop the watching.
            }
            worker.postDelayed(this, POLL_MS);
        }
    };

    @Override
    @SuppressWarnings("deprecation") // the channel-less builder is the only one Android 7.1 has
    public void onCreate() {
        super.onCreate();
        usage = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("My Home")
                .setContentText("Home button")
                .build());
        thread = new HandlerThread("keeper");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(poll);
        worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                firstStart();
            }
        }, 2500);
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    /** After a boot or an update there is no Home task yet, and UsageStats saw no new event. */
    private void firstStart() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || !manager.getAppTasks().isEmpty()) return;
        launch(System.currentTimeMillis());
    }

    private void check() {
        long now = System.currentTimeMillis();
        String top = top(now);
        if (top == null) return;
        if (!Guard.AMAZON.equals(top)) {
            if (!"com.android.systemui".equals(top) && !"android".equals(top)) paused = false;
            return;
        }
        if (!AMAZON_HOME.equals(topClass) || paused || now - lastLaunch < DEBOUNCE_MS) return;
        launch(now);
    }

    private String top(long now) {
        if (usage == null) return null;
        UsageEvents events = usage.queryEvents(now - 4000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String top = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                top = e.getPackageName();
                topClass = e.getClassName();
            }
        }
        return top;
    }

    private void launch(long now) {
        lastLaunch = now;
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            try {
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    // An empty task is one being torn down; moving it would relaunch a dead activity.
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    String top = info.topActivity == null ? "" : info.topActivity.getClassName();
                    String base = info.baseActivity == null ? "" : info.baseActivity.getClassName();
                    if (top.isEmpty() && base.isEmpty()) continue;
                    task.moveToFront();
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "keeper: moveToFront failed", t);
            }
        }
        Intent i = new Intent(this, LegacyHome.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.RTC, now, pi);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
