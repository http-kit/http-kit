package org.httpkit.server;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import org.httpkit.DynamicBytes;
import org.httpkit.HeaderMap;
import org.httpkit.HttpVersion;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.server.ClojureRing.*;
import static org.httpkit.server.WSDecoder.*;

@SuppressWarnings({"unchecked"})
public class AsyncChannel {

    private final SelectionKey key;
    private final HttpServer server;

    final public AtomicBoolean closedRan = new AtomicBoolean();
    final private AtomicReference<IFn> closeHandler = new AtomicReference<>(null);

    final private AtomicReference<IFn> receiveHandler = new AtomicReference<>(null);
    final private AtomicReference<IFn> pingHandler = new AtomicReference<>(null);

    private HttpRequest request;     // package private, for http 1.0 keep-alive

    // streaming
    private volatile boolean headerSent = false;

    // messages sent from a WebSocket client should be handled orderly by server
    // Changed from a Single Thread(IO event thread), no volatile needed
    LinkingRunnable serialTask;

    public AsyncChannel(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void reset(HttpRequest request) {
        this.request = request;
        serialTask = null;

        headerSent = false;
        closedRan.set(false);
        closeHandler.set(null);
        receiveHandler.set(null);
        pingHandler.set(null);
    }

    private static final byte[] finalChunkBytes = "0\r\n\r\n".getBytes();
    private static final byte[] newLineBytes = "\r\n".getBytes();

    private static ByteBuffer chunkSize(int size) {
        String s = Integer.toHexString(size) + "\r\n";
        return ByteBuffer.wrap(s.getBytes());
    }

    // Write first HTTP header and [first chunk data]? to client
    private void firstWrite(Object data, boolean close) throws IOException {
        ByteBuffer buffers[];
        int status = 200;
        Object body = data;
        HeaderMap headers;
        if (data instanceof Map) {
            Map<Keyword, Object> resp = (Map<Keyword, Object>) data;
            headers = HeaderMap.camelCase((Map) resp.get(HEADERS));
            status = getStatus(resp);
            body = resp.get(BODY);
        } else {
            headers = new HeaderMap();
        }

        if (headers.isEmpty()) { // default 200 and text/html
            headers.put("Content-Type", "text/html; charset=utf-8");
        }

        if (request.isKeepAlive && request.version == HttpVersion.HTTP_1_0) {
            headers.put("Connection", "Keep-Alive");
        }

        if (close) { // normal response, Content-Length. Every http client understand it
            buffers = HttpEncode(status, headers, body, server.serverHeader);
        } else {
            if (request.version == HttpVersion.HTTP_1_1) {
                headers.put("Transfer-Encoding", "chunked"); // first chunk
            }
            ByteBuffer[] bb = HttpEncode(status, headers, body, server.serverHeader);
            if (body == null) {
                buffers = bb;
            } else {
                buffers = new ByteBuffer[]{
                        bb[0], // header
                        chunkSize(bb[1].remaining()), // chunk size
                        bb[1], // chunk data
                        ByteBuffer.wrap(newLineBytes) // terminating CRLF sequence
                };
            }
        }
        if (close) {
            onClose(0);
        }
        server.tryWrite(key, !close, buffers);
    }

    // for streaming, send a chunk of data to client
    private void writeChunk(Object body, boolean close) throws IOException {
        if (body instanceof Map) { // only get body if a map
            body = ((Map<Keyword, Object>) body).get(BODY);
        }
        if (body != null) { // null is ignored
            ByteBuffer t = bodyBuffer(body);
            if (t.hasRemaining()) {
                ByteBuffer[] buffers = new ByteBuffer[]{
                        chunkSize(t.remaining()),
                        t,  // actual data
                        ByteBuffer.wrap(newLineBytes) // terminating CRLF sequence
                };
                server.tryWrite(key, !close, buffers);
            }
        }
        if (close) {
            serverClose(0);
        }
    }

    public void setReceiveHandler(IFn fn) {
        if (!receiveHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("receive handler exist: " + receiveHandler);
        }
    }

    public void setPingHandler(IFn fn) {
        if (!pingHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("ping handler exist: " + pingHandler);
        }
    }

    public void messageReceived(final Object mesg) {
        IFn f = receiveHandler.get();
        if (f != null) {
            f.invoke(mesg); // byte[] or String
        }
    }

    public void pingReceived(final byte[] mesg) {
        IFn f = pingHandler.get();
        if (f != null) {
            f.invoke(mesg);
        }
    }

    public void sendHandshake(Map<String, Object> headers) {
        HeaderMap map = HeaderMap.camelCase(headers);
        server.tryWrite(key, HttpEncode(101, map, null));
    }

    public boolean hasCloseHandler() {
        return closeHandler.get() != null;
    }

    public void setCloseHandler(IFn fn) {
        if (!closeHandler.compareAndSet(null, fn)) { // only once
            throw new IllegalStateException("close handler exist: " + closeHandler);
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
            server.tryWrite(key, WsEncode(OPCODE_CLOSE, ByteBuffer.allocate(2)
                    .putShort((short) status).array()));
        } else {
            server.tryWrite(key, false, ByteBuffer.wrap(finalChunkBytes));
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
                server.tryWrite(key, WsEncode(OPCODE_TEXT, ((String) data).getBytes(UTF_8)));
            } else if (data instanceof byte[]) {
                server.tryWrite(key, WsEncode(OPCODE_BINARY, (byte[]) data));
            } else if (data instanceof InputStream) {
                DynamicBytes bytes = readAll((InputStream) data);
                server.tryWrite(key, WsEncode(OPCODE_BINARY, bytes.get(), bytes.length()));
            } else if (data != null) { // ignore null
                String mesg = "send! called with data: " + data.toString() +
                        "(" + data.getClass() + "), but only string, byte[], InputStream expected";
                throw new IllegalArgumentException(mesg);
            }

            if (close) {
                serverClose(1000);
            }
        } else {
            if (headerSent) {  // HTTP Streaming
                writeChunk(data, close);
            }
            else {
                headerSent = true;
                firstWrite(data, close);
            }
        }
        return true;
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress();
    }

    public boolean isWebSocket() {
        return key.attachment() instanceof WsAtta;
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
    // 1004 is Reserved
    static Keyword K_WS_1005 = Keyword.intern("no-status-received");
    static Keyword K_WS_1006 = Keyword.intern("abnormal");
    static Keyword K_WS_1007 = Keyword.intern("invalid-payload-data");
    static Keyword K_WS_1008 = Keyword.intern("policy-violation");
    static Keyword K_WS_1009 = Keyword.intern("message-too-big");
    static Keyword K_WS_1010 = Keyword.intern("mandatory-extension");
    static Keyword K_WS_1011 = Keyword.intern("internal-server-error");
    // 1012 - 1014 are undefined
    static Keyword K_WS_1015 = Keyword.intern("tls-handshake");
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
            case 1005:
                return K_WS_1005;
            case 1006:
                return K_WS_1006;
            case 1007:
                return K_WS_1007;
            case 1008:
                return K_WS_1008;
            case 1009:
                return K_WS_1009;
            case 1010:
                return K_WS_1010;
            case 1011:
                return K_WS_1011;
            case 1015:
                return K_WS_1015;
            default:
                return K_UNKNOWN;
        }
    }
}
