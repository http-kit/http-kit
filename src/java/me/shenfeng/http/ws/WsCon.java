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
    private final SelectionKey key;
    private final Queue<SelectionKey> pendings;
    private List<IFn> listeners = new ArrayList<IFn>(1);

    public void addListener(IFn fn) {
        synchronized (listeners) {
            listeners.add(fn);
        }
    }

    public void send(String msg) {
        ByteBuffer buffer = WSEncoder.encode(msg);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(buffer);
        pendings.add(key);
        key.selector().wakeup();
    }

    public void onText(String mesg) {
        synchronized (listeners) {
            for (IFn l : listeners) {
                l.invoke(mesg);
            }
        }
    }

    public WsCon(SelectionKey key, Queue<SelectionKey> pendings) {
        this.key = key;
        this.pendings = pendings;
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return "WsCon[" + s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress();
    }
}
