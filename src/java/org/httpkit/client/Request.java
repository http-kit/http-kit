package org.httpkit.client;

import javafx.util.Pair;
import org.httpkit.PriorityQueue;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class Request implements Comparable<Request> {
    final InetSocketAddress addr;
    final InetSocketAddress realAddr;
    final Decoder decoder;
    final ByteBuffer[] request; // HTTP request
    final RequestConfig cfg;
    protected final PriorityQueue<Request> clients; // update timeout

    // is modify from the loop thread. ensure only called once
    public boolean isDone = false;

    boolean isReuseConn = false; // a reused socket sent the request
    private boolean isConnected = false;
    SelectionKey key; // for timeout, close connection
    public URI uri;
    private long timeoutTs; // future time this request timeout, ms

    public Pair<InetSocketAddress, InetSocketAddress> addrKey() {
        return new Pair<InetSocketAddress, InetSocketAddress>(addr, realAddr);
    }

    public Request(InetSocketAddress addr, InetSocketAddress realAddr,
                   ByteBuffer[] request, IRespListener handler,
                   PriorityQueue<Request> clients, RequestConfig config, URI uri) {
        this.cfg = config;
        this.decoder = new Decoder(handler, config.method);
        this.request = request;
        this.clients = clients;
        this.addr = addr;
        this.realAddr = realAddr;
        this.timeoutTs = config.connTimeout + System.currentTimeMillis();
        this.uri = uri;
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

