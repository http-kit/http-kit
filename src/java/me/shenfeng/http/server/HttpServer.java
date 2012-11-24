package me.shenfeng.http.server;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.ProtocolException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.nio.channels.SelectionKey.*;
import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.server.ServerDecoderState.ALL_READ;

public class HttpServer {

    private static void doWrite(SelectionKey key) throws IOException {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        ReqeustDecoder decoder = atta.decoder;
        ByteBuffer header = atta.respHeader;
        ByteBuffer body = atta.respBody;

        if (body == null) {
            ch.write(header);
            if (!header.hasRemaining()) {
                if (decoder.request.isKeepAlive()) {
                    decoder.reset();
                    key.interestOps(OP_READ);
                } else {
                    closeQuiety(ch);
                }
            }
        } else {
            if (header.hasRemaining()) {
                ch.write(new ByteBuffer[]{header, body});
            } else {
                ch.write(body);
            }
            if (!body.hasRemaining()) {
                if (decoder.request.isKeepAlive()) {
                    decoder.reset();
                    key.interestOps(OP_READ);
                } else {
                    closeQuiety(ch);
                }
            }
        }
    }

    private IHandler handler;
    private int port;
    private final int maxBody;
    private String ip;
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
                    e.printStackTrace();
                }
            } // end of while loop
        }
    };

    public HttpServer(String ip, int port, IHandler handler, int maxBody) {
        this.handler = handler;
        this.ip = ip;
        this.port = port;
        this.maxBody = maxBody;
    }

    void accept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        while ((s = ch.accept()) != null) {
            s.configureBlocking(false);
            s.register(selector, OP_READ, new ServerAtta(maxBody));
        }
    }

    void bind() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
        System.out.println(String.format("http server start %s@%d, max body: %d", ip, port, maxBody));
    }

    private void doRead(final SelectionKey key) {
        final ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear(); // clear for read
            int read = ch.read(buffer);
            if (read == -1) {
                // remote entity shut the socket down cleanly.
                closeQuiety(ch);
            } else if (read > 0) {
                buffer.flip(); // flip for read
                ReqeustDecoder decoder = atta.decoder;
                if (decoder.decode(buffer) == ALL_READ) {
                    HttpRequest request = decoder.request;
                    request.setRemoteAddr(ch.socket().getRemoteSocketAddress());
                    handler.handle(request, new Callback(pendings, key));
                }
            }
        } catch (IOException e) {
            closeQuiety(ch); // the remote forcibly closed the connection
        } catch (ProtocolException e) {
            closeQuiety(ch);
            // LineTooLargeException, RequestTooLargeException
        } catch (Exception e) {
            byte[] body = e.getMessage().getBytes(ASCII);
            Map<String, Object> headers = new TreeMap<String, Object>();
            headers.put(CONTENT_LENGTH, body.length);
            DynamicBytes db = encodeResponseHeader(400, headers);
            db.append(body, 0, body.length);
            atta.respBody = null;
            atta.respHeader = ByteBuffer.wrap(db.get(), 0, db.length());
            key.interestOps(OP_WRITE);
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
