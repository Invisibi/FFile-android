package com.invisibi.firefile;

import android.content.Context;
import android.webkit.MimeTypeMap;

import com.amazonaws.regions.Regions;
import com.invisibi.firefile.callback.GetDataCallback;
import com.invisibi.firefile.callback.GetDataStreamCallback;
import com.invisibi.firefile.callback.GetFileCallback;
import com.invisibi.firefile.callback.ProgressCallback;
import com.invisibi.firefile.callback.SaveCallback;
import com.invisibi.firefile.util.FireFileTaskUtils;
import com.invisibi.firefile.util.FireFileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import bolts.Continuation;
import bolts.Task;
import bolts.TaskCompletionSource;

/**
 * Created by Tiny on 4/27/16.
 */
public class FireFile {
    private static final int MAX_FILE_SIZE = 10 * 1048576;
    private static String s3URL;
    private static String s3Bucket;
    private static FireFileController fFileController;
    private State state;
    private byte[] data;
    private File file;

    final TaskQueue taskQueue = new TaskQueue();
    private Set<TaskCompletionSource> currentTasks = Collections.synchronizedSet(new HashSet<TaskCompletionSource>());

    private static ProgressCallback progressCallbackOnMainThread(final ProgressCallback progressCallback) {
        if (progressCallback == null) {
            return null;
        }

        return new ProgressCallback() {
            @Override
            public void done(final Integer percentDone) {
                Task.call(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        progressCallback.done(percentDone);
                        return null;
                    }
                }, FireFileExecutors.main());
            }
        };
    }

    public static class State {
        public static class Builder {
            private String name;
            private String mimeType;
            private String url;

            public Builder() {
                // do nothing
            }

            public Builder(State state) {
                name = state.name();
                mimeType = state.mimeType();
                url = state.url();
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder mimeType(String mimeType) {
                this.mimeType = mimeType;
                return this;
            }

            public Builder url(String url) {
                this.url = url;
                return this;
            }

            public State build() {
                return new State(this);
            }
        }

        private final String name;
        private final String contentType;
        private final String url;

        private State(Builder builder) {
            name = builder.name != null ? builder.name : "file";
            contentType = builder.mimeType;
            url = builder.url;
        }

        public String name() {
            return name;
        }

        public String mimeType() {
            return contentType;
        }

        public String url() {
            return url;
        }


    }

    public static void initialize(final Context context, final String awsIdentityPoolId, final String s3URL, final String s3Bucket, final Regions s3Regions) {
        fFileController = new FireFileController(context, awsIdentityPoolId, s3Regions, s3URL, s3Bucket);
        FireFile.s3URL = s3URL;
        FireFile.s3Bucket = s3Bucket;
    }

    public FireFile(final String objectId) {
        final String url = s3URL + File.separator + s3Bucket + File.separator + objectId;
        final String mimeType = MimeTypeMap.getFileExtensionFromUrl(url);
        final String name = objectId;
        this.state = new State.Builder().url(url).name(name).mimeType(mimeType).build();
    }

    public FireFile(final File file) {
        this(file, null);
    }

    public FireFile(final File file, final String contentType) {
        this(new State.Builder().name(file.getName()).mimeType(contentType).build());
        if (file.length() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(String.format("FFile must be less than %d bytes", MAX_FILE_SIZE));
        }
        this.file = file;
    }

    public FireFile(final String name, final byte[] data, final String contentType) {
        this(new State.Builder().name(name).mimeType(contentType).build());
        if (data.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(String.format("FFile must be less than %d bytes", MAX_FILE_SIZE));
        }
        this.data = data;
    }

    public FireFile(final byte[] data) {
        this(null, data, null);
    }

    public FireFile(final String name, final byte[] data) {
        this(name, data, null);
    }

    public FireFile(final byte[] data, final String contentType) {
        this(null, data, contentType);
    }

    public FireFile(final State state) {
        this.state = state;
    }

    public State getState() {
        return state;
    }

    public String getName() {
        return state.name();
    }

    public boolean isDirty() {
        return state.url() == null;
    }

    public boolean isDataAvailable() {
        return data != null || fFileController.isDataAvailable(state);
    }

    public String getUrl() {
        return state.url();
    }

    public void save() throws Exception {
        FireFileTaskUtils.wait(saveInBackground());
    }

    private Task<Void> saveAsync(final ProgressCallback uploadProgressCallback, final Task<Void> toAwait, final Task<Void> cancellationToken) {
        // If the file isn't dirty, just return immediately.
        if (!isDirty()) {
            return Task.forResult(null);
        }
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return Task.cancelled();
        }

        // Wait for our turn in the queue, then check state to decide whether to no-op.
        return toAwait.continueWithTask(new Continuation<Void, Task<Void>>() {
            @Override
            public Task<Void> then(Task<Void> task) throws Exception {
                if (!isDirty()) {
                    return Task.forResult(null);
                }
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    return Task.cancelled();
                }

                Task<FireFile.State> saveTask;
                if (data != null) {
                    saveTask = fFileController.saveAsync(
                            state,
                            data,
                            progressCallbackOnMainThread(uploadProgressCallback),
                            cancellationToken);
                } else {
                    saveTask = fFileController.saveAsync(
                            state,
                            file,
                            progressCallbackOnMainThread(uploadProgressCallback),
                            cancellationToken);
                }

                return saveTask.onSuccessTask(new Continuation<State, Task<Void>>() {
                    @Override
                    public Task<Void> then(Task<State> task) throws Exception {
                        state = task.getResult();
                        // Since we have successfully uploaded the file, we do not need to hold the file pointer
                        // anymore.
                        data = null;
                        file = null;
                        return task.makeVoid();
                    }
                });
            }
        });
    }

    public Task<Void> saveInBackground(final ProgressCallback uploadProgressCallback) {
        final Task<Void>.TaskCompletionSource cts = Task.create();
        currentTasks.add(cts);

        return saveAsync(uploadProgressCallback, cts.getTask())
                .continueWithTask(new Continuation<Void, Task<Void>>() {
                    @Override
                    public Task<Void> then(Task<Void> task) throws Exception {
                        cts.trySetResult(null); // release
                        currentTasks.remove(cts);
                        return task;
                    }
                });
    }

    Task<Void> saveAsync(final ProgressCallback uploadProgressCallback, final Task<Void> cancellationToken) {
        return taskQueue.enqueue(new Continuation<Void, Task<Void>>() {
            @Override
            public Task<Void> then(Task<Void> toAwait) throws Exception {
                return saveAsync(uploadProgressCallback, toAwait, cancellationToken);
            }
        });
    }

    public Task<Void> saveInBackground() {
        return saveInBackground((ProgressCallback) null);
    }

    public void saveInBackground(final SaveCallback saveCallback, final ProgressCallback progressCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(saveInBackground(progressCallback), saveCallback);
    }

    public void saveInBackground(final SaveCallback callback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(saveInBackground(), callback);
    }

    public byte[] getData() throws Exception {
        return FireFileTaskUtils.wait(getDataInBackground());
    }

    public Task<byte[]> getDataInBackground(final ProgressCallback progressCallback) {
        final TaskCompletionSource cts = new TaskCompletionSource();
        currentTasks.add(cts);

        return taskQueue.enqueue(new Continuation<Void, Task<byte[]>>() {
            @Override
            public Task<byte[]> then(Task<Void> toAwait) throws Exception {
                return fetchInBackground(progressCallback, toAwait, cts.getTask()).onSuccess(new Continuation<File, byte[]>() {
                    @Override
                    public byte[] then(Task<File> task) throws Exception {
                        File file = task.getResult();
                        try {
                            return FireFileUtils.readFileToByteArray(file);
                        } catch (IOException e) {
                            // do nothing
                        }
                        return null;
                    }
                });
            }
        }).continueWithTask(new Continuation<byte[], Task<byte[]>>() {
            @Override
            public Task<byte[]> then(Task<byte[]> task) throws Exception {
                cts.trySetResult(null); // release
                currentTasks.remove(cts);
                return task;
            }
        });
    }

    public Task<byte[]> getDataInBackground() {
        return getDataInBackground((ProgressCallback) null);
    }

    public void getDataInBackground(final GetDataCallback dataCallback, final ProgressCallback progressCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(getDataInBackground(progressCallback), dataCallback);
    }

    public void getDataInBackground(final GetDataCallback dataCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(getDataInBackground(), dataCallback);
    }

    public File getFile() throws Exception {
        return FireFileTaskUtils.wait(getFileInBackground());
    }

    public Task<File> getFileInBackground(final ProgressCallback progressCallback) {
        final TaskCompletionSource cts = new TaskCompletionSource();
        currentTasks.add(cts);

        return taskQueue.enqueue(new Continuation<Void, Task<File>>() {
            @Override
            public Task<File> then(Task<Void> toAwait) throws Exception {
                return fetchInBackground(progressCallback, toAwait, cts.getTask());
            }
        }).continueWithTask(new Continuation<File, Task<File>>() {
            @Override
            public Task<File> then(Task<File> task) throws Exception {
                cts.trySetResult(null); // release
                currentTasks.remove(cts);
                return task;
            }
        });
    }

    public Task<File> getFileInBackground() {
        return getFileInBackground((ProgressCallback) null);
    }

    public void getFileInBackground(final GetFileCallback fileCallback, final ProgressCallback progressCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(getFileInBackground(progressCallback), fileCallback);
    }

    public void getFileInBackground(final GetFileCallback fileCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(getFileInBackground(), fileCallback);
    }

    public InputStream getDataStream() throws Exception {
        return FireFileTaskUtils.wait(getDataStreamInBackground());
    }

    public Task<InputStream> getDataStreamInBackground(final ProgressCallback progressCallback) {
        final TaskCompletionSource cts = new TaskCompletionSource();
        currentTasks.add(cts);

        return taskQueue.enqueue(new Continuation<Void, Task<InputStream>>() {
            @Override
            public Task<InputStream> then(Task<Void> toAwait) throws Exception {
                return fetchInBackground(progressCallback, toAwait, cts.getTask()).onSuccess(new Continuation<File, InputStream>() {
                    @Override
                    public InputStream then(Task<File> task) throws Exception {
                        return new FileInputStream(task.getResult());
                    }
                });
            }
        }).continueWithTask(new Continuation<InputStream, Task<InputStream>>() {
            @Override
            public Task<InputStream> then(Task<InputStream> task) throws Exception {
                cts.trySetResult(null); // release
                currentTasks.remove(cts);
                return task;
            }
        });
    }

    private Task<File> fetchInBackground(final ProgressCallback progressCallback, final Task<Void> toAwait, final Task<Void> cancellationToken) {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return Task.cancelled();
        }

        return toAwait.onSuccessTask(new Continuation<Void, Task<File>>() {
            @Override
            public Task<File> then(final Task<Void> task) throws Exception {
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    return Task.cancelled();
                }
                return fFileController.fetchAsync(state, progressCallbackOnMainThread(progressCallback), cancellationToken);
            }
        });
    }

    public Task<InputStream> getDataStreamInBackground() {
        return getDataStreamInBackground((ProgressCallback) null);
    }

    public void getDataStreamInBackground(final GetDataStreamCallback dataStreamCallback, final ProgressCallback progressCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(getDataStreamInBackground(progressCallback), dataStreamCallback);
    }

    public void getDataStreamInBackground(final GetDataStreamCallback dataStreamCallback) {
        FireFileTaskUtils.callbackOnMainThreadAsync(getDataStreamInBackground(), dataStreamCallback);
    }

    public void cancel() {
        Set<TaskCompletionSource> tasks = new HashSet<>(currentTasks);
        for (TaskCompletionSource tcs : tasks) {
            tcs.trySetCancelled();
        }
        currentTasks.removeAll(tasks);
    }
}