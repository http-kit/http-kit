package me.shenfeng.http;

public class LineTooLargeException extends Exception {

    private static final long serialVersionUID = 1L;

    public LineTooLargeException(String msg) {
        super(msg);
    }
}
