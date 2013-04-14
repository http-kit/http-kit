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
import org.httpkit.PriorityQueue;
import org.httpkit.ProtocolException;

import javax.net.ssl.SSLException;

public final class HttpClient implements Runnable {
    private static final AtomicInteger ID = new AtomicInteger(0);

    private final Queue<Request> pending = new ConcurrentLinkedQueue<Request>();
    private final PriorityQueue<Request> requests = new PriorityQueue<Request>();
    private final PriorityQueue<PersistentConn> keepalives = new PriorityQueue<PersistentConn>();

    private volatile boolean running = true;

    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final Selector selector;

    public HttpClient() throws IOException {
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
                String msg = "connect timeout: ";
                if (r.isConnected) {
                    msg = "read timeout: ";
                }
                // will remove it from queue
                r.finish(new TimeoutException(msg + r.cfg.timeout + "ms"));
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

    /**
     * tricky part
     * <p/>
     * http-kit think all connections are keep-alived (since some say it is, but
     * actually is not). but, some are not, http-kit pick them out **after the
     * fact** 1. the connection is resued 2. no data received
     */
    private boolean cleanAndRetryIfBroken(SelectionKey key, Request req) {
        closeQuietly(key);
        keepalives.remove(key);
        // keep-alived connection, remote server close it without sending byte
        if (req.isReuseConn && req.decoder.state == READ_INITIAL) {
            for (ByteBuffer b : req.request) {
                b.position(0); // reset for retry
            }
            req.isReuseConn = false;
            requests.remove(req); // remove from timeout queue
            pending.offer(req); // queue for retry
            selector.wakeup();
            // retry (re-open a connection to server, sent the request again)
            return true;
        }
        return false;
    }

    private void doRead(SelectionKey key, long now) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        int read = 0;
        boolean https = req instanceof HttpsRequest;
        try {
            if (https && !((HttpsRequest) req).handshaken) {
                // TODO when read < 0, it's an error
                buffer.clear();
                read = ((HttpsRequest) req).doHandshake(buffer);
                return;
            } else if (https) {
                buffer.clear();
                read = ((HttpsRequest) req).unwrapRead(buffer);
            } else {
                buffer.clear();
                read = ch.read(buffer);
            }
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
                    if (req.cfg.keepAlive > 0)
                        keepalives.offer(new PersistentConn(now + req.cfg.keepAlive, req.addr, key));
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
        boolean https = req instanceof HttpsRequest;
        try {
            if (https && !((HttpsRequest) req).handshaken) {
                buffer.clear();
                int b = ((HttpsRequest) req).doHandshake(buffer);
//                if(b < 0)
            } else if (https) {
                ((HttpsRequest) req).writeWrapped();
            } else {
                ByteBuffer[] request = req.request;
                ch.write(request);
                if (!request[request.length - 1].hasRemaining()) {
                    key.interestOps(OP_READ);
                }
            }
        } catch (IOException e) {
            if (!cleanAndRetryIfBroken(key, req)) {
                req.finish(e);
            }
        }
    }

    public void exec(String url, Map<String, Object> headers, Object body,
                     HttpRequestConfig cfg, IRespListener cb) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            cb.onThrowable(e);
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            cb.onThrowable(new ProtocolException(scheme + " is not supported"));
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
            headers.put("User-Agent", HttpRequestConfig.DEFAULT_USER_AGENT); // default
        if (!headers.containsKey("Accept-Encoding"))
            headers.put("Accept-Encoding", "gzip, deflate"); // compression is good

        ByteBuffer request[];
        try {
            request = encode(cfg.method, headers, uri, body);
        } catch (IOException e) {
            cb.onThrowable(e);
            return;
        }
        if ("https".equals(scheme)) {
            pending.offer(new HttpsRequest(addr, request, cb, requests, cfg));
        } else {
            pending.offer(new Request(addr, request, cb, requests, cfg));
        }
        selector.wakeup();
    }

    private ByteBuffer[] encode(HttpMethod method, Map<String, Object> headers, URI uri, Object body) throws IOException {
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
            return new ByteBuffer[]{headBuffer};
        } else {
            return new ByteBuffer[]{headBuffer, bodyBuffer};
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
                if (req instanceof HttpsRequest) {
                    ((HttpsRequest) req).engine.beginHandshake();
                }
            }
        } catch (IOException e) {
            closeQuietly(key); // not added to kee-alive yet;
            req.finish(e);
        }
    }

    private void processPending() {
        Request job = null;
        while ((job = pending.poll()) != null) {
            if (job.cfg.keepAlive > 0) {
                PersistentConn con = keepalives.remove(job.addr);
                if (con != null) { // keep alive
                    SelectionKey key = con.key;
                    if (key.isValid()) {
                        job.isReuseConn = true;
                        // reuse key, engine
                        try {
                            job.recycle((Request) key.attachment());
                            key.attach(job);
                            key.interestOps(OP_WRITE);
                            requests.offer(job);
                            continue;
                        } catch (SSLException e) {
                            closeQuietly(key); // https wrap SSLException, start from fresh
                        }
                    } else {
                        // this should not happen often
                        closeQuietly(key);
                    }
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
        return this.getClass().getCanonicalName();
    }
}
