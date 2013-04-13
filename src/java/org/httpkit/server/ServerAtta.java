package org.httpkit.server;

import java.nio.ByteBuffer;
import java.util.LinkedList;

public abstract class ServerAtta {
    final LinkedList<ByteBuffer> toWrites = new LinkedList<ByteBuffer>();

    protected AsyncChannel channel;

    public abstract boolean isKeepAlive();
}
