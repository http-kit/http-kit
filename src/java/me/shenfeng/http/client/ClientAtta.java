package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.getServerAddr;
import static me.shenfeng.http.client.ClientConnState.DIRECT_CONNECTING;
import static me.shenfeng.http.client.ClientConnState.SOCKS_CONNECTTING;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientAtta {
    InetSocketAddress addr;
    final ClientDecoder decoder;
    final ByteBuffer request; // http request
    long lastActiveTime;
    SocketChannel ch;
    ClientConnState state;
    final URI url; // save for socks proxy know target addr

    // for timeout check
    boolean finished = false;

    public ClientAtta(ByteBuffer request, IRespListener handler, Proxy proxy, URI url) {
        decoder = new ClientDecoder(handler);
        this.url = url;
        this.request = request;
        if (proxy.type() == Type.SOCKS) {
            state = SOCKS_CONNECTTING;
            addr = (InetSocketAddress) proxy.address();
        } else {
            state = DIRECT_CONNECTING;
            try {
                addr = getServerAddr(url);
            } catch (UnknownHostException e) {
                decoder.listener.onThrowable(e);
            }
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
