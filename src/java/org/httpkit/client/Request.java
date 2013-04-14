package org.httpkit.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

import org.httpkit.PriorityQueue;

import javax.net.ssl.SSLException;

public class Request implements Comparable<Request> {

    final InetSocketAddress addr;
    final Decoder decoder;
    final ByteBuffer[] request; // HTTP request
    final HttpRequestConfig cfg;
    private final PriorityQueue<Request> clients; // update timeout

    // is modify from the loop thread. ensure only called once
    private boolean isDone = false;

    public boolean isReuseConn = false; // a reused socket sent the request
    public boolean isConnected = false;

    SelectionKey key; // for timeout, close connection

    private long timeoutTs; // future time this request timeout, ms

    public Request(InetSocketAddress addr, ByteBuffer[] request, IRespListener handler,
                   PriorityQueue<Request> clients, HttpRequestConfig config) {
        this.cfg = config;
        this.decoder = new Decoder(handler, config.method);
        this.request = request;
        this.clients = clients;
        this.addr = addr;
        this.timeoutTs = config.timeout + System.currentTimeMillis();
    }

    public void onProgress(long now) {
        // update time
        clients.remove(this);
        timeoutTs = cfg.timeout + now;
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

    public void recycle(Request old) throws SSLException {
        this.key = old.key;
    }
}

