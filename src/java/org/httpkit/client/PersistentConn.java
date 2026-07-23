package org.httpkit.client;

import java.net.SocketAddress;
import java.nio.channels.SelectionKey;

public class PersistentConn implements Comparable<PersistentConn> {
    private final long timeoutTs;
    private final String host;
    private final boolean secure;
    public final SocketAddress addr;
    public final SelectionKey key;

    public PersistentConn(long timeoutTs, SocketAddress addr, String host,
                          boolean secure, SelectionKey key) {
        this.timeoutTs = timeoutTs;
        this.addr = addr;
        this.host = host;
        this.secure = secure;
        this.key = key;
    }

    private static boolean equalsToRequest(PersistentConn pc, Request req) {
        return pc.addr.equals(req.addr) && pc.host.equals(req.host)
                && pc.secure == (req instanceof HttpsRequest);
    }

    private static boolean equalsToSelectionKey(PersistentConn pc, SelectionKey key) {
        return pc.key.equals(key);
    }

    private static boolean equalsToPersistentConn(PersistentConn pc1, PersistentConn pc2) {
        return pc1.addr.equals(pc2.addr) && pc1.host.equals(pc2.host)
                && pc1.secure == pc2.secure;
    }

    public boolean equals(Object obj) {
        // for PriorityQueue to remove by key and by addr
        if (obj instanceof Request) {
            return equalsToRequest(this, (Request) obj);
        } else if (obj instanceof SelectionKey) {
            return equalsToSelectionKey(this, (SelectionKey) obj);
        } else if (obj instanceof PersistentConn) {
            return equalsToPersistentConn(this, (PersistentConn) obj);
        } else {
            return (addr.toString() + host).equals(obj) || key.equals(obj);
        }
    }

    public int compareTo(PersistentConn o) {
        return Long.compare(timeoutTs, o.timeoutTs);
    }

    public boolean isTimeout(long now) {
        return timeoutTs < now;
    }

    public String toString() {
        return addr + "; timeout=" + (timeoutTs - System.currentTimeMillis()) + "ms";
    }
}
