package me.shenfeng.http.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class Request implements Comparable<Request> {

    final InetSocketAddress addr;
    final Decoder decoder;
    final ByteBuffer request; // HTTP request
    private final PriorityQueue<Request> clients;
    public final int timeOutMs;

    private long timeoutTs;
    private boolean connected = false;

    public Request(InetSocketAddress addr, ByteBuffer request, IRespListener handler,
            PriorityQueue<Request> clients, int timeOutMs) {
        this.decoder = new Decoder(handler);
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
        // HTTP keep-alive is not implemented, for simplicity
        clients.remove(this);
        // closeQuiety(ch);
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
        // closeQuiety(ch);
        clients.remove(this);
        decoder.listener.onThrowable(t);
    }

    public int compareTo(Request o) {
        return (int) (timeoutTs - o.timeoutTs);
    }
}
