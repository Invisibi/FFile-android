package com.invisibi.firefile.callback;

/**
 * Created by Tiny on 4/28/16.
 */
public interface SaveCallback extends FireFileCallback1<Exception> {
    @Override
    void done(Exception e);
}
