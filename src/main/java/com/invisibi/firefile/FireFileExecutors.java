package com.invisibi.firefile;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import bolts.Task;

/**
 * Created by Tiny on 4/27/16.
 */
public class FireFileExecutors {
    private static ScheduledExecutorService scheduledExecutor;
    private static final Object SCHEDULED_EXECUTOR_LOCK = new Object();

    static ScheduledExecutorService scheduled() {
        synchronized (SCHEDULED_EXECUTOR_LOCK) {
            if (scheduledExecutor == null) {
                scheduledExecutor = java.util.concurrent.Executors.newScheduledThreadPool(1);
            }
        }
        return scheduledExecutor;
    }

    public static Executor main() {
        return Task.UI_THREAD_EXECUTOR;
    }

    public static Executor io() {
        return Task.BACKGROUND_EXECUTOR;
    }
}
