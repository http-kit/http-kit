package org.httpkit.client;

import org.httpkit.PriorityQueue;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class Request implements Comparable<Request> {

    final InetSocketAddress addr;
    final Decoder decoder;
    final ByteBuffer[] request; // HTTP request
    final RequestConfig cfg;
    private final PriorityQueue<Request> clients; // update timeout

    // is modify from the loop thread. ensure only called once
    private boolean isDone = false;

    boolean isReuseConn = false; // a reused socket sent the request
    private boolean isConnected = false;
    SelectionKey key; // for timeout, close connection

    private long timeoutTs; // future time this request timeout, ms

    public Request(InetSocketAddress addr, ByteBuffer[] request, IRespListener handler,
                   PriorityQueue<Request> clients, RequestConfig config) {
        this.cfg = config;
        this.decoder = new Decoder(handler, config.method);
        this.request = request;
        this.clients = clients;
        this.addr = addr;
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
          clients.remove(this);
          timeoutTs = timeout + System.currentTimeMillis();
          clients.offer(this);
        }
    }

    public void onProgress(long now) {
        long timeout = isConnected ? cfg.idleTimeout : cfg.connTimeout;
        if (timeout + now - timeoutTs > 800) {
            // Extend timeout on activity
            clients.remove(this);
            timeoutTs = timeout + now;
            clients.offer(this);
        }
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

    public long toTimeout(long now) {
        return Math.max(timeoutTs - now, 0L);
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

