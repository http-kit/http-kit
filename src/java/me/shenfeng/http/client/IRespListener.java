package me.shenfeng.http.client;

import java.util.Map;

import me.shenfeng.http.codec.HttpStatus;
import me.shenfeng.http.codec.HttpVersion;

/**
 * Will be invoked once the response/request has been fully read
 */
public interface IRespListener {

    public static final int ABORT = -1;
    public static final int CONTINUE = 1;

    public int onBodyReceived(byte[] buf, int length);

    public void onCompleted();

    public int onHeadersReceived(Map<String, String> headers);

    public int onInitialLineReceived(HttpVersion version, HttpStatus status);

    /**
     * protocol error
     */
    public void onThrowable(Throwable t);

}
