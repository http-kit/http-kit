package me.shenfeng.http.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static me.shenfeng.http.HttpUtils.SELECT_TIMEOUT;
import static me.shenfeng.http.HttpUtils.closeQuiety;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.ProtocolException;
import me.shenfeng.http.server.ReqeustDecoder.State;
import me.shenfeng.http.ws.PingFrame;
import me.shenfeng.http.ws.TextFrame;
import me.shenfeng.http.ws.WSDecoder;
import me.shenfeng.http.ws.WSEncoder;
import me.shenfeng.http.ws.WSFrame;
import me.shenfeng.http.ws.WsCon;
import me.shenfeng.http.ws.WsServerAtta;

public class HttpServer {

    private static void doWrite(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            LinkedList<ByteBuffer> toWrites = atta.toWrites;
            synchronized (toWrites) {
                if (toWrites.size() == 1) {
                    ch.write(toWrites.get(0));
                } else {
                    ByteBuffer buffers[] = new ByteBuffer[toWrites.size()];
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
                        closeQuiety(ch);
                    }
                }
            }
        } catch (IOException e) {
            closeQuiety(ch); // the remote forcibly closed the connection
        }
    }

    private final IHandler handler;
    private final int port;
    private final int maxBody;
    private final int maxLine;
    private final String ip;
    private Selector selector;
    private Thread serverThread;
    private ServerSocketChannel serverChannel;

    private ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();

    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    private Runnable eventLoop = new Runnable() {
        public void run() {
            SelectionKey key = null;
            while (true) {
                try {
                    while ((key = pendings.poll()) != null) {
                        if (key.isValid()) {
                            key.interestOps(OP_WRITE);
                        }
                    }
                    int select = selector.select(SELECT_TIMEOUT);
                    if (select <= 0) {
                        continue;
                    }
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> ite = selectedKeys.iterator();
                    while (ite.hasNext()) {
                        key = ite.next();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            accept(key, selector);
                        } else if (key.isReadable()) {
                            doRead(key);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        }
                    }
                    selectedKeys.clear();
                } catch (ClosedSelectorException ignore) {
                    selector = null;
                    return;
                } catch (Exception e) { // catch any exception, print it
                    if (key != null) {
                        closeQuiety(key.channel());
                    }
                    // should not happen
                    e.printStackTrace();
                }
            }
        }
    };

    public HttpServer(String ip, int port, IHandler handler, int maxBody, int maxLine) {
        this.handler = handler;
        this.ip = ip;
        this.port = port;
        this.maxLine = maxLine;
        this.maxBody = maxBody;
    }

    void accept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        while ((s = ch.accept()) != null) {
            s.configureBlocking(false);
            s.register(selector, OP_READ, new HttpServerAtta(maxBody, maxLine));
        }
    }

    void bind() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
        System.out.println(String.format("http server start %s@%d, max body: %d", ip, port,
                maxBody));
    }

    private void decodeHttp(HttpServerAtta atta, SelectionKey key, SocketChannel ch) {
        ReqeustDecoder decoder = atta.decoder;
        try {
            if (decoder.decode(buffer) == State.ALL_READ) {
                HttpRequest request = decoder.request;
                if (request.isWs()) {
                    WsCon con = new WsCon(key, pendings);
                    request.setWebSocketCon(con);
                    key.attach(new WsServerAtta(con));
                }
                request.setRemoteAddr(ch.socket().getRemoteSocketAddress());
                handler.handle(request, new ResponseCallback(pendings, key));
            }
        } catch (ProtocolException e) {
            closeQuiety(ch);
            // LineTooLargeException, RequestTooLargeException
        } catch (Exception e) {
            ByteBuffer[] buffers = ClojureRing.encode(400, null, e.getMessage());
            atta.addBuffer(buffers);
            key.interestOps(OP_WRITE);
        }
    }

    private void decodeWs(WsServerAtta atta, SelectionKey key, SocketChannel ch) {
        try {
            WSFrame frame = atta.decoder.decode(buffer);
            if (frame instanceof TextFrame) {
                handler.handle(frame);
                atta.reset();
            } else if (frame instanceof PingFrame) {
                atta.addBuffer(WSEncoder.encode(WSDecoder.OPCODE_PONG, frame.data));
                atta.reset();
                key.interestOps(OP_WRITE);
            }
        } catch (ProtocolException e) {
            // TODO error msg
            closeQuiety(ch);
        }
    }

    private void doRead(final SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear(); // clear for read
            int read = ch.read(buffer);
            if (read == -1) {
                // remote entity shut the socket down cleanly.
                closeQuiety(ch);
            } else if (read > 0) {
                buffer.flip(); // flip for read
                final ServerAtta atta = (ServerAtta) key.attachment();
                if (atta instanceof HttpServerAtta) {
                    decodeHttp((HttpServerAtta) atta, key, ch);
                } else {
                    decodeWs((WsServerAtta) atta, key, ch);
                }
            }
        } catch (IOException e) {
            closeQuiety(ch); // the remote forcibly closed the connection
        }
    }

    public void start() throws IOException {
        bind();
        serverThread = new Thread(eventLoop, "http-server");
        serverThread.start();
    }

    public void stop() {
        if (selector != null) {
            try {
                serverChannel.close();
                serverChannel = null;
                Set<SelectionKey> keys = selector.keys();
                for (SelectionKey k : keys) {
                    k.channel().close();
                }
                selector.close();
            } catch (IOException ignore) {
            }
            serverThread.interrupt();
        }
    }
}
