package org.httpkit.server;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import org.httpkit.DynamicBytes;
import org.httpkit.HeaderMap;
import org.httpkit.HttpMethod;
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
    private IFn closeHandler;
    private IFn closeRingHandler;

    final private AtomicReference<IFn> receiveHandler = new AtomicReference<>(null);
    final private AtomicReference<IFn> pingHandler = new AtomicReference<>(null);
    final private AtomicReference<IFn> pongHandler = new AtomicReference<>(null);
    final private Object closeLock = new Object();

    private int closeStatus;
    private int closeRingStatus;
    private String closeReason = "";

    private HttpRequest request;     // package private, for http 1.0 keep-alive

    // streaming
    private volatile boolean headerSent = false;
    private volatile boolean websocketUpgraded = false;

    // messages sent from a WebSocket client should be handled orderly by server
    // Changed from a Single Thread(IO event thread), no volatile needed
    LinkingRunnable serialTask;

    public AsyncChannel(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public synchronized void reset(HttpRequest request) {
        this.request = request;
        serialTask = null;

        headerSent = false;
        websocketUpgraded = false;
        synchronized (closeLock) {
            closedRan.set(false);
            closeHandler = null;
            closeRingHandler = null;
            closeStatus = 0;
            closeRingStatus = 0;
            closeReason = "";
        }
        receiveHandler.set(null);
        pingHandler.set(null);
        pongHandler.set(null);
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

        if (request.method == HttpMethod.HEAD || isBodyForbidden(status)) {
            close = true;
        }

        if (headers.isEmpty()) { // default 200 and text/html
            headers.put("Content-Type", "text/html; charset=utf-8");
        }

        if (isWebSocketCandidate() && !websocketUpgraded) {
            close = true;
            headers.putOrReplace("Connection", "Close");
            server.closeAfterResponse(key);
        }

        boolean closeDelimited = !close && request.version == HttpVersion.HTTP_1_0;
        if (closeDelimited) {
            headers.putOrReplace("Connection", "Close");
            server.closeAfterResponse(key);
        } else if (request.isKeepAlive && request.version == HttpVersion.HTTP_1_0) {
            headers.put("Connection", "Keep-Alive");
        }

        if (close) { // normal response, Content-Length. Every http client understand it
            buffers = HttpEncode(status, headers, body, server.serverHeader);
            if (request.method == HttpMethod.HEAD && buffers.length > 1) {
                buffers = new ByteBuffer[]{buffers[0]};
            }
        } else {
            if (request.version == HttpVersion.HTTP_1_1) {
                headers.putOrReplace("Transfer-Encoding", "chunked"); // first chunk
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
            } else {
                buffers = HttpEncodeCloseDelimited(status, headers, body, server.serverHeader);
            }
        }
        if (close) {
            onClose(0);
        }
        server.tryWrite(key, !close, buffers);
        if (close) {
            server.responseComplete(key);
        }
    }

    // for streaming, send a chunk of data to client
    private void writeChunk(Object body, boolean close) throws IOException {
        if (body instanceof Map) { // only get body if a map
            body = ((Map<Keyword, Object>) body).get(BODY);
        }
        if (body != null) { // null is ignored
            ByteBuffer t = bodyBuffer(body);
            if (t.hasRemaining()) {
                ByteBuffer[] buffers;
                if (request.version == HttpVersion.HTTP_1_1) {
                    buffers = new ByteBuffer[]{
                            chunkSize(t.remaining()),
                            t,  // actual data
                            ByteBuffer.wrap(newLineBytes) // terminating CRLF sequence
                    };
                } else {
                    buffers = new ByteBuffer[]{t};
                }
                server.tryWrite(key, true, buffers);
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

    public void setPongHandler(IFn fn) {
        if (!pongHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("pong handler exist: " + pongHandler);
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
        } else {
            // if no ping handler, default to sending a return PONG frame
            server.tryWrite(key, WsEncode(OPCODE_PONG, mesg));
        }
    }

    public void pongReceived(final byte[] mesg) {
        IFn f = pongHandler.get();
        if (f != null) {
            f.invoke(mesg);
        }
    }

    public synchronized void sendHandshake(Map<String, Object> headers) {
        HeaderMap map = HeaderMap.camelCase(headers);
        ByteBuffer[] response = HttpEncode(101, map, null);
        websocketUpgraded = true;
        server.tryWrite(key, response);
        server.responseComplete(key);
    }

    public boolean hasCloseHandler() {
        synchronized (closeLock) {
            return closeHandler != null || closeRingHandler != null;
        }
    }

    public void setCloseHandler(IFn fn) {
        boolean invoke;
        int status;
        synchronized (closeLock) {
            if (closeHandler != null) { // only once
                throw new IllegalStateException("close handler exist: " + closeHandler);
            }
            closeHandler = fn;
            invoke = closedRan.get();
            status = closeStatus;
        }
        if (invoke) {
            fn.invoke(readable(status));
        }
    }

    public void setCloseRingHandler(IFn fn) {
        boolean invoke;
        int status;
        String reason;
        synchronized (closeLock) {
            if (closeRingHandler != null) { // only once
                throw new IllegalStateException("close ring handler exist: " + closeRingHandler);
            }
            closeRingHandler = fn;
            invoke = closedRan.get();
            status = closeRingStatus;
            reason = closeReason;
        }
        if (invoke) {
            fn.invoke(status, reason);
        }
    }

    public void onClose(int status) {
        onClose(status, "");
    }

    public void onClose(int status, String reason) {
        IFn handler;
        IFn ringHandler;
        synchronized (closeLock) {
            if (!closedRan.compareAndSet(false, true)) {
                return;
            }
            closeStatus = status;
            closeRingStatus = status;
            closeReason = reason;
            handler = closeHandler;
            ringHandler = closeRingHandler;
        }
        if (handler != null) {
            handler.invoke(readable(status));
        }
        if (ringHandler != null) {
            ringHandler.invoke(status, reason);
        }
    }

    public boolean serverClose(int status) {
        return serverClose(status, "");
    }

    // also sent CloseFrame a final Chunk
    public synchronized boolean serverClose(int status, String reason) {
        boolean websocket = websocketUpgraded;
        byte[] closePayload = websocket ? closePayload(status, reason) : null;
        IFn handler;
        IFn ringHandler;
        synchronized (closeLock) {
            if (!closedRan.compareAndSet(false, true)) {
                return false; // already closed
            }
            closeStatus = 0;
            closeRingStatus = status;
            closeReason = reason;
            handler = closeHandler;
            ringHandler = closeRingHandler;
        }
        if (websocket) {
            server.tryWrite(key, WsEncode(OPCODE_CLOSE, closePayload));
        } else if (request.version == HttpVersion.HTTP_1_0) {
            server.finishCloseDelimitedResponse(key);
        } else {
            server.tryWrite(key, false, ByteBuffer.wrap(finalChunkBytes));
        }
        if (!websocket) {
            server.responseComplete(key);
        }
        if (handler != null) {
            handler.invoke(readable(0)); // server close is 0
        }
        if (ringHandler != null) {
            ringHandler.invoke(status, reason);
        }
        return true;
    }

    public synchronized boolean send(Object data, boolean close) throws IOException {
        if (closedRan.get()) {
            return false;
        }

        if (websocketUpgraded) {
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
            } else if (data instanceof Frame.PingFrame) {
                server.tryWrite(key, WsEncode(OPCODE_PING, ((Frame) data).data));
            } else if (data instanceof Frame.PongFrame) {
                server.tryWrite(key, WsEncode(OPCODE_PONG, ((Frame) data).data));
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
        return isWebSocketCandidate();
    }

    private boolean isWebSocketCandidate() {
        return key.attachment() instanceof WsAtta;
    }

    private static byte[] closePayload(int status, String reason) {
        if (!WSDecoder.isValidCloseStatus(status)) {
            throw new IllegalArgumentException("Invalid websocket close status: " + status);
        }
        byte[] reasonBytes = (reason == null ? "" : reason).getBytes(UTF_8);
        if (reasonBytes.length > 123) {
            throw new IllegalArgumentException("Websocket close reason exceeds 123 bytes");
        }
        return ByteBuffer.allocate(2 + reasonBytes.length)
                .putShort((short) status).put(reasonBytes).array();
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
