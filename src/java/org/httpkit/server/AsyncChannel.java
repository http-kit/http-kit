package org.httpkit.server;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.server.ClojureRing.*;
import static org.httpkit.ws.WSDecoder.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.httpkit.DynamicBytes;
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

    private static final byte[] finalChunkBytes = "0\r\n\r\n".getBytes();
    private static final byte[] newLineBytes = "\r\n".getBytes();

    private static ByteBuffer chunkSize(int size) {
        String s = Integer.toHexString(size) + "\r\n";
        return ByteBuffer.wrap(s.getBytes());
    }

    private void firstWrite(Object data, boolean close) throws IOException {
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

        if (close) { // normal response
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
        if (close) {
            onClose(0);
        }
        write(buffers);
    }

    private void writeChunk(Object body, boolean close) throws IOException {
        if (body instanceof Map) { // only get body if a map
            body = ((Map<Keyword, Object>) body).get(BODY);
        }
        if (body != null) { // null is ignored
            ByteBuffer buffers[];
            ByteBuffer t = bodyBuffer(body);
            if (t.hasRemaining()) {
                ByteBuffer size = chunkSize(t.remaining());
                buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(newLineBytes) };
                write(buffers);
            }
        }
        if (close) {
            serverClose(0);
        }
    }

    public void setReceiveHandler(IFn fn) {
        if (!receiveHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("receive handler exist: " + receiveHandler.get());
        }
    }

    public void messageReceived(final Object mesg) {
        IFn f = receiveHandler.get();
        if (f != null) {
            f.invoke(mesg); // byte[] or String
        }
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
                f.invoke(readable(status));
            }
        }
    }

    // also sent CloseFrame a final Chunk
    public boolean serverClose(int status) {
        if (!closedRan.compareAndSet(false, true)) {
            return false; // already closed
        }
        if (isWebSocket()) {
            write(WSEncoder.encode(OPCODE_CLOSE, ByteBuffer.allocate(2)
                    .putShort((short) status).array()));
        } else {
            write(ByteBuffer.wrap(finalChunkBytes));
        }
        IFn f = closeHandler.get();
        if (f != null) {
            f.invoke(readable(0)); // server close is 0
        }
        return true;
    }

    public boolean send(Object data, boolean close) throws IOException {
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
                write(WSEncoder.encode(OPCODE_TEXT, ((String) data).getBytes(UTF_8)));
            } else if (data instanceof byte[]) {
                write(WSEncoder.encode(OPCODE_BINARY, (byte[]) data));
            } else if (data instanceof InputStream) {
                DynamicBytes bytes = readAll((InputStream) data);
                write(WSEncoder.encode(OPCODE_BINARY, bytes.get(), bytes.length()));
            } else if (data != null) { // ignore null
                throw new IllegalArgumentException(
                        "only accept string, byte[], InputStream, get" + data);
            }

            if (close) {
                serverClose(1000);
            }
        } else {
            if (isHeaderSent) {
                writeChunk(data, close);
            } else {
                firstWrite(data, close);
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

    static Keyword K_BY_SERVER = Keyword.intern("server-close");
    static Keyword K_CLIENT_CLOSED = Keyword.intern("client-close");

    // http://datatracker.ietf.org/doc/rfc6455/?include_text=1
    // 7.4.1. Defined Status Codes
    static Keyword K_WS_1000 = Keyword.intern("normal");
    static Keyword K_WS_1001 = Keyword.intern("going-away");
    static Keyword K_WS_1002 = Keyword.intern("protocol-error");
    static Keyword K_WS_1003 = Keyword.intern("unsupported");
    static Keyword K_UNKNOWN = Keyword.intern("unknown");

    private static Keyword readable(int status) {
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
