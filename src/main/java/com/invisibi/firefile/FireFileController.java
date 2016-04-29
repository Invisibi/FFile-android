package com.invisibi.firefile;

import android.content.Context;
import android.util.Log;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.invisibi.firefile.callback.ProgressCallback;
import com.invisibi.firefile.util.FireFileUtils;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Callable;

import bolts.Continuation;
import bolts.Task;
import bolts.TaskCompletionSource;

/**
 * Created by Tiny on 4/28/16.
 */
public class FireFileController {
    private static final String DEFAULT_SUB_FOLDER = "file";
    private TransferUtility transferUtility;
    private String s3URL;
    private String s3Bucket;
    private File cachePath;

    public FireFileController(final Context context, final String awsIdentityPoolId, final Regions s3Regions, final String s3URL, final String s3Bucket) {
        this.s3Bucket = s3Bucket;
        this.s3URL = s3URL;
        cachePath = new File(context.getCacheDir(), "FFile");

        final CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(context, awsIdentityPoolId, s3Regions);
        final AmazonS3 s3 = new AmazonS3Client(credentialsProvider);
        transferUtility = new TransferUtility(s3, context);
    }

    public File getCacheFile(FireFile.State state) {
        return new File(cachePath, state.name());
    }

    File getTempFile(FireFile.State state) {
        if (state.url() == null) {
            return null;
        }
        return new File(cachePath, state.url() + ".tmp");
    }

    public boolean isDataAvailable(FireFile.State state) {
        return getCacheFile(state).exists();
    }

    public void clearCache() {
        File[] files = cachePath.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            FireFileUtils.deleteQuietly(file);
        }
    }

    public Task<FireFile.State> saveAsync(final FireFile.State state, final byte[] data, final ProgressCallback progressCallback, final Task<Void> cancellationToken) {
        if (state.url() != null) { // !isDirty
            return Task.forResult(state);
        }
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return Task.cancelled();
        }
        return Task.forResult(data).onSuccessTask(new Continuation<byte[], Task<File>>() {
            @Override
            public Task<File> then(final Task<byte[]> task) throws Exception {
                final File cacheFile = getCacheFile(state);
                FireFileUtils.writeByteArrayToFile(cacheFile, data);
                TaskCompletionSource<File> successful = new TaskCompletionSource<>();
                successful.setResult(cacheFile);
                return successful.getTask();
            }
        }).onSuccessTask(new Continuation<File, Task<FireFile.State>>() {
            @Override
            public Task<FireFile.State> then(final Task<File> task) throws Exception {
                return uploadFileToS3(task.getResult(), state, progressCallback).getTask();
            }
        });
    }

    public Task<FireFile.State> saveAsync(final FireFile.State state, final File file, final ProgressCallback progressCallback, final Task<Void> cancellationToken) {
        if (state.url() != null) { // !isDirty
            return Task.forResult(state);
        }
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return Task.cancelled();
        }

        return Task.forResult(state).onSuccessTask(new Continuation<FireFile.State, Task<FireFile.State>>() {
            @Override
            public Task<FireFile.State> then(final Task<FireFile.State> task) throws Exception {
                return uploadFileToS3(file, state, progressCallback).getTask();
            }
        });
    }

    private TaskCompletionSource uploadFileToS3(final File file, final FireFile.State state, final ProgressCallback progressCallback) {
        final TaskCompletionSource<FireFile.State> taskCompletionSource = new TaskCompletionSource<>();
        final String objectId = UUID.randomUUID() + "-" + state.name();
        TransferObserver transferObserver = transferUtility.upload(s3Bucket, DEFAULT_SUB_FOLDER + File.separator + objectId, file, CannedAccessControlList.PublicReadWrite);
        transferObserver.setTransferListener(new TransferListener() {
            @Override
            public void onStateChanged(final int id, final TransferState transferState) {
                switch (transferState) {
                    case COMPLETED:
                        final FireFile.State.Builder builder = new FireFile.State.Builder();
                        builder.name(state.name()).mimeType(state.mimeType()).url(s3URL + File.separator + s3Bucket + File.separator + objectId);
                        taskCompletionSource.setResult(builder.build());
                        break;
                    case FAILED:
                        taskCompletionSource.setError(new Exception("Upload FFile fail"));
                        break;
                }
            }

            @Override
            public void onProgressChanged(final int id, final long bytesCurrent, final long bytesTotal) {
                if (progressCallback != null) {
                    progressCallback.done((int) (((float) bytesCurrent / (float) bytesTotal) * 100));
                }
            }

            @Override
            public void onError(final int id, final Exception ex) {
                taskCompletionSource.setError(ex);
            }
        });
        return taskCompletionSource;
    }

    public Task<File> fetchAsync(final FireFile.State state, final ProgressCallback downloadProgressCallback, final Task<Void> cancellationToken) {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return Task.cancelled();
        }
        final File cacheFile = getCacheFile(state);

        return Task.call(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return cacheFile.exists();
            }
        }, FireFileExecutors.io()).continueWithTask(new Continuation<Boolean, Task<File>>() {
            @Override
            public Task<File> then(Task<Boolean> task) throws Exception {
                boolean result = task.getResult();
                if (result) {
                    return Task.forResult(cacheFile);
                }
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    return Task.cancelled();
                }

                final TaskCompletionSource<File> taskCompletionSource = new TaskCompletionSource<>();

                final File tempFile = getTempFile(state);
                final String objectId = state.url().replace(s3URL + s3Bucket + "/", "");
                TransferObserver observer = transferUtility.download(s3Bucket, objectId, tempFile);
                observer.setTransferListener(new TransferListener() {
                    @Override
                    public void onStateChanged(final int id, final TransferState state) {
                        switch (state) {
                            case COMPLETED:
                                try {
                                    FireFileUtils.deleteQuietly(cacheFile);
                                    FireFileUtils.moveFile(tempFile, cacheFile);
                                    taskCompletionSource.setResult(cacheFile);
                                } catch (IOException e) {
                                    taskCompletionSource.setError(e);
                                }
                                break;
                            case FAILED:
                                FireFileUtils.deleteQuietly(tempFile);
                                taskCompletionSource.setError(new Exception("Download FFile fail"));
                                break;
                        }
                    }

                    @Override
                    public void onProgressChanged(final int id, final long bytesCurrent, final long bytesTotal) {
                        if (downloadProgressCallback != null) {
                            downloadProgressCallback.done((int) (((float) bytesCurrent / (float) bytesTotal) * 100));
                        }
                    }

                    @Override
                    public void onError(final int id, final Exception ex) {
                        Log.d(FireFileController.class.getSimpleName(), "error:" + ex.getLocalizedMessage());
                        FireFileUtils.deleteQuietly(tempFile);
                    }
                });

                return taskCompletionSource.getTask();
            }
        });
    }
}
