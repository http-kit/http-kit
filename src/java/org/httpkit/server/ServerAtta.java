package org.httpkit.server;

import java.nio.ByteBuffer;
import java.util.LinkedList;

public abstract class ServerAtta {
    public final LinkedList<ByteBuffer> toWrites = new LinkedList<ByteBuffer>();

    public void addBuffer(ByteBuffer... buffer) {
        synchronized (toWrites) {
            for (ByteBuffer b : buffer) {
                if (b != null) {
                    toWrites.add(b);
                }
            }
        }
    }
    
    public abstract boolean isKeepAlive();
}
