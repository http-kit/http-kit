package me.shenfeng.http.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Queue;

public class ResponseCallback {
    private final SelectionKey key;
    private final Queue<SelectionKey> pendings;

    public ResponseCallback(Queue<SelectionKey> pendings, SelectionKey key) {
        this.pendings = pendings;
        this.key = key;
    }

    // maybe in another thread :worker thread
    public void run(ByteBuffer... buffers) {
        ServerAtta atta = (ServerAtta) key.attachment();
        for (ByteBuffer b : buffers) {
            atta.addBuffer(b);
        }
        atta.reset();
        pendings.offer(key);
        key.selector().wakeup();
    }
}
