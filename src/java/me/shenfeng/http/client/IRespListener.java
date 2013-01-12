package me.shenfeng.http.client;

import java.util.Map;

import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpVersion;

/**
 * Interface for response received from server
 * 
 * A low level interface, Can be used for very large file download
 * 
 * @author feng
 * 
 */
public interface IRespListener {

    public static enum State {
        ABORT, CONTINUE
    }

    public State onBodyReceived(byte[] buf, int length);

    public void onCompleted();

    public State onHeadersReceived(Map<String, String> headers);

    public State onInitialLineReceived(HttpVersion version, HttpStatus status);

    /**
     * protocol error
     */
    public void onThrowable(Throwable t);
}
