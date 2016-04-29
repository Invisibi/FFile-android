package com.invisibi.firefile.util;

import com.invisibi.firefile.FireFileExecutors;
import com.invisibi.firefile.callback.FireFileCallback1;
import com.invisibi.firefile.callback.FireFileCallback2;

import java.util.concurrent.CancellationException;

import bolts.Continuation;
import bolts.Task;

/**
 * Created by Tiny on 4/28/16.
 */
public class FireFileTaskUtils {

    public static <T> T wait(Task<T> task) throws Exception {
        try {
            task.waitForCompletion();
            if (task.isFaulted()) {
                Exception error = task.getError();
                if (error instanceof RuntimeException) {
                    throw (RuntimeException) error;
                }
                throw new RuntimeException(error);
            } else if (task.isCancelled()) {
                throw new RuntimeException(new CancellationException());
            }
            return task.getResult();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Task<Void> callbackOnMainThreadAsync(Task<Void> task,
                                                       final FireFileCallback1<Exception> callback) {
        return callbackOnMainThreadAsync(task, callback, false);
    }

    public static Task<Void> callbackOnMainThreadAsync(Task<Void> task,
                                                       final FireFileCallback1<Exception> callback, final boolean reportCancellation) {
        if (callback == null) {
            return task;
        }
        return callbackOnMainThreadAsync(task, new FireFileCallback2<Void, Exception>() {
            @Override
            public void done(Void aVoid, Exception e) {
                callback.done(e);
            }
        }, reportCancellation);
    }

    public static <T> Task<T> callbackOnMainThreadAsync(Task<T> task,
                                                        final FireFileCallback2<T, Exception> callback) {
        return callbackOnMainThreadAsync(task, callback, false);
    }

    public static <T> Task<T> callbackOnMainThreadAsync(Task<T> task,
                                                        final FireFileCallback2<T, Exception> callback, final boolean reportCancellation) {
        if (callback == null) {
            return task;
        }
        final Task<T>.TaskCompletionSource tcs = Task.create();
        task.continueWith(new Continuation<T, Void>() {
            @Override
            public Void then(final Task<T> task) throws Exception {
                if (task.isCancelled() && !reportCancellation) {
                    tcs.setCancelled();
                    return null;
                }
                FireFileExecutors.main().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Exception error = task.getError();
                            callback.done(task.getResult(), error);
                        } finally {
                            if (task.isCancelled()) {
                                tcs.setCancelled();
                            } else if (task.isFaulted()) {
                                tcs.setError(task.getError());
                            } else {
                                tcs.setResult(task.getResult());
                            }
                        }
                    }
                });
                return null;
            }
        });
        return tcs.getTask();
    }
}
