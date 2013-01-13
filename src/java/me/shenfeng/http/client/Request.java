package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.getServerAddr;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.PriorityQueue;

public class Request implements Comparable<Request> {

    InetSocketAddress addr;
    SocketChannel ch;

    final Decoder decoder;
    final ByteBuffer request; // HTTP request

    private final PriorityQueue<Request> clients;

    public final int timeOutMs;

    private long timeoutTs;
    private boolean connected = false;

    public Request(ByteBuffer request, IRespListener handler, int timeOutMs, URI url,
            PriorityQueue<Request> clients) {
        this.decoder = new Decoder(handler);
        this.timeOutMs = timeOutMs;
        this.request = request;
        this.clients = clients;
        try {
            addr = getServerAddr(url); // Maybe slow
        } catch (UnknownHostException e) {
            decoder.listener.onThrowable(e);
        }
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
        closeQuiety(ch);
        decoder.listener.onCompleted();
    }

    public boolean connected() {
        return connected;
    }

    public void setConnected() {
        connected = true;
    }

    public boolean isTimeout(long now) {
        return timeoutTs > now;
    }

    public void finish(Throwable t) {
        closeQuiety(ch);
        clients.remove(this);
        decoder.listener.onThrowable(t);
    }

    public int compareTo(Request o) {
        return (int) (timeoutTs - o.timeoutTs);
    }
}
