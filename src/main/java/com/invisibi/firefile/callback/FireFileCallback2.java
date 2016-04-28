package com.invisibi.firefile.callback;

/**
 * Created by Tiny on 4/28/16.
 */
public interface FireFileCallback2<T1, T2 extends Throwable> {
    void done(T1 t1, T2 t2);
}
