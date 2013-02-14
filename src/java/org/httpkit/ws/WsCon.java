package org.httpkit.ws;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;

import org.httpkit.server.ClojureRing;
import org.httpkit.server.HttpServer;

import clojure.lang.IFn;

public class WsCon {
    final SelectionKey key;
    final List<IFn> receiveListeners = new ArrayList<IFn>(1);
    final List<IFn> closeListeners = new ArrayList<IFn>(1);
    private volatile boolean isClosedRan = false;

    final private HttpServer server;

    public WsCon(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void addOnCloseListener(IFn fn) {
        synchronized (closeListeners) {
            closeListeners.add(fn);
        }
    }

    public void addReceiveListener(IFn fn) {
        synchronized (receiveListeners) {
            receiveListeners.add(fn);
        }
    }

    public void clientClosed(int status) {
        onClose(status);
    }

    private void onClose(int status) {
        if (!isClosedRan) {
            isClosedRan = true;
            IFn[] listeners;
            synchronized (closeListeners) {
                listeners = new IFn[closeListeners.size()];
                listeners = closeListeners.toArray(listeners);
            }
            for (IFn l : listeners) {
                l.invoke(status);
            }
        }
    }

    public void messageReceived(final String mesg) {
        IFn[] listeners;
        synchronized (receiveListeners) {
            listeners = new IFn[receiveListeners.size()];
            listeners = receiveListeners.toArray(listeners);
        }
        for (IFn l : listeners) {
            l.invoke(mesg);
        }
    }

    public void send(final String mesg) {
        ByteBuffer buffer = WSEncoder.encode(mesg);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(buffer);
        server.queueWrite(key);
    }

    public void serverClose() {
        serverClose(CloseFrame.CLOSE_NORMAL);
    }

    public void serverClose(int status) {
        ByteBuffer s = ByteBuffer.allocate(2).putShort((short) status);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, s.array()));
        atta.closeOnfinish = true;
        server.queueWrite(key);
        onClose(status);
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return "WsCon[" + s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress() + "]";
    }

    public void sendHandshake(Map<String, Object> headers) {
        ByteBuffer[] buffers = ClojureRing.encode(101, headers, null);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(buffers);
        server.queueWrite(key);
    }
}
