package me.shenfeng.http.server;

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
        ServerAtta atta = (ServerAtta) key.attachment();
        atta.addBuffer(buffers);
        server.queueWrite(key);
    }
}
