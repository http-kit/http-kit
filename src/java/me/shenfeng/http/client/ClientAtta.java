package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.closeQuiety;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientAtta {
    Proxy proxy;
    InetSocketAddress addr;
    HttpClientDecoder decoder;
    ByteBuffer request;
    long lastActiveTime;
    SocketChannel ch;

    // for timeout check
    boolean finished = false;

    public ClientAtta(ByteBuffer request, InetSocketAddress server,
            IRespListener handler, Proxy proxy) {
        this.proxy = proxy;
        this.addr = server;
        decoder = new HttpClientDecoder(handler);
        this.request = request;
    }

    public void finish() {
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
