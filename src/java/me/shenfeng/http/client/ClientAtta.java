package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.getServerAddr;
import static me.shenfeng.http.client.ConnectionState.DIRECT_CONNECT;
import static me.shenfeng.http.client.ConnectionState.SOCKS_VERSION_AUTH;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientAtta {
    InetSocketAddress addr;
    HttpClientDecoder decoder;
    ByteBuffer request; // http request
    long lastActiveTime;
    SocketChannel ch;
    ConnectionState state;
    URI url; // save for socks proxy know target addr

    // for timeout check
    boolean finished = false;

    public ClientAtta(ByteBuffer request, IRespListener handler, Proxy proxy,
            URI url) {
        if (proxy.type() == Type.SOCKS) {
            state = SOCKS_VERSION_AUTH;
            addr = (InetSocketAddress) proxy.address();
        } else {
            state = DIRECT_CONNECT;
            try {
                addr = getServerAddr(url);
            } catch (UnknownHostException e) {
                decoder.listener.onThrowable(e);
            }
        }
        this.url = url;
        decoder = new HttpClientDecoder(handler);
        this.request = request;
    }

    public void finish() {
        // http keep alive is not implemented, for simplicity
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
