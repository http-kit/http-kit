package org.httpkit.client;

import org.httpkit.PriorityQueue;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class HttpsRequest extends Request {
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    public HttpsRequest(SocketAddress addr, String host, ByteBuffer[] request, IRespListener handler,
                        PriorityQueue<Request> clients, RequestConfig config, SSLEngine engine) {
        super(addr, host, request, handler, clients, config);
        this.engine = engine;
        this.engineOriginal = engine;
        myNetData.flip();
    }

    SSLEngine engine; // package private
    SSLEngine engineOriginal;
    private ByteBuffer myNetData = ByteBuffer.allocate(40 * 1024);
    private ByteBuffer peerNetData = ByteBuffer.allocate(40 * 1024);
    boolean handshaken = false;
    private int ioProgress;

    final int unwrapRead(ByteBuffer peerAppData) throws IOException {
        // TODO, make sure peerNetData has remaining place
        int read = ((SocketChannel) key.channel()).read(peerNetData), unwrapped = 0;
        recordIo(read);
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
                    throw new SSLException("TLS response buffer overflow");
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
            throw new SSLException("Failed to wrap TLS request: " + res.getStatus());
        }
        myNetData.flip();
    }

    final void writeWrappedRequest() throws IOException {
        if (myNetData.hasRemaining()) {
            recordIo(((SocketChannel) key.channel()).write(myNetData));
        } else if (request[request.length - 1].hasRemaining()) {
            wrapRequest();
            recordIo(((SocketChannel) key.channel()).write(myNetData));
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

    final boolean isConnectionClosed() {
        return engine.isInboundDone() || engine.isOutboundDone();
    }

    final int doHandshake(ByteBuffer peerAppData) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (myNetData.hasRemaining()) {
            recordIo(channel.write(myNetData));
            if (myNetData.hasRemaining()) {
                return 0;
            }
            myNetData.clear();
            myNetData.flip();
        }

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
                    key.interestOps(SelectionKey.OP_READ);
                    int read = channel.read(peerNetData);
                    recordIo(read);
                    if (read < 0) {
                        return -1;
                    } else {
                        peerNetData.flip();
                        SSLEngineResult res = engine.unwrap(peerNetData, peerAppData);
                        peerNetData.compact();
                        switch (res.getStatus()) {
                            case BUFFER_OVERFLOW:
                                throw new SSLException("TLS handshake buffer overflow");
                            case CLOSED:
                                return -1;
                            case BUFFER_UNDERFLOW: // need more data from peer
                                return 0;
                        }
                        // do not flip to write here, since TCP buffer is writable
                    }
                    break;
                case NEED_WRAP:
                    myNetData.clear();
                    SSLEngineResult res = engine.wrap(EMPTY_BUFFER, myNetData);
                    if (res.getStatus() != Status.OK) {
                        throw new SSLException("Failed to wrap TLS handshake: " + res.getStatus());
                    }
                    myNetData.flip();
                    recordIo(channel.write(myNetData));
                    if (myNetData.hasRemaining()) {
                        key.interestOps(SelectionKey.OP_WRITE);
                        return 0;
                    } else {
                        myNetData.clear();
                        myNetData.flip();
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

    final int consumeIoProgress() {
        int progress = ioProgress;
        ioProgress = 0;
        return progress;
    }

    private void recordIo(int bytes) {
        if (bytes > 0) {
            ioProgress += bytes;
        }
    }

    public void recycle(Request old) throws SSLException {
        super.recycle(old);
        this.engine = ((HttpsRequest) old).engine;
        this.handshaken = true;
        wrapRequest(); // prepare for write
    }

    // if we weren't able to reuse the kept-alive conn, switch back to original ssl engine
    @Override
    public void unrecycle() {
        super.unrecycle();
        this.engine = this.engineOriginal;
        this.handshaken = false;
        myNetData.clear();
        myNetData.flip();
        peerNetData.clear();
        ioProgress = 0;
    }
}
