package org.httpkit.client;

import org.httpkit.PriorityQueue;

import javax.net.ssl.*;
import javax.net.ssl.SSLEngineResult.Status;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;

public class HttpsRequest extends Request {

    public static final SSLContext DEFAUTL_CONTEXT;

    static {
        try {
            DEFAUTL_CONTEXT = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new Error("Failed to initialize SSLContext", e);
        }
    }

    public SSLEngine engine;

    public HttpsRequest(InetSocketAddress addr, ByteBuffer[] request, IRespListener handler,
                        PriorityQueue<Request> clients, HttpRequestConfig config) {
        super(addr, request, handler, clients, config);
        this.engine = config.engine;
    }

    private ByteBuffer myNetData = ByteBuffer.allocate(32 * 1024);
    private ByteBuffer peerNetData = ByteBuffer.allocate(32 * 1024);

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
    boolean handshaken = false;

    private void log(Object... p) {
        StringBuffer sb = new StringBuffer();
        for (Object o : p) {
            sb.append(o).append(' ');
        }
//        System.out.println(sb.toString());
    }

    final int unwrapRead(ByteBuffer peerAppData) throws IOException {
        // TODO, make sure peerNetData has remaing place
        int read = ((SocketChannel) key.channel()).read(peerNetData);
        if (read > 0) {
            peerNetData.flip();
//            log("--read peernet--", read, "bytes");
            read = 0; // how many bytes produced
            SSLEngineResult res;
            while ((res = engine.unwrap(peerNetData, peerAppData)).getStatus() == Status.OK) {
                read += res.bytesProduced();
                if (!peerNetData.hasRemaining())
                    break;
            }
//            log("--unwrapRead-- produce", read, "bytes, peernet", peerNetData, "peerapp:", peerAppData);
            peerNetData.compact();
            switch (res.getStatus()) {
                case OK:
                case BUFFER_UNDERFLOW:
                    return read;
                case CLOSED:
                    return read > 0 ? read : -1;
                case BUFFER_OVERFLOW:
                    System.out.println("unwrapRead--------overflow-----------------");
                    // TODO Overflow, need large buffer
                    return -1;
            }
        }
        return read;
    }

    private final void wrap() throws SSLException {
        myNetData.clear();
        SSLEngineResult res = engine.wrap(request, myNetData);
        if (res.getStatus() != Status.OK) {
            // TODO larger buffer, uberflow?
        }
        myNetData.flip();
    }

    final void writeWrapped() throws IOException {
        if (myNetData.hasRemaining()) {
            ((SocketChannel) key.channel()).write(myNetData);
        } else if (request[request.length - 1].hasRemaining()) {
            wrap();
            ((SocketChannel) key.channel()).write(myNetData);
        }
        if (myNetData.hasRemaining() || request[request.length - 1].hasRemaining()) {
            // need more write
            if ((key.interestOps() & SelectionKey.OP_WRITE) == 0)
                key.interestOps(SelectionKey.OP_WRITE);
        } else {
            // OK, request sent
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    final int doHandshake(ByteBuffer peerAppData) throws IOException {
        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        while (!handshaken) {
            switch (hs) {
                case NEED_TASK:
                    Runnable runnable;
                    while ((runnable = engine.getDelegatedTask()) != null) {
                        runnable.run();
                        log("get task", runnable, engine.getHandshakeStatus());
                    }
                    break;
                case NEED_UNWRAP:
                    int read = ((SocketChannel) key.channel()).read(peerNetData);
                    log("--read--", read, "bytes");
                    if (read < 0) {
                        // TODO closed;
                        log("--closed--");
                        return -1;
                    } else {
                        peerNetData.flip();
                        SSLEngineResult res = engine.unwrap(peerNetData, peerAppData);
                        peerNetData.compact();
                        log("--hs unwrap--", res);
                        switch (res.getStatus()) {
                            // OK, BUFFER_OVERFLOW
                            case BUFFER_OVERFLOW:
                                System.out.println("-----------");
                                break;
                            case CLOSED:
                                return -1;
                            case BUFFER_UNDERFLOW: // need more data from peer
                                log("wait for more data from peer");
                                return 0;
                        }
                        // do not flip to write here, since TCP buffer is writable
                    }
                    break;
                case NEED_WRAP:
                    SSLEngineResult res = engine.wrap(EMPTY_BUFFER, myNetData);
                    log("--hs wrap--: ", res);
                    myNetData.flip();
                    ((SocketChannel) key.channel()).write(myNetData);
                    if (myNetData.hasRemaining()) {
                        // TODO, make sure data get written
                    } else {
                        myNetData.clear();
                        if (res.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP)
                            key.interestOps(SelectionKey.OP_READ);
                    }
                    break;
            }
            hs = engine.getHandshakeStatus();
            handshaken = hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    || hs == SSLEngineResult.HandshakeStatus.FINISHED;
            log("--hs--", hs, handshaken);
            if (handshaken) {
                wrap();
                writeWrapped(); // TCP buffer maybe empty this time
            }
        }
        return 0;
    }

    public void recycle(Request old) throws SSLException {
        super.recycle(old);
        this.engine = ((HttpsRequest) old).engine;
        this.handshaken = true;
        wrap(); // prepare for write
    }
}
