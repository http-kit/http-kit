package org.httpkit.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

import org.httpkit.HttpMethod;
import org.httpkit.PriorityQueue;

public class Request implements Comparable<Request> {

    final InetSocketAddress addr;
    final Decoder decoder;
    final ByteBuffer[] request; // HTTP request
    private final PriorityQueue<Request> clients; // update timeout
    public final int timeOutMs;

    private boolean isDone = false; // ensure only call once

    public boolean isKeepAlived = false; // a reused socket sent the request
    public boolean isConnected = false;

    SelectionKey key; // for timeout, close connection

    private long timeoutTs; // future time this request timeout, ms

    public Request(InetSocketAddress addr, ByteBuffer[] request, IRespListener handler,
            PriorityQueue<Request> clients, int timeOutMs, HttpMethod method) {
        this.decoder = new Decoder(handler, method);
        this.timeOutMs = timeOutMs;
        this.request = request;
        this.clients = clients;
        this.addr = addr;
        timeoutTs = this.timeOutMs + System.currentTimeMillis();
    }

    public void onProgress(long now) {
        // update time
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
