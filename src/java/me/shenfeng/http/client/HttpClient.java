package me.shenfeng.http.client;

import static java.lang.System.currentTimeMillis;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.client.State.ALL_READ;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HTTPException;
import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.ProtocolException;

public final class HttpClient implements Runnable {
    private static final AtomicInteger ID = new AtomicInteger(0);

    private final Queue<Request> pendings = new ConcurrentLinkedQueue<Request>();
    private final PriorityQueue<Request> requests = new PriorityQueue<Request>();
    private final PriorityQueue<PersistentConn> keepalives = new PriorityQueue<PersistentConn>();

    private volatile boolean running = true;

    private final HttpClientConfig config;
    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final Selector selector;

    public HttpClient(HttpClientConfig config) throws IOException {
        this.config = config;
        int id = ID.incrementAndGet();
        String name = "http-client";
        if (id > 1) {
            name = name + "#" + id;
        }
        selector = Selector.open();
        Thread t = new Thread(this, name);
        t.setDaemon(true);
        t.start();
    }

    private void clearTimeouted(long now) {
        Request r;
        while ((r = requests.peek()) != null) {
            if (r.isTimeout(now)) {
                String msg;
                if (r.connected()) {
                    msg = "read timeout: ";
                } else {
                    msg = "connect timeout: ";
                }
                r.finish(new TimeoutException(msg + r.timeOutMs + "ms"));
                requests.poll();
            } else {
                break;
            }
        }

        PersistentConn pc;
        while ((pc = keepalives.peek()) != null) {
            if (pc.isTimeout(now)) {
                try {
                    pc.key.channel().close();
                } catch (IOException ignore) {
                }
                keepalives.poll();
            } else {
                break;
            }
        }
    }

    private void doRead(SelectionKey key, long now) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear();
            int read = ch.read(buffer);
            if (read == -1) {
                closeQuiety(key);
                req.finish(); // read all, remote closed it cleanly
            } else if (read > 0) {
                req.onProgress(now);
                buffer.flip();
                try {
                    if (req.decoder.decode(buffer) == ALL_READ) {
                        req.finish();
                        keepalives.offer(new PersistentConn(now + config.keepalive, req.addr,
                                key));
                    }
                } catch (HTTPException e) {
                    closeQuiety(key);
                    req.finish(e);
                } catch (Exception e) {
                    closeQuiety(key);
                    req.finish();
                    HttpUtils.printError("Should not happend!!", e); // decoding
                }
            }
        } catch (IOException e) { // The remote forcibly closed the connection
            closeQuiety(key);
            req.finish(e);
        }
    }

    private void closeQuiety(SelectionKey key) {
        try {
            keepalives.remove(key);
            key.channel().close();
        } catch (Exception ignore) {
        }
    }

    private void doWrite(SelectionKey key) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            ByteBuffer request = req.request;
            ch.write(request);
            if (!request.hasRemaining()) {
                key.interestOps(OP_READ);
            }
        } catch (IOException e) {
            closeQuiety(key);
            req.finish(e);
        }
    }

    public void exec(String url, HttpMethod method, Map<String, String> headers, byte[] body,
            int timeoutMs, IRespListener cb) {
        URI uri = null;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            cb.onThrowable(e);
            return;
        }
        if (!"http".equals(uri.getScheme())) {
            cb.onThrowable(new ProtocolException("Scheme " + uri.getScheme()
                    + " is not supported"));
            return;
        }
        // copy to modify
        if (headers == null) {
            headers = new TreeMap<String, String>();
        } else {
            headers = new TreeMap<String, String>(headers);
        }
        headers.put(HOST, uri.getHost());
        headers.put(ACCEPT, "*/*");

        if (headers.get(USER_AGENT) == null) // allow override
            headers.put(USER_AGENT, config.userAgent); // default
        if (!headers.containsKey(ACCEPT_ENCODING))
            headers.put(ACCEPT_ENCODING, "gzip, deflate");

        int length = 64 + headers.size() * 48;
        if (body != null) {
            headers.put(CONTENT_LENGTH, Integer.toString(body.length));
            length += body.length;
        }
        String path = getPath(uri);
        DynamicBytes bytes = new DynamicBytes(length);

        bytes.append(method.toString()).append(SP).append(path).append(SP);
        bytes.append("HTTP/1.1\r\n");
        Iterator<Map.Entry<String, String>> ite = headers.entrySet().iterator();
        while (ite.hasNext()) {
            Map.Entry<String, String> e = ite.next();
            if (e.getValue() != null) {
                bytes.append(e.getKey()).append(COLON).append(SP).append(e.getValue());
                bytes.append(CR).append(LF);
            }
        }
        bytes.append(CR).append(LF);
        if (body != null) {
            bytes.append(body, 0, body.length);
        }

        ByteBuffer request = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        if (timeoutMs == -1) {
            timeoutMs = config.timeOutMs;
        }
        try {
            InetSocketAddress addr = getServerAddr(uri); // Maybe slow
            pendings.offer(new Request(addr, request, cb, requests, timeoutMs));
            selector.wakeup();
        } catch (UnknownHostException e) {
            cb.onThrowable(e);
        }
    }

    private void finishConnect(SelectionKey key, long now) {
        SocketChannel ch = (SocketChannel) key.channel();
        Request req = (Request) key.attachment();
        try {
            if (ch.finishConnect()) {
                req.setConnected();
                req.onProgress(now);
                key.interestOps(OP_WRITE);
            }
        } catch (IOException e) {
            req.finish(e);
        }
    }

    private void processPendings(long currentTime) {
        Request job = null;
        while ((job = pendings.poll()) != null) {
            try {
                PersistentConn con = keepalives.remove(job.addr);
                if (con != null) { // keep alive
                    SelectionKey key = con.key;
                    key.attach(job);
                    key.interestOps(OP_WRITE);
                } else {
                    SocketChannel ch = SocketChannel.open();
                    ch.configureBlocking(false);
                    ch.register(selector, OP_CONNECT, job);
                    ch.connect(job.addr);
                    requests.offer(job);
                }
            } catch (IOException e) {
                job.finish(e);
                HttpUtils.printError("Try to connect " + job.addr, e);
            }
        }
    }

    public void run() {
        while (running) {
            try {
                long now = currentTimeMillis();
                int select = selector.select(2000);
                if (select > 0) {
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> ite = selectedKeys.iterator();
                    while (ite.hasNext()) {
                        SelectionKey key = ite.next();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isConnectable()) {
                            finishConnect(key, now);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        } else if (key.isReadable()) {
                            doRead(key, now);
                        }
                        ite.remove();
                    }
                }
                processPendings(now);
                clearTimeouted(now);
            } catch (Exception e) {
                HttpUtils.printError("Please catch this exception!!", e);
            }
        }
    }

    public void stop() throws IOException {
        running = false;
        if (selector != null) {
            selector.close();
        }
    }

    @Override
    public String toString() {
        return this.getClass().getCanonicalName() + config.toString();
    }
}
