package org.httpkit.client;

import org.httpkit.PriorityQueue;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class HttpsRequest extends Request {
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    public HttpsRequest(InetSocketAddress addr, InetSocketAddress realAddr,
                        ByteBuffer[] request, IRespListener handler,
                        PriorityQueue<Request> clients, RequestConfig config, SSLEngine engine, URI uri) {
        super(addr, realAddr, request, handler, clients, config, uri);
        this.engine = engine;
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
        } else {

        }
        myNetData.flip();
    }

    final void writeWrappedRequest() throws IOException {
        if (myNetData.hasRemaining()) {
            ((SocketChannel) key.channel()).write(myNetData);
        } else if (request[request.length - 1].hasRemaining()) {
            wrapRequest();
            ((SocketChannel) key.channel()).write(myNetData);
            request[request.length - 1].flip();
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
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        new Thread(task).start();
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
                    myNetData.clear();
                    SSLEngineResult res = engine.wrap(request, myNetData);
                    hs = res.getHandshakeStatus();

                    // Check status
                    switch (res.getStatus()) {
                        case OK :
                            myNetData.flip();

                            // Send the handshaking data to peer
                            while (myNetData.hasRemaining()) {
                                ((SocketChannel) key.channel()).write(myNetData);
                            }
                            break;

                        // Handle other status:  BUFFER_OVERFLOW, BUFFER_UNDERFLOW, CLOSED

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
