package org.httpkit.client;

import org.httpkit.PriorityQueue;

import javax.net.ssl.SSLException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.atomic.AtomicBoolean;

public class Request implements Comparable<Request> {

    final SocketAddress addr;
    final String host;
    final Decoder decoder;
    final ByteBuffer[] request; // HTTP request
    private final int[] requestPositions;
    final RequestConfig cfg;
    private final PriorityQueue<Request> clients; // update timeout

    // is modify from the loop thread. ensure only called once
    private final AtomicBoolean isDone = new AtomicBoolean(false);

    boolean isReuseConn = false; // a reused socket sent the request
    private boolean isConnected = false;
    SelectionKey key; // for timeout, close connection

    private long timeoutTs; // future time this request timeout, ms
    private boolean timeoutStarted;

    public Request(SocketAddress addr, String host, ByteBuffer[] request, IRespListener handler,
                   PriorityQueue<Request> clients, RequestConfig config) {
        this.cfg = config;
        this.decoder = new Decoder(handler, config.method);
        this.request = request;
        this.requestPositions = new int[request.length];
        for (int i = 0; i < request.length; i++) {
            requestPositions[i] = request[i].position();
        }
        this.clients = clients;
        this.addr = addr;
        this.host = host;
        this.timeoutTs = config.connTimeout + System.currentTimeMillis();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean isConnected) {
        if (this.isConnected != isConnected) {
          this.isConnected = isConnected;

          // Switch timeout type
          long timeout = isConnected ? cfg.idleTimeout : cfg.connTimeout;
          timeoutTs = timeout + System.currentTimeMillis();
          if (timeoutStarted) {
              clients.remove(this);
              clients.offer(this);
          }
        }
    }

    public void onProgress(long now) {
        if (isDone.get()) {
            return;
        }
        long timeout = isConnected ? cfg.idleTimeout : cfg.connTimeout;
        timeoutTs = timeout + now;
        if (timeoutStarted) {
            clients.remove(this);
            clients.offer(this);
        }
    }

    public void startTimeout() {
        if (!timeoutStarted && !isDone.get()) {
            timeoutStarted = true;
            clients.offer(this);
        }
    }

    public void finish() {
        if (!isDone.compareAndSet(false, true))
            return;
        clients.remove(this);
        decoder.listener.onCompleted();
    }

    public boolean isTimeout(long now) {
        return timeoutTs < now;
    }

    public long toTimeout(long now) {
        return Math.max(timeoutTs - now, 0L);
    }

    public void finish(Throwable t) {
        if (!isDone.compareAndSet(false, true))
            return;
        clients.remove(this);
        decoder.listener.onThrowable(t);
    }

    public int compareTo(Request o) {
        return Long.compare(timeoutTs, o.timeoutTs);
    }

    public boolean isDone() {
        return isDone.get();
    }

    public void recycle(Request old) throws SSLException {
        this.key = old.key;
        isReuseConn = true;
        startTimeout();
        setConnected(true); // since we're re-using a keepalive conn, set the timeout as if we're already connected
    }

    public void unrecycle() {
        for (int i = 0; i < request.length; i++) {
            request[i].position(requestPositions[i]);
        }
        isReuseConn = false;
        setConnected(false);
    }
}
