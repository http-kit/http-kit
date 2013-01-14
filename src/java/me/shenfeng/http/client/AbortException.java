package me.shenfeng.http.client;

import me.shenfeng.http.HTTPException;

public class AbortException extends HTTPException {

    public AbortException(String msg) {
        super(msg);
    }

    private static final long serialVersionUID = 1L;

}
