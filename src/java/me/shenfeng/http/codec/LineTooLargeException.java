package me.shenfeng.http.codec;

public class LineTooLargeException extends Exception {

    private static final long serialVersionUID = 1L;

    public LineTooLargeException(String msg) {
        super(msg);
    }
}
