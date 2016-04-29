package com.invisibi.firefile.callback;

import java.io.File;

/**
 * Created by Tiny on 4/28/16.
 */
public interface GetFileCallback extends FireFileCallback2<File, Exception> {
    @Override
    void done(File file, Exception e);
}
