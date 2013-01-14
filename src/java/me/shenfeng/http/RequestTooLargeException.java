package me.shenfeng.http;

public class RequestTooLargeException extends HTTPException {

    private static final long serialVersionUID = 1L;

    public RequestTooLargeException(String msg) {
        super(msg);
    }
}
