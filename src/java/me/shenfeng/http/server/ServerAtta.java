package me.shenfeng.http.server;

import java.nio.ByteBuffer;
import java.util.LinkedList;

public class ServerAtta {

    public ServerAtta(int maxBody) {
        decoder = new ReqeustDecoder(maxBody);
    }

    public final LinkedList<ByteBuffer> toWrites = new LinkedList<ByteBuffer>();
    public final ReqeustDecoder decoder;

    public void addBuffer(ByteBuffer buffer) {
        synchronized (toWrites) {
            if (buffer != null)
                toWrites.add(buffer);
        }
    }

    public void addBuffer(ByteBuffer buffer1, ByteBuffer buffer2) {
        synchronized (toWrites) {
            if (buffer1 != null) {
                toWrites.add(buffer1);
            }
            if (buffer2 != null)
                toWrites.add(buffer2);
        }
    }
}
