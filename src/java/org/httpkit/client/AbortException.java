package org.httpkit.client;

import org.httpkit.HTTPException;

public class AbortException extends HTTPException {

    public AbortException(String msg) {
        super(msg);
    }

    private static final long serialVersionUID = 1L;

}
