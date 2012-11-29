package me.shenfeng.http.ws;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import clojure.lang.IFn;

public class WsCon {
    final SelectionKey key;
    final Queue<SelectionKey> pendings;
    final List<IFn> onRecieveListeners = new ArrayList<IFn>(1);
    final List<IFn> onCloseListeners = new ArrayList<IFn>(1);
    private volatile boolean isClosedRuned = false;

    public WsCon(SelectionKey key, Queue<SelectionKey> pendings) {
        this.key = key;
        this.pendings = pendings;
    }

    public void addOnCloseListener(IFn fn) {
        synchronized (onCloseListeners) {
            onCloseListeners.add(fn);
        }
    }

    public void addRecieveListener(IFn fn) {
        synchronized (onRecieveListeners) {
            onRecieveListeners.add(fn);
        }
    }

    public void clientClosed(int status) {
        onClose(status);
    }

    private void onClose(int status) {
        if (!isClosedRuned) {
            isClosedRuned = true;
            IFn[] listeners;
            synchronized (onCloseListeners) {
                listeners = new IFn[onCloseListeners.size()];
                listeners = onCloseListeners.toArray(listeners);
            }
            for (IFn l : listeners) {
                l.invoke(status);
            }
        }
    }

    public void messageRecieved(final String mesg) {
        IFn[] listeners;
        synchronized (onRecieveListeners) {
            listeners = new IFn[onRecieveListeners.size()];
            listeners = onRecieveListeners.toArray(listeners);
        }
        for (IFn l : listeners) {
            l.invoke(mesg);
        }
    }

    public void send(final String msg) {
        ByteBuffer buffer = WSEncoder.encode(msg);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(buffer);
        pendings.add(key);
        key.selector().wakeup();
    }

    public void serverClose() {
        serverClose(CloseFrame.CLOSE_NORMAL);
    }

    public void serverClose(int status) {
        ByteBuffer s = ByteBuffer.allocate(2).putShort((short) status);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, s.array()));
        atta.closeOnfinish = true;
        pendings.add(key);
        key.selector().wakeup();
        onClose(status);
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return "WsCon[" + s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress() + "]";
    }
}
