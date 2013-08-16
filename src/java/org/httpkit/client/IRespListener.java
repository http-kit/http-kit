package org.httpkit.client;

import org.httpkit.HttpStatus;
import org.httpkit.HttpVersion;

import java.util.Map;

/**
 * Interface for response received from server
 * <p/>
 * A low level interface, can be used for very large file download
 *
 * @author feng
 */
public interface IRespListener {

    public void onBodyReceived(byte[] buf, int length) throws AbortException;

    public void onCompleted();

    public void onHeadersReceived(Map<String, Object> headers) throws AbortException;

    public void onInitialLineReceived(HttpVersion version, HttpStatus status)
            throws AbortException;

    /**
     * protocol error
     */
    public void onThrowable(Throwable t);
}
