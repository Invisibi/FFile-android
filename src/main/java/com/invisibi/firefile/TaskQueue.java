package com.invisibi.firefile;

import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import bolts.Continuation;
import bolts.Task;

/**
 * Created by Tiny on 4/28/16.
 */
class TaskQueue {
    private Task<Void> tail;
    private final Lock lock = new ReentrantLock();

    private Task<Void> getTaskToAwait() {
        lock.lock();
        try {
            Task<Void> toAwait = tail != null ? tail : Task.<Void>forResult(null);
            return toAwait.continueWith(new Continuation<Void, Void>() {
                @Override
                public Void then(Task<Void> task) throws Exception {
                    return null;
                }
            });
        } finally {
            lock.unlock();
        }
    }

    <T> Task<T> enqueue(Continuation<Void, Task<T>> taskStart) {
        lock.lock();
        try {
            Task<T> task;
            Task<Void> oldTail = tail != null ? tail : Task.<Void>forResult(null);
            // The task created by taskStart is responsible for waiting for the task passed into it before
            // doing its work (this gives it an opportunity to do startup work or save state before
            // waiting for its turn in the queue)
            try {
                Task<Void> toAwait = getTaskToAwait();
                task = taskStart.then(toAwait);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // The tail task should be dependent on the old tail as well as the newly-created task. This
            // prevents cancellation of the new task from causing the queue to run out of order.
            tail = Task.whenAll(Arrays.asList(oldTail, task));
            return task;
        } finally {
            lock.unlock();
        }
    }

    static <T> Continuation<T, Task<T>> waitFor(final Task<Void> toAwait) {
        return new Continuation<T, Task<T>>() {
            @Override
            public Task<T> then(final Task<T> task) throws Exception {
                return toAwait.continueWithTask(new Continuation<Void, Task<T>>() {
                    @Override
                    public Task<T> then(Task<Void> ignored) throws Exception {
                        return task;
                    }
                });
            }
        };
    }

    Lock getLock() {
        return lock;
    }

    void waitUntilFinished() throws InterruptedException {
        lock.lock();
        try {
            if (tail == null) {
                return;
            }
            tail.waitForCompletion();
        } finally {
            lock.unlock();
        }
    }
}

