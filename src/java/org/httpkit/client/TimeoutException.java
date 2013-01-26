package org.httpkit.client;

import org.httpkit.HTTPException;

public class TimeoutException extends HTTPException {

    private static final long serialVersionUID = 1L;

    public TimeoutException(String msg) {
        super(msg);
    }
}
