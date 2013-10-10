package org.httpkit.server;

import java.nio.ByteBuffer;
import java.util.LinkedList;

public abstract class ServerAtta {
    final LinkedList<ByteBuffer> toWrites = new LinkedList<ByteBuffer>();

    protected AsyncChannel channel;

    // close the connection after write?

    /* HTTP: greedy, if client support it( HTTP/1.1 without keep-alive: close),
             http-kit only close the socket after client first close it
       WebSocket: When a close frame is received, the socket get closed after the response close frame is sent
     */
    protected boolean keepalive = true;

    public boolean isKeepAlive() {
        return keepalive;
    }

    // HTTP: can be set to false to keep connection open during a chunked response
    // WebSocket: not applicable
    protected boolean responsecomplete = true;

    public boolean isResponseComplete() {
        return responsecomplete;
    }
}
