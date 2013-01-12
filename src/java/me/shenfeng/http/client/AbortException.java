package me.shenfeng.http.client;

public class AbortException extends Exception {

    public AbortException() {
        super("aborted");
    }

    private static final long serialVersionUID = 1L;

}
