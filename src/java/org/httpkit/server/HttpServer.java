package org.httpkit.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static org.httpkit.ws.CloseFrame.CLOSE_AWAY;
import static org.httpkit.ws.CloseFrame.CLOSE_MESG_BIG;
import static org.httpkit.ws.CloseFrame.CLOSE_NORMAL;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.httpkit.*;
import org.httpkit.ws.*;

public class HttpServer implements Runnable {

    static final String THREAD_NAME = "server-loop";

    private final IHandler handler;
    private final int maxBody;
    private final int maxLine;

    private final Selector selector;
    private final ServerSocketChannel serverChannel;

    private Thread serverThread;

    private final ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();
    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    public HttpServer(String ip, int port, IHandler handler, int maxBody, int maxLine)
            throws IOException {
        this.handler = handler;
        this.maxLine = maxLine;
        this.maxBody = maxBody;
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
    }

    void accept(SelectionKey key) {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        try {
            while ((s = ch.accept()) != null) {
                s.configureBlocking(false);
                HttpServerAtta atta = new HttpServerAtta(maxBody, maxLine);
                SelectionKey k = s.register(selector, OP_READ, atta);
                atta.asycChannel = new AsyncChannel(k, this);
            }
        } catch (Exception e) {
            // too many open files. do not quit
            HttpUtils.printError("accept incoming request", e);
        }
    }

    private void closeKey(final SelectionKey key, int status) {
        try {
            key.channel().close();
        } catch (Exception ignore) {
        }

        ServerAtta att = (ServerAtta) key.attachment();
        if (att instanceof HttpServerAtta) {
            handler.clientClose(att.asycChannel, -1);
        } else {
            handler.clientClose(att.asycChannel, status);
        }
    }

    private void decodeHttp(HttpServerAtta atta, SelectionKey key, SocketChannel ch) {
        try {
            do {
                atta.asycChannel.reset(); // reuse for performance
                HttpRequest request = atta.decoder.decode(buffer);
                if (request != null) {
                    if (request.isWebSocket) {
                        key.attach(new WsServerAtta(atta.asycChannel));
                    } else {
                        atta.keepalive = request.isKeepAlive;
                    }
                    request.asycChannel = atta.asycChannel;
                    request.remoteAddr = (InetSocketAddress) ch.socket()
                            .getRemoteSocketAddress();
                    handler.handle(request, new ResponseCallback(key, this));
                    // pipelining not supported : need queue to ensure order
                    // AsyncChannel can't be reseted here
                    atta.decoder.reset();
                }
            } while (buffer.hasRemaining()); // consume all
        } catch (ProtocolException e) {
            closeKey(key, -1);
        } catch (RequestTooLargeException e) {
            ByteBuffer[] buffers = ClojureRing.encode(413, null, e.getMessage());
            atta.addBuffer(buffers);
            atta.keepalive = false; // close after write
            key.interestOps(OP_WRITE);
        } catch (LineTooLargeException e) {
            ByteBuffer[] buffers = ClojureRing.encode(414, null, e.getMessage());
            atta.keepalive = false; // close after write
            atta.addBuffer(buffers);
            key.interestOps(OP_WRITE);
        }
    }

    private void decodeWs(WsServerAtta atta, SelectionKey key) {
        try {
            do {
                WSFrame frame = atta.decoder.decode(buffer);
                if (frame instanceof TextFrame) {
                    handler.handle(atta.asycChannel, (TextFrame) frame);
                    atta.decoder.reset();
                } else if (frame instanceof PingFrame) {
                    atta.addBuffer(WSEncoder.encode(WSDecoder.OPCODE_PONG, frame.data));
                    atta.decoder.reset();
                    key.interestOps(OP_WRITE);
                } else if (frame instanceof CloseFrame) {
                    // even though the logic connection is closed. the socket
                    // did not, if client willing to reuse it, http-kit is more
                    // than happy
                    handler.clientClose(atta.asycChannel, ((CloseFrame) frame).getStatus());
                    atta.addBuffer(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, frame.data));
                    key.interestOps(OP_WRITE);
                }
            } while (buffer.hasRemaining()); // consume all
        } catch (ProtocolException e) {
            System.err.printf("%s [%s] WARN - %s\n", new Date(), THREAD_NAME, e.getMessage());
            closeKey(key, CLOSE_MESG_BIG); // TODO more specific error
        }
    }

    private void doRead(final SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear(); // clear for read
            int read = ch.read(buffer);
            if (read == -1) {
                // remote entity shut the socket down cleanly.
                closeKey(key, CLOSE_AWAY);
            } else if (read > 0) {
                final ServerAtta atta = (ServerAtta) key.attachment();
                buffer.flip(); // flip for read
                if (atta instanceof HttpServerAtta) {
                    decodeHttp((HttpServerAtta) atta, key, ch);
                } else {
                    decodeWs((WsServerAtta) atta, key);
                }
            }
        } catch (IOException e) { // the remote forcibly closed the connection
            closeKey(key, CLOSE_AWAY);
        }
    }

    private void doWrite(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            LinkedList<ByteBuffer> toWrites = atta.toWrites;
            // the sync is per socket (per client). virtually, no contention
            // 1. keep byte data order, 2. ensure visibility
            synchronized (atta.toWrites) {
                int size = toWrites.size();
                if (size == 1) {
                    ch.write(toWrites.get(0));
                    // TODO investigate why needed.
                    // ws request for write, but has no data?
                } else if (size > 0) {
                    ByteBuffer buffers[] = new ByteBuffer[size];
                    toWrites.toArray(buffers);
                    ch.write(buffers);
                }
                Iterator<ByteBuffer> ite = toWrites.iterator();
                while (ite.hasNext()) {
                    if (!ite.next().hasRemaining()) {
                        ite.remove();
                    }
                }
                // all done
                if (toWrites.size() == 0) {
                    if (atta.isKeepAlive()) {
                        key.interestOps(OP_READ);
                    } else {
                        closeKey(key, CLOSE_NORMAL);
                    }
                }
            }
        } catch (IOException e) { // the remote forcibly closed the connection
            closeKey(key, CLOSE_AWAY);
        }
    }

    public void queueWrite(final SelectionKey key) {
        pendings.add(key);
        selector.wakeup(); // JVM is smart enough: only once per loop
    }

    public void run() {
        while (true) {
            try {
                SelectionKey k = null;
                while ((k = pendings.poll()) != null) {
                    if (k.isValid()) {
                        k.interestOps(OP_WRITE);
                    }
                }
                if (selector.select() <= 0) {
                    continue;
                }
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                for (SelectionKey key : selectedKeys) {
                    // TODO I do not know if this is needed
                    // if !valid, isAcceptable, isReadable.. will Exception
                    // run hours happily after commented, but not sure.
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        doRead(key);
                    } else if (key.isWritable()) {
                        doWrite(key);
                    }
                }
                selectedKeys.clear();
            } catch (ClosedSelectorException ignore) {
                return;
            } catch (Exception e) { // catch any exception, print it
                HttpUtils.printError("http server loop error, should not happen", e);
            }
        }
    }

    public void start() throws IOException {
        serverThread = new Thread(this, THREAD_NAME);
        serverThread.start();
    }

    public void stop() {
        if (selector.isOpen()) {
            try {
                serverChannel.close();
                Set<SelectionKey> keys = selector.keys();
                for (SelectionKey k : keys) {
                    k.channel().close();
                }
                selector.close();
                handler.close();
            } catch (IOException ignore) {
            }
            serverThread.interrupt();
        }
    }
}
