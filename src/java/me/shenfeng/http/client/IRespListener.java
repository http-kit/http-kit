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

    public void onBodyReceived(byte[] buf, int length) throws AbortException;

    public void onCompleted();

    public void onHeadersReceived(Map<String, String> headers) throws AbortException;

    public void onInitialLineReceived(HttpVersion version, HttpStatus status)
            throws AbortException;

    /**
     * protocol error
     */
    public void onThrowable(Throwable t);
}
