package org.httpkit.client;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;

public class PersistentConn implements Comparable<PersistentConn> {
    private final long timeoutTs;
    public final String host;
    public final SelectionKey key;

    public PersistentConn(long timeoutTs, String host, SelectionKey key) {
        this.timeoutTs = timeoutTs;
        this.host = host;
        this.key = key;
    }

    public boolean equals(Object obj) {
        // for PriorityQueue to remove by key and by host
        return host.equals(obj) || key.equals(obj);
    }

    public int compareTo(PersistentConn o) {
        return (int) (timeoutTs - o.timeoutTs);
    }

    public boolean isTimeout(long now) {
        return timeoutTs < now;
    }

    public String toString() {
        return host + "; timeout=" + (timeoutTs - System.currentTimeMillis()) + "ms";
    }
}
