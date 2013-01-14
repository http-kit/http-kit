package me.shenfeng.http.client;

import me.shenfeng.http.HTTPException;

public class TimeoutException extends HTTPException {

    private static final long serialVersionUID = 1L;

    public TimeoutException(String msg) {
        super(msg);
    }
}
