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
import org.httpkit.ws.CloseFrame;
import org.httpkit.ws.WSDecoder;
import org.httpkit.ws.WSEncoder;

import clojure.lang.IFn;
import clojure.lang.Keyword;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class AsyncChannel {

    private final SelectionKey key;
    private final HttpServer server;

    final AtomicReference<Boolean> closedRan = new AtomicReference<Boolean>(false);
    final AtomicReference<IFn> closeHandler = new AtomicReference<IFn>(null);

    // websocket
    final AtomicReference<IFn> receiveHandler = new AtomicReference<IFn>(null);

    // streaming
    private volatile boolean isInitialWrite = true;
    private volatile boolean finalWritten = false;

    public AsyncChannel(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void reset() {
        closedRan.set(false);
        closeHandler.set(null);
        receiveHandler.set(null);
        isInitialWrite = true;
        finalWritten = false;
    }

    // -------------- streaming --------------------
    private static final byte[] finalChunkBytes = "0\r\n\r\n".getBytes();
    private static final byte[] finalChunkBytes2 = "\r\n0\r\n\r\n".getBytes();
    private static final byte[] newLineBytes = "\r\n".getBytes();

    public void writeChunk(Object data, boolean isFinal) throws IOException {
        if (finalWritten) {
            throw new IllegalStateException("final chunk has already been written");
        }

        ByteBuffer buffers[];
        boolean isMap = (data instanceof Map);

        if (isInitialWrite) {
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
        write(buffers);
        if (isFinal) {
            finalWritten = true;
            onClose(0); // server close
        }
        isInitialWrite = false;
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

    public void send(final String mesg) {
        if (closedRan.get()) {
            throw new IllegalStateException("already closed");
        }
        write(WSEncoder.encode(mesg));
    }

    public void serverClose() {
        serverClose(CloseFrame.CLOSE_NORMAL);
    }

    public void serverClose(int status) {
        ByteBuffer s = ByteBuffer.allocate(2).putShort((short) status);
        write(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, s.array()));
        onClose(0); // server close, 0
    }

    public void sendHandshake(Map<String, Object> headers) {
        write(encode(101, headers, null));
    }

    // ----------------------shared--------------------------------------
    private static ByteBuffer chunkSize(int size) {
        String s = Integer.toHexString(size) + "\r\n";
        return ByteBuffer.wrap(s.getBytes());
    }

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

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress();
    }

    private void write(ByteBuffer... buffers) {
        ServerAtta atta = (ServerAtta) key.attachment();
        atta.addBuffer(buffers);
        server.queueWrite(key);
    }

}
