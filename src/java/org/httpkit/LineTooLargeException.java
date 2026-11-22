package org.httpkit;

public class LineTooLargeException extends HTTPException {

    private static final long serialVersionUID = 1L;

    public LineTooLargeException(String msg) {
        super(msg);
    }
}
