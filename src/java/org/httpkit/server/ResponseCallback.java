package org.httpkit.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class ResponseCallback {
    private final SelectionKey key;
    private final HttpServer server;

    public ResponseCallback(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    // maybe in another thread :worker thread
    public void run(ByteBuffer... buffers) {
        server.tryWrite(key, buffers);
    }
}
