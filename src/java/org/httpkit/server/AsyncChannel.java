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

    private volatile IFn onSend;

    // websocket
    final AtomicReference<IFn> receiveHandler = new AtomicReference<IFn>(null);

    // streaming
    private volatile boolean isInitialWrite = true;

    public AsyncChannel(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void reset() {
        closedRan.set(false);
        closeHandler.set(null);
        receiveHandler.set(null);
        isInitialWrite = true;
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

    private void writeChunk(Object body) throws IOException {
        if (body != null) { // null is ignored
            ByteBuffer buffers[];
            ByteBuffer t = HttpUtils.bodyBuffer(body);
            ByteBuffer size = chunkSize(t.remaining());
            buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(newLineBytes) };
            write(buffers);
        }
    }

    // ---------------------------websocket-------------------------------------

    public void setReceiveHandler(IFn fn) {
        if (!receiveHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("receive handler exist: " + receiveHandler.get());
        }
    }

    public void messageReceived(final String mesg) {
        IFn f = receiveHandler.get();
        if (f != null) {
            f.invoke(mesg);
        }
    }

    private void sendTextFrame(final String mesg) {
        write(WSEncoder.encode(mesg));
    }

    public void sendHandshake(Map<String, Object> headers) {
        write(encode(101, headers, null));
    }

    // ----------------------shared--------------------------------------
    public void setCloseHandler(IFn fn) {
        if (!closeHandler.compareAndSet(null, fn)) {
            throw new IllegalStateException("close handler exist: " + closeHandler.get());
        }
    }

    public void onClose(int status) {
        if (closedRan.compareAndSet(false, true)) {
            IFn f = closeHandler.get();
            if (f != null) {
                f.invoke(status);
            }
        }
    }

    public void serverClose(int status) {
        if (key.attachment() instanceof WsServerAtta) {
            ByteBuffer s = ByteBuffer.allocate(2).putShort((short) status);
            write(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, s.array()));
        } else {
            write(ByteBuffer.wrap(finalChunkBytes));
        }
        onClose(0); // server close, 0
    }

    public boolean send(Object data, boolean closeAfterSent) throws IOException {
        if (closedRan.get()) {
            return false;
        }

        if (onSend != null) {
            data = onSend.invoke(data);
        }

        if (key.attachment() instanceof WsServerAtta) {
            sendTextFrame((String) data);// websocket
            if (closeAfterSent) {
                serverClose(1000);
            }
        } else {
            if (isInitialWrite) {
                firstWrite(data, closeAfterSent);
                isInitialWrite = false;
            } else {
                writeChunk(data);
            }
        }
        return true;
    }

    public void setOnSendFn(IFn fn) {
        this.onSend = fn;
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress();
    }

    private void write(ByteBuffer... buffers) {
        ServerAtta atta = (ServerAtta) key.attachment();
        atta.addBuffer(buffers);
        server.queueWrite(key);
    }

    public boolean isClosed() {
        return closedRan.get();
    }
}
