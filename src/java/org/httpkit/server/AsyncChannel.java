package org.httpkit.server;

import static org.httpkit.server.ClojureRing.*;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.httpkit.HttpUtils;
import org.httpkit.ws.WSDecoder;
import org.httpkit.ws.WSEncoder;
import org.httpkit.ws.WsServerAtta;

import clojure.lang.IFn;
import clojure.lang.Keyword;

@SuppressWarnings({ "unchecked" })
public class AsyncChannel {
    private final SelectionKey key;
    private final HttpServer server;

    final public AtomicReference<Boolean> closedRan = new AtomicReference<Boolean>(false);
    final AtomicReference<IFn> closeHandler = new AtomicReference<IFn>(null);

    // websocket
    final AtomicReference<IFn> receiveHandler = new AtomicReference<IFn>(null);

    // streaming
    private volatile boolean isHeaderSent = false;

    // messages sent from a websocket client should be handled orderly by server
    LinkingRunnable serialTask;

    public AsyncChannel(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void reset() {
        closedRan.lazySet(false);
        closeHandler.lazySet(null);
        receiveHandler.lazySet(null);
        isHeaderSent = false;
        serialTask = null;
    }

    // -------------- streaming --------------------

    private static final byte[] finalChunkBytes = "0\r\n\r\n".getBytes();
    private static final byte[] newLineBytes = "\r\n".getBytes();

    private static ByteBuffer chunkSize(int size) {
        String s = Integer.toHexString(size) + "\r\n";
        return ByteBuffer.wrap(s.getBytes());
    }

    private void firstWrite(Object data, boolean isFinal) throws IOException {
        ByteBuffer buffers[];
        int status = 200;
        Object body = data;
        Map<String, Object> headers = new TreeMap<String, Object>();
        if (data instanceof Map) {
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
        if (isFinal) {
            onClose(0);
        }
        write(buffers);
    }

    private void writeChunk(Object body, boolean closeAfterSent) throws IOException {
        if (body instanceof Map) { // only get body if a map
            body = ((Map<Keyword, Object>) body).get(BODY);
        }
        if (body != null) { // null is ignored
            ByteBuffer buffers[];
            ByteBuffer t = HttpUtils.bodyBuffer(body);
            if(t.hasRemaining()) {
                ByteBuffer size = chunkSize(t.remaining());
                buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(newLineBytes) };
                write(buffers);
            }
        }
        if(closeAfterSent) {
            serverClose(0);
        }
    }

    // ---------------------------websocket-------------------------------------

    public void setReceiveHandler(IFn fn) {
        if (!receiveHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("receive handler exist: " + receiveHandler.get());
        }
    }

    public void messageReceived(final Object mesg) {
        IFn f = receiveHandler.get();
        if (f != null) {
            f.invoke(mesg);
        }
    }

    private void sendTextFrame(final String mesg) {
        write(WSEncoder.encode(mesg));
    }

    private void sendBinaryFrame(final byte[] data) {
        write(WSEncoder.encode(WSDecoder.OPCODE_BINARY, data));
    }

    public void sendHandshake(Map<String, Object> headers) {
        write(encode(101, headers, null));
    }


    public void setCloseHandler(IFn fn) {
        if (!closeHandler.compareAndSet(null, fn)) { // only once
            throw new IllegalStateException("close handler exist: " + closeHandler.get());
        }
        if (closedRan.get()) { // no handler, but already closed
            fn.invoke(K_UNKNOWN);
        }
    }

    public void onClose(int status) {
        if (closedRan.compareAndSet(false, true)) {
            IFn f = closeHandler.get();
            if (f != null) {
                f.invoke(closeReason(status));
            }
        }
    }

    public boolean serverClose(int status) {
        if (!closedRan.compareAndSet(false, true)) {
            return false; // already closed
        }
        if (isWebSocket()) {
            write(WSEncoder.encode(WSDecoder.OPCODE_CLOSE,
                    ByteBuffer.allocate(2).putShort((short) status).array()));
        } else {
            write(ByteBuffer.wrap(finalChunkBytes));
        }
        IFn f = closeHandler.get();
        if (f != null) {
            f.invoke(closeReason(0)); // server close is 0
        }
        return true;
    }

    public boolean send(Object data, boolean closeAfterSent) throws IOException {
        if (closedRan.get()) {
            return false;
        }

        if (isWebSocket()) {
            if (data instanceof Map) { // only get the :body if map
                Object tmp = ((Map<Keyword, Object>) data).get(BODY);
                if (tmp != null) { // save contains(BODY) && get(BODY)
                    data = tmp;
                }
            }

            if (data instanceof String) { // null is not allowed
                sendTextFrame((String) data);// websocket
            } else if (data instanceof byte[]) {
                sendBinaryFrame((byte[]) data);
            } else {                
                if (data != null) { // null is ignored
                    throw new IllegalArgumentException(
                        "websocket only accept string and byte[], get" + data);
                }
            }

            if (closeAfterSent) {
                serverClose(1000);
            }
        } else {
            if (isHeaderSent) {
                writeChunk(data, closeAfterSent);
            } else {
                firstWrite(data, closeAfterSent);
                isHeaderSent = true;
            }
        }
        return true;
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress();
    }

    private void write(ByteBuffer... buffers) {
        ((ServerAtta) key.attachment()).addBuffer(buffers);
        server.queueWrite(key);
    }

    public boolean isWebSocket() {
        return key.attachment() instanceof WsServerAtta;
    }

    public boolean isClosed() {
        return closedRan.get();
    }

    // closed by server
    static Keyword K_BY_SERVER = Keyword.intern("server-close");
    // general close status
    static Keyword K_CLIENT_CLOSED = Keyword.intern("http-client-close");

    // 1000 indicates a normal closure
    static Keyword K_WS_1000 = Keyword.intern("ws-normal");
    // 1001 indicates that an endpoint is "going away"
    static Keyword K_WS_1001 = Keyword.intern("ws-going-away");
    // 1002 indicates that an endpoint is terminating the connection due to a
    // protocol error
    static Keyword K_WS_1002 = Keyword.intern("ws-protocol-error");
    // 1003 indicates that an endpoint is terminating the connection
    // because it has received a type of data it cannot accept
    static Keyword K_WS_1003 = Keyword.intern("ws-unsupported");

    static Keyword K_UNKNOWN = Keyword.intern("ws-unknown");

    private static Keyword closeReason(int status) {
        switch (status) {
        case 0:
            return K_BY_SERVER;
        case -1:
            return K_CLIENT_CLOSED;
        case 1000:
            return K_WS_1000;
        case 1001:
            return K_WS_1001;
        case 1002:
            return K_WS_1002;
        case 1003:
            return K_WS_1003;
        default:
            return K_UNKNOWN;
        }
    }
}
