package org.httpkit.client;

import java.net.SocketAddress;
import java.nio.channels.SelectionKey;

public class PersistentConn implements Comparable<PersistentConn> {
    private final long timeoutTs;
    private final String host;
    public final SocketAddress addr;
    public final SelectionKey key;

    public PersistentConn(long timeoutTs, SocketAddress addr, String host, SelectionKey key) {
        this.timeoutTs = timeoutTs;
        this.addr = addr;
        this.host = host;
        this.key = key;
    }

    public boolean equals(Object obj) {
        // for PriorityQueue to remove by key and by addr
        return (addr.toString() + host).equals(obj) || key.equals(obj);
    }

    public int compareTo(PersistentConn o) {
        return (int) (timeoutTs - o.timeoutTs);
    }

    public boolean isTimeout(long now) {
        return timeoutTs < now;
    }

    public String toString() {
        return addr + "; timeout=" + (timeoutTs - System.currentTimeMillis()) + "ms";
    }
}
