package com.invisibi.firefile.data;

/**
 * Created by Tiny on 4/29/16.
 */
public class FireFilePointer {
    private String key;
    private String type;

    public FireFilePointer(final String key, final String type) {
        this.key = key;
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public void setKey(final String key) {
        this.key = key;
    }

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }
}
