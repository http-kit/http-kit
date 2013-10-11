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
        return keepalive || chunkedResponseInprogress;
    }

    // Needed in the following situation, thanks @rufoa
    // https://github.com/http-kit/http-kit/pull/84
    // 1. client sent Connection: Close => server
    // 2. server try to streaming the response
    // 3. server close the connection after first write, which makes a bad streaming

    // only apply to HTTP
    protected boolean chunkedResponseInprogress = false;

    public void chunkedResponseInprogress(boolean b) {
        chunkedResponseInprogress = b;
    }
}
