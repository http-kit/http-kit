package me.shenfeng.http.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.BAD_REQUEST;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.SELECT_TIMEOUT;
import static me.shenfeng.http.HttpUtils.UTF_8;
import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.encodeResponseHeader;
import static me.shenfeng.http.HttpUtils.readAll;
import static me.shenfeng.http.server.ServerConstant.BODY;
import static me.shenfeng.http.server.ServerConstant.HEADERS;
import static me.shenfeng.http.server.ServerConstant.STATUS;
import static me.shenfeng.http.server.ServerDecoderState.ALL_READ;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.codec.LineTooLargeException;
import me.shenfeng.http.codec.ProtocolException;
import clojure.lang.ISeq;
import clojure.lang.Seqable;

public class HttpServer {
    private static void doWrite(SelectionKey key) throws IOException {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        HttpReqeustDecoder decoder = atta.decoder;
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
                ch.write(new ByteBuffer[] { header, body });
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
    private String ip;
    private Selector selector;
    private Thread serverThread;
    private ServerSocketChannel serverChannel;

    ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();

    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    private Runnable server = new Runnable() {
        public void run() {
            try {
                SelectionKey key;
                while (true) {
                    while ((key = pendings.poll()) != null) {
                        if (key.isValid()) {
                            key.interestOps(SelectionKey.OP_WRITE);
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
                }
            } catch (ClosedSelectorException ignore) {
                System.out.println("selector closed, stop server");
                selector = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    };

    public HttpServer(String ip, int port, IHandler handler) {
        this.handler = handler;
        this.ip = ip;
        this.port = port;
    }

    void accept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        while ((s = ch.accept()) != null) {
            s.configureBlocking(false);
            s.register(selector, OP_READ, new ServerAtta());
        }
    }

    void bind(String ip, int port) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
        System.out.println("start server " + ip + "@" + port);
    }

    private class Callback implements IResponseCallback {
        private final SelectionKey key;

        public Callback(SelectionKey key) {
            this.key = key;
        }

        // maybe in another thread
        public void run(int status, Map<String, Object> headers, Object body) {
            ServerAtta atta = (ServerAtta) key.attachment();
            // extend ring spec to support async response
            if (body instanceof IListenableFuture) {
                final IListenableFuture future = (IListenableFuture) body;
                future.addListener(new Runnable() {
                    @SuppressWarnings("rawtypes")
                    public void run() {
                        Map resp = (Map) future.get();
                        int status = ((Long) resp.get(STATUS)).intValue();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> headers = (Map) resp.get(HEADERS);
                        Object body = resp.get(BODY);
                        new Callback(key).run(status, headers, body);
                    }
                });
                return;
            }

            if (headers != null) {
                // copy to modify
                headers = new TreeMap<String, Object>(headers);
            } else {
                headers = new TreeMap<String, Object>();
            }
            try {
                if (body == null) {
                    atta.respBody = null;
                    headers.put(CONTENT_LENGTH, "0");
                } else if (body instanceof String) {
                    byte[] b = ((String) body).getBytes(UTF_8);
                    atta.respBody = ByteBuffer.wrap(b);
                    headers.put(CONTENT_LENGTH, Integer.toString(b.length));
                } else if (body instanceof InputStream) {
                    DynamicBytes b = readAll((InputStream) body);
                    atta.respBody = ByteBuffer.wrap(b.get(), 0, b.length());
                    headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
                } else if (body instanceof File) {
                    File f = (File) body;
                    // serving file is better be done by nginx
                    long length = f.length();
                    byte[] b = readAll(f, (int) length);
                    atta.respBody = ByteBuffer.wrap(b);
                } else if (body instanceof Seqable) {
                    ISeq seq = ((Seqable) body).seq();
                    DynamicBytes b = new DynamicBytes(seq.count() * 512);
                    while (seq != null) {
                        b.append(seq.first().toString(), UTF_8);
                        seq = seq.next();
                    }
                    atta.respBody = ByteBuffer.wrap(b.get(), 0, b.length());
                    headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
                }
            } catch (IOException e) {
                byte[] b = e.getMessage().getBytes(ASCII);
                status = 500;
                headers.clear();
                headers.put(CONTENT_LENGTH, b.length + "");
                atta.respBody = ByteBuffer.wrap(b);
            }
            DynamicBytes bytes = encodeResponseHeader(status, headers);
            atta.respHeader = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
            pendings.offer(key);
            selector.wakeup();
        }
    }

    private void doRead(final SelectionKey key) throws IOException {
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
                HttpReqeustDecoder decoder = atta.decoder;
                ServerDecoderState s = decoder.decode(buffer);
                if (s == ALL_READ) {
                    handler.handle(decoder.request, new Callback(key));
                }
            }
        } catch (IOException e) {
            closeQuiety(ch); // the remote forcibly closed the connection
        } catch (ProtocolException e) {
            closeQuiety(ch);
        } catch (LineTooLargeException e) {
            atta.respBody = null;
            atta.respHeader = ByteBuffer.wrap(BAD_REQUEST);
            key.interestOps(OP_WRITE);
        } catch (RequestTooLargeException e) {
            atta.respBody = null;
            atta.respHeader = ByteBuffer.wrap(BAD_REQUEST);
            key.interestOps(OP_WRITE);
        }
    }

    public void start() throws IOException {
        bind(ip, port);
        serverThread = new Thread(server, "server-boss");
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
