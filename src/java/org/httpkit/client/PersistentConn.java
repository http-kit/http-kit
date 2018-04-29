package org.httpkit.client;

import javafx.util.Pair;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;

public class PersistentConn implements Comparable<PersistentConn> {
    private final long timeoutTs;
    public final InetSocketAddress addr;
    public final SelectionKey key;
    public final InetSocketAddress realAddr;

    public PersistentConn(long timeoutTs, InetSocketAddress addr, InetSocketAddress realAddr, SelectionKey key) {
        this.timeoutTs = timeoutTs;
        this.addr = addr;
        this.realAddr = realAddr;
        this.key = key;
    }

    public boolean equals(Object obj) {
        // for PriorityQueue to remove by key and by addr
        if (obj instanceof Pair) {
            Pair<InetSocketAddress, InetSocketAddress> p = (Pair<InetSocketAddress, InetSocketAddress>)obj;
            return p.getKey().equals(addr) && p.getValue().equals(realAddr);
        }
        return key.equals(obj);
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
