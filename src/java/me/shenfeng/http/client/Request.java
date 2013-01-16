package me.shenfeng.http.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

import me.shenfeng.http.HttpMethod;

public class Request implements Comparable<Request> {

    final InetSocketAddress addr;
    final Decoder decoder;
    final ByteBuffer request; // HTTP request
    private final PriorityQueue<Request> clients;
    public final int timeOutMs;

    private boolean isDone = false; // for only call once

    SelectionKey key; // for timeout

    private long timeoutTs;
    private boolean connected = false;

    public Request(InetSocketAddress addr, ByteBuffer request, IRespListener handler,
            PriorityQueue<Request> clients, int timeOutMs, HttpMethod method) {
        this.decoder = new Decoder(handler, method);
        this.timeOutMs = timeOutMs;
        this.request = request;
        this.clients = clients;
        this.addr = addr;
        timeoutTs = this.timeOutMs + System.currentTimeMillis();
    }

    public void onProgress(long now) {
        clients.remove(this);
        timeoutTs = this.timeOutMs + now;
        clients.offer(this);
    }

    public void finish() {
        clients.remove(this);
        if (isDone)
            return;
        isDone = true;
        decoder.listener.onCompleted();
    }

    public boolean connected() {
        return connected;
    }

    public void setConnected() {
        connected = true;
    }

    public boolean isTimeout(long now) {
        return timeoutTs < now;
    }

    public void finish(Throwable t) {
        clients.remove(this);
        if (isDone)
            return;
        isDone = true;
        decoder.listener.onThrowable(t);
    }

    public int compareTo(Request o) {
        return (int) (timeoutTs - o.timeoutTs);
    }
}
