package com.invisibi.firefile.callback;

import java.io.InputStream;

/**
 * Created by Tiny on 4/28/16.
 */
public interface GetDataStreamCallback extends FireFileCallback2<InputStream, Exception> {
    @Override
    void done(InputStream input, Exception e);
}
