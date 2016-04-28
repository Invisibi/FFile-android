package com.invisibi.firefile.callback;

/**
 * Created by Tiny on 4/28/16.
 */
public interface FireFileCallback1<T extends Throwable> {
    void done(T t);
}
