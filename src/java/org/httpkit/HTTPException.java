package org.httpkit;

public class HTTPException extends Exception {

    private static final long serialVersionUID = 1L;

    public HTTPException(String msg) {
        super(msg);
    }
}
