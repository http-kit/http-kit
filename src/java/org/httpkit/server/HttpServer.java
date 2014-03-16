package org.httpkit.server;

import org.httpkit.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.nio.channels.SelectionKey.*;
import static org.httpkit.HttpUtils.HttpEncode;
import static org.httpkit.HttpUtils.WsEncode;
import static org.httpkit.server.Frame.CloseFrame.*;

class PendingKey {
    public final SelectionKey key;
    // operation: can be register for write or close the selectionkey
    public final int Op;

    PendingKey(SelectionKey key, int op) {
        this.key = key;
        Op = op;
    }

    public static final int OP_WRITE = -1;
}

public class HttpServer implements Runnable {

    static final String THREAD_NAME = "server-loop";

    private final IHandler handler;
    private final int maxBody; // max http body size
    private final int maxLine; // max header line size

    private final int maxWs; // websocket, max message size

    private final Selector selector;
    private final ServerSocketChannel serverChannel;


    private Thread serverThread;

    // queue operations from worker threads to the IO thread
    private final ConcurrentLinkedQueue<PendingKey> pending = new ConcurrentLinkedQueue<PendingKey>();

    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    public HttpServer(String ip, int port, IHandler handler, int maxBody, int maxLine, int maxWs)
            throws IOException {
        this.handler = handler;
        this.maxLine = maxLine;
        this.maxBody = maxBody;
        this.maxWs = maxWs;

        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(ip, port));
        serverChannel.register(selector, OP_ACCEPT);
    }

    void accept(SelectionKey key) {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        try {
            while ((s = ch.accept()) != null) {
                s.configureBlocking(false);
                HttpAtta atta = new HttpAtta(maxBody, maxLine);
                SelectionKey k = s.register(selector, OP_READ, atta);
                atta.channel = new AsyncChannel(k, this);
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
        if (att instanceof HttpAtta) {
            handler.clientClose(att.channel, -1);
        } else if (att != null) {
            handler.clientClose(att.channel, status);
        }
    }

    private void decodeHttp(HttpAtta atta, SelectionKey key, SocketChannel ch) {
        try {
            do {
                AsyncChannel channel = atta.channel;
                HttpRequest request = atta.decoder.decode(buffer);
                if (request != null) {
                    channel.reset(request);
                    if (request.isWebSocket) {
                        key.attach(new WsAtta(channel, maxWs));
                    } else {
                        atta.keepalive = request.isKeepAlive;
                    }
                    request.channel = channel;
                    request.remoteAddr = (InetSocketAddress) ch.socket().getRemoteSocketAddress();
                    handler.handle(request, new RespCallback(key, this));
                    // pipelining not supported : need queue to ensure order
                    atta.decoder.reset();
                }
            } while (buffer.hasRemaining()); // consume all
        } catch (ProtocolException e) {
            closeKey(key, -1);
        } catch (RequestTooLargeException e) {
            atta.keepalive = false;
            tryWrite(key, HttpEncode(413, new HeaderMap(), e.getMessage()));
        } catch (LineTooLargeException e) {
            atta.keepalive = false; // close after write
            tryWrite(key, HttpEncode(414, new HeaderMap(), e.getMessage()));
        }
    }

    private void decodeWs(WsAtta atta, SelectionKey key) {
        try {
            do {
                Frame frame = atta.decoder.decode(buffer);
                if (frame instanceof TextFrame || frame instanceof BinaryFrame) {
                    handler.handle(atta.channel, frame);
                    atta.decoder.reset();
                } else if (frame instanceof PingFrame) {
                    atta.decoder.reset();
                    tryWrite(key, WsEncode(WSDecoder.OPCODE_PONG, frame.data));
                } else if (frame instanceof PongFrame) {
                    atta.decoder.reset();
                    tryWrite(key, WsEncode(WSDecoder.OPCODE_PING, frame.data));
                } else if (frame instanceof CloseFrame) {
                    handler.clientClose(atta.channel, ((CloseFrame) frame).getStatus());
                    // close the TCP connection after sent
                    atta.keepalive = false;
                    tryWrite(key, WsEncode(WSDecoder.OPCODE_CLOSE, frame.data));
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
                buffer.flip(); // flip for read
                final ServerAtta atta = (ServerAtta) key.attachment();
                if (atta instanceof HttpAtta) {
                    decodeHttp((HttpAtta) atta, key, ch);
                } else {
                    decodeWs((WsAtta) atta, key);
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
            // the sync is per socket (per client). virtually, no contention
            // 1. keep byte data order, 2. ensure visibility
            synchronized (atta) {
                LinkedList<ByteBuffer> toWrites = atta.toWrites;
                int size = toWrites.size();
                if (size == 1) {
                    ch.write(toWrites.get(0));
                    // TODO investigate why needed.
                    // ws request for write, but has no data?
                } else if (size > 0) {
                    ByteBuffer buffers[] = new ByteBuffer[size];
                    toWrites.toArray(buffers);
                    ch.write(buffers, 0, buffers.length);
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

    public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
        tryWrite(key, false, buffers);
    }

    public void tryWrite(final SelectionKey key, boolean chunkInprogress, ByteBuffer... buffers) {
        ServerAtta atta = (ServerAtta) key.attachment();
        synchronized (atta) {
            atta.chunkedResponseInprogress(chunkInprogress);
            if (atta.toWrites.isEmpty()) {
                SocketChannel ch = (SocketChannel) key.channel();
                try {
                    // TCP buffer most of time is empty, writable(8K ~ 256k)
                    // One IO thread => One thread reading + Many thread writing
                    // Save 2 system call
                    ch.write(buffers, 0, buffers.length);
                    if (buffers[buffers.length - 1].hasRemaining()) {
                        for (ByteBuffer b : buffers) {
                            if (b.hasRemaining()) {
                                atta.toWrites.add(b);
                            }
                        }
                        pending.add(new PendingKey(key, PendingKey.OP_WRITE));
                        selector.wakeup();
                    } else if (!atta.isKeepAlive()) {
                        pending.add(new PendingKey(key, CLOSE_NORMAL));
                    }
                } catch (IOException e) {
                    pending.add(new PendingKey(key, CLOSE_AWAY));
                }
            } else {
                // If has pending write, order should be maintained. (WebSocket)
                Collections.addAll(atta.toWrites, buffers);
                pending.add(new PendingKey(key, PendingKey.OP_WRITE));
                selector.wakeup();
            }
        }
    }

    public void run() {
        while (true) {
            try {
                PendingKey k;
                while ((k = pending.poll()) != null) {
                    if (k.Op == PendingKey.OP_WRITE) {
                        if (k.key.isValid()) {
                            k.key.interestOps(OP_WRITE);
                        }
                    } else {
                        closeKey(k.key, k.Op);
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
                return; // stopped
                // do not exits the while IO event loop. if exits, then will not process any IO event
                // jvm can catch any exception, including OOM
            } catch (Throwable e) { // catch any exception(including OOM), print it
                HttpUtils.printError("http server loop error, should not happen", e);
            }
        }
    }

    public void start() throws IOException {
        serverThread = new Thread(this, THREAD_NAME);
        serverThread.start();
    }

    public void stop(int timeout) {
        try {
            serverChannel.close(); // stop accept any request
        } catch (IOException ignore) {
        }

        // wait all requests to finish, at most timeout milliseconds
        handler.close(timeout);

        // close socket, notify on-close handlers
        if (selector.isOpen()) {
            Set<SelectionKey> t = selector.keys();
            SelectionKey[] keys = t.toArray(new SelectionKey[t.size()]);
            for (SelectionKey k : keys) {
                closeKey(k, 0); // 0 => close by server
            }

            try {
                selector.close();
            } catch (IOException ignore) {
            }
        }
    }

    public int getPort() {
        return this.serverChannel.socket().getLocalPort();
    }
}
