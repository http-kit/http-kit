package org.httpkit.client;

import static java.lang.System.currentTimeMillis;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static org.httpkit.HttpUtils.BUFFER_SIZE;
import static org.httpkit.HttpUtils.SP;
import static org.httpkit.HttpUtils.getServerAddr;
import static org.httpkit.client.State.ALL_READ;
import static org.httpkit.client.State.READ_INITIAL;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.httpkit.*;
import org.httpkit.ProtocolException;

public final class HttpClient implements Runnable {
    private static final AtomicInteger ID = new AtomicInteger(0);

    private final Queue<Request> pending = new ConcurrentLinkedQueue<Request>();
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
        String name = "client-loop";
        if (id > 1) {
            name = name + "#" + id;
        }
        selector = Selector.open();
        Thread t = new Thread(this, name);
        t.setDaemon(true);
        t.start();
    }

    private void clearTimeout(long now) {
        Request r;
        while ((r = requests.peek()) != null) {
            if (r.isTimeout(now)) {
                String msg;
                if (r.isConnected) {
                    msg = "read timeout: ";
                } else {
                    msg = "connect timeout: ";
                }
                // will remove it
                r.finish(new TimeoutException(msg + r.timeOutMs + "ms"));
                if (r.key != null) {
                    closeQuietly(r.key);
                }
            } else {
                break;
            }
        }

        PersistentConn pc;
        while ((pc = keepalives.peek()) != null) {
            if (pc.isTimeout(now)) {
                closeQuietly(pc.key);
                keepalives.poll();
            } else {
                break;
            }
        }
    }

    private boolean cleanAndRetryIfBroken(SelectionKey key, Request req) {
        closeQuietly(key);
        keepalives.remove(key);
        // keep-alived connection, remote server close it without sending byte
        if (req.isKeepAlived && req.decoder.state == READ_INITIAL) {
            for (ByteBuffer b : req.request) {
                b.position(0); // reset for retry
            }
            req.isKeepAlived = false;
            requests.remove(req); // remove from timeout queue
            pending.offer(req); // queue for retry
            selector.wakeup();
            return true;
        }
        return false;
    }

    private void doRead(SelectionKey key, long now) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        buffer.clear();
        int read = 0;
        try {
            read = ch.read(buffer);
        } catch (IOException e) { // The remote forcibly closed the connection
            if (!cleanAndRetryIfBroken(key, req)) {
                // os X get Connection reset by peer error,
                req.finish(e);
            }
        }

        if (read == -1) { // read all, remote closed it cleanly
            if (!cleanAndRetryIfBroken(key, req)) {
                req.finish();
            }
        } else if (read > 0) {
            req.onProgress(now);
            buffer.flip();
            try {
                if (req.decoder.decode(buffer) == ALL_READ) {
                    req.finish();
                    keepalives.offer(new PersistentConn(now + config.keepalive, req.addr, key));
                }
            } catch (HTTPException e) {
                closeQuietly(key);
                req.finish(e);
            } catch (Exception e) {
                closeQuietly(key);
                req.finish(e);
                HttpUtils.printError("Should not happen!!", e); // decoding
            }
        }
    }

    private void closeQuietly(SelectionKey key) {
        try {
            key.channel().close();
        } catch (Exception ignore) {
        }
    }

    private void doWrite(SelectionKey key) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            ByteBuffer[] request = req.request;
            ch.write(request);
            if (!request[request.length - 1].hasRemaining()) {
                key.interestOps(OP_READ);
            }
        } catch (IOException e) {
            if (!cleanAndRetryIfBroken(key, req)) {
                req.finish(e);
            }
        }
    }

    public void exec(String url, HttpMethod method, Map<String, Object> headers, Object body,
            int timeoutMs, IRespListener cb) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            cb.onThrowable(e);
            return;
        }
        if (!"http".equals(uri.getScheme())) {
            cb.onThrowable(new ProtocolException(uri.getScheme() + " is not supported"));
            return;
        }

        InetSocketAddress addr;
        try {
            addr = getServerAddr(uri);
        } catch (UnknownHostException e) {
            cb.onThrowable(e);
            return;
        }

        // copy to modify, normalize header
        headers = HttpUtils.camelCase(headers);
        headers.put("Host", HttpUtils.getHost(uri));
        headers.put("Accept", "*/*");

        if (!headers.containsKey("User-Agent")) // allow override
            headers.put("User-Agent", config.userAgent); // default
        if (!headers.containsKey("Accept-Encoding"))
            headers.put("Accept-Encoding", "gzip, deflate");

        ByteBuffer request[];
        try {
            request = encode(method, headers, body, uri);
        } catch (IOException e) {
            cb.onThrowable(e);
            return;
        }
        if (timeoutMs == -1) {
            timeoutMs = config.timeOutMs;
        }

        pending.offer(new Request(addr, request, cb, requests, timeoutMs, method));
        selector.wakeup();
    }

    private ByteBuffer[] encode(HttpMethod method, Map<String, Object> headers, Object body,
            URI uri) throws IOException {
        ByteBuffer bodyBuffer = HttpUtils.bodyBuffer(body);

        if (body != null) {
            headers.put("Content-Length", Integer.toString(bodyBuffer.remaining()));
        } else {
            headers.put("Content-Length", "0");
        }
        DynamicBytes bytes = new DynamicBytes(196);
        bytes.append(method.toString()).append(SP).append(HttpUtils.getPath(uri));
        bytes.append(" HTTP/1.1\r\n");
        HttpUtils.encodeHeaders(bytes, headers);
        ByteBuffer headBuffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());

        if (bodyBuffer == null) {
            return new ByteBuffer[] { headBuffer };
        } else {
            return new ByteBuffer[] { headBuffer, bodyBuffer };
        }
    }

    private void finishConnect(SelectionKey key, long now) {
        SocketChannel ch = (SocketChannel) key.channel();
        Request req = (Request) key.attachment();
        try {
            if (ch.finishConnect()) {
                req.isConnected = true;
                req.onProgress(now);
                key.interestOps(OP_WRITE);
            }
        } catch (IOException e) {
            closeQuietly(key); // not added to kee-alive yet;
            req.finish(e);
        }
    }

    private void processPending() {
        Request job = null;
        while ((job = pending.poll()) != null) {
            PersistentConn con = keepalives.remove(job.addr);
            if (con != null) { // keep alive
                SelectionKey key = con.key;
                if (key.isValid()) {
                    job.isKeepAlived = true;
                    key.attach(job);
                    key.interestOps(OP_WRITE);
                    requests.offer(job);
                    continue;
                } else {
                    // this should not happen often
                    closeQuietly(key);
                }
            }
            try {
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                // saved for timeout
                job.key = ch.register(selector, OP_CONNECT, job);
                ch.connect(job.addr);
                requests.offer(job);
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
                        } else if (key.isReadable()) {
                            doRead(key, now);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        }
                        ite.remove();
                    }
                }
                clearTimeout(now);
                processPending();
            } catch (IOException e) {
                HttpUtils.printError("select exception", e);
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
