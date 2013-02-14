package org.httpkit.server;

import static org.httpkit.server.ClojureRing.*;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.httpkit.HttpUtils;
import org.httpkit.ws.*;

import clojure.lang.IFn;
import clojure.lang.Keyword;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class AsyncChannel {

    private final SelectionKey key;
    final private HttpServer server;
    private AtomicReference<Boolean> closedRan = new AtomicReference<Boolean>(false);
    List<IFn> closeListeners = null;

    // streaming
    private volatile boolean initialWrite = true;
    private volatile boolean finalWritten = false;

    // websocket
    List<IFn> receiveListeners = null;

    public AsyncChannel(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    // -------------- streaming --------------------
    private static final byte[] finalChunkBytes = "0\r\n\r\n".getBytes();
    private static final byte[] finalChunkBytes2 = "\r\n0\r\n\r\n".getBytes();
    private static final byte[] newLineBytes = "\r\n".getBytes();

    public void writeChunk(Object data, boolean isFinal) throws IOException {
        if (finalWritten) {
            throw new IllegalStateException("final chunk has already been written");
        }
        if (isFinal) {
            finalWritten = true;
        }

        ByteBuffer buffers[];
        boolean isMap = (data instanceof Map);

        if (initialWrite) {
            int status = 200;
            Object body = data;
            Map<String, Object> headers = new TreeMap<String, Object>();
            if (isMap) {
                Map<Keyword, Object> resp = (Map<Keyword, Object>) data;
                headers = getHeaders(resp, false);
                status = getStatus(resp);
                body = resp.get(BODY);
            }

            if (headers.isEmpty()) { // default 200 and text/html
                headers.put("Content-Type", "text/html; charset=utf-8");
            }

            if (isFinal) { // normal response
                buffers = encode(status, headers, body);
            } else {
                headers.put("Transfer-Encoding", "chunked"); // first chunk
                ByteBuffer[] bb = encode(status, headers, body);
                if (body == null) {
                    buffers = bb;
                } else {
                    buffers = new ByteBuffer[] { bb[0], chunkSize(bb[1].remaining()), bb[1],
                            ByteBuffer.wrap(newLineBytes) };
                }
            }
        } else {
            Object body = isMap ? ((Map) data).get(BODY) : data;

            if (body != null) {
                ByteBuffer t = HttpUtils.bodyBuffer(body);
                ByteBuffer size = chunkSize(t.remaining());
                if (isFinal) {
                    buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(finalChunkBytes2) };
                } else {
                    buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(newLineBytes) };
                }

            } else {
                if (isFinal)
                    buffers = new ByteBuffer[] { ByteBuffer.wrap(finalChunkBytes) };
                else {
                    return; // strange, body is null, and not final
                }
            }
        }

        HttpServerAtta att = (HttpServerAtta) key.attachment();
        att.addBuffer(buffers);
        server.queueWrite(key);

        initialWrite = false;
    }

    // ---------------------------websocket-------------------------------------

    public void addReceiveListener(IFn fn) {
        synchronized (this) {
            if (receiveListeners == null) {
                receiveListeners = new ArrayList<IFn>(1);
            }
            receiveListeners.add(fn);
        }
    }

    public void messageReceived(final String mesg) {
        IFn[] listeners;
        synchronized (this) {
            if (receiveListeners == null) {
                return;
            }
            listeners = new IFn[receiveListeners.size()];
            listeners = receiveListeners.toArray(listeners);
        }
        for (IFn l : listeners) {
            l.invoke(mesg);
        }
    }

    public void send(final String mesg) {
        if (closedRan.get()) {
            throw new IllegalStateException("already closed");
        }
        write(WSEncoder.encode(mesg));
    }

    public void serverClose() {
        ByteBuffer s = ByteBuffer.allocate(2).putShort(CloseFrame.CLOSE_NORMAL);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.closeOnfinish = true;
        write(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, s.array()));
        onClose(CloseFrame.CLOSE_NORMAL);
    }

    public void sendHandshake(Map<String, Object> headers) {
        write(encode(101, headers, null));
    }

    // ----------------------shared--------------------------------------
    private static ByteBuffer chunkSize(int size) {
        String s = Integer.toHexString(size) + "\r\n";
        byte[] bytes = s.getBytes();
        return ByteBuffer.wrap(bytes);
    }

    public void clientClosed(int status) {
        onClose(status);
    }

    public void addOnCloseListener(IFn fn) {
        synchronized (this) {
            if (closeListeners == null) {
                closeListeners = new ArrayList<IFn>();
            }
            closeListeners.add(fn);
        }
    }

    private void onClose(int status) {
        if (!closedRan.compareAndSet(false, true)) {
            IFn[] listeners;
            synchronized (this) {
                if (closeListeners == null)
                    return;
                listeners = new IFn[closeListeners.size()];
                listeners = closeListeners.toArray(listeners);
            }
            for (IFn l : listeners) {
                l.invoke(status);
            }
        }
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return "AsycChannel[" + s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress()
                + "]";
    }

    private void write(ByteBuffer... buffers) {
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(buffers);
        server.queueWrite(key);
    }

}
