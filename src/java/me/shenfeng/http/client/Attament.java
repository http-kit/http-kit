package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.getServerAddr;
import static me.shenfeng.http.client.ConnState.DIRECT_CONNECTING;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

enum ConnState {
    DIRECT_CONNECTING, DIRECT_CONNECTED
}

public class Attament {

    InetSocketAddress addr;
    final Decoder decoder;
    final ByteBuffer request; // http request
    long lastActiveTime;
    SocketChannel ch;
    ConnState state;
    final int timeOutMs;

    // for timeout check
    volatile boolean finished = false;

    public Attament(ByteBuffer request, IRespListener handler, int timeOutMs, URI url) {
        decoder = new Decoder(handler);
        this.timeOutMs = timeOutMs;
        this.request = request;
        state = DIRECT_CONNECTING;
        try {
            addr = getServerAddr(url);
        } catch (UnknownHostException e) {
            decoder.listener.onThrowable(e);
        }
    }

    public void finish() {
        // HTTP keep-alive is not implemented, for simplicity
        closeQuiety(ch);
        finished = true;
        decoder.listener.onCompleted();
    }

    public void finish(Throwable t) {
        closeQuiety(ch);
        finished = true;
        decoder.listener.onThrowable(t);
    }
}
