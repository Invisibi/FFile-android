package com.invisibi.firefile.callback;

/**
 * Created by Tiny on 4/28/16.
 */
public interface GetDataCallback extends FireFileCallback2<byte[], Exception> {
    @Override
    void done(byte[] data, Exception e);
}
