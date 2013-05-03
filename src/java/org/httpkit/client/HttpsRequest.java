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

    public static final SSLContext DEFAULT_CONTEXT;
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    static {
        try {
            DEFAULT_CONTEXT = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new Error("Failed to initialize SSLContext", e);
        }
    }

    public HttpsRequest(InetSocketAddress addr, ByteBuffer[] request, IRespListener handler,
                        PriorityQueue<Request> clients, RequestConfig config) {
        super(addr, request, handler, clients, config);
        this.engine = config.engine;
    }

    SSLEngine engine; // package private
    private ByteBuffer myNetData = ByteBuffer.allocate(40 * 1024);
    private ByteBuffer peerNetData = ByteBuffer.allocate(40 * 1024);
    boolean handshaken = false;

    final int unwrapRead(ByteBuffer peerAppData) throws IOException {
        // TODO, make sure peerNetData has remaining place
        int read = ((SocketChannel) key.channel()).read(peerNetData), unwrapped = 0;
        if (read > 0) {
            peerNetData.flip();
            SSLEngineResult res;
            while ((res = engine.unwrap(peerNetData, peerAppData)).getStatus() == Status.OK) {
                unwrapped += res.bytesProduced();
                if (!peerNetData.hasRemaining())
                    break;
            }
            peerNetData.compact();
            switch (res.getStatus()) {
                case OK:
                case BUFFER_UNDERFLOW: // need more data
                    return unwrapped;
                case CLOSED:
                    return unwrapped > 0 ? unwrapped : -1;
                case BUFFER_OVERFLOW:
                    return -1; // can't => peerAppData is 64k
            }
            return unwrapped;
        } else {
            return read;
        }
    }

    private void wrapRequest() throws SSLException {
        myNetData.clear();
        SSLEngineResult res = engine.wrap(request, myNetData);
        if (res.getStatus() != Status.OK) {
            // TODO larger buffer, uberflow?
        }
        myNetData.flip();
    }

    final void writeWrappedRequest() throws IOException {
        if (myNetData.hasRemaining()) {
            ((SocketChannel) key.channel()).write(myNetData);
        } else if (request[request.length - 1].hasRemaining()) {
            wrapRequest();
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
                    }
                    break;
                case NEED_UNWRAP:
                    int read = ((SocketChannel) key.channel()).read(peerNetData);
                    if (read < 0) {
                        return -1;
                    } else {
                        peerNetData.flip();
                        SSLEngineResult res = engine.unwrap(peerNetData, peerAppData);
                        peerNetData.compact();
                        switch (res.getStatus()) {
                            case BUFFER_OVERFLOW: // Not possible, peerAppData is 64k
                                break;
                            case CLOSED:
                                return -1;
                            case BUFFER_UNDERFLOW: // need more data from peer
                                return 0;
                        }
                        // do not flip to write here, since TCP buffer is writable
                    }
                    break;
                case NEED_WRAP:
                    SSLEngineResult res = engine.wrap(EMPTY_BUFFER, myNetData);
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
            if (handshaken) {
                wrapRequest();
                writeWrappedRequest(); // TCP buffer maybe empty this time
            }
        }
        return 0;
    }

    public void recycle(Request old) throws SSLException {
        super.recycle(old);
        this.engine = ((HttpsRequest) old).engine;
        this.handshaken = true;
        wrapRequest(); // prepare for write
    }
}
