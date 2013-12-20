package org.httpkit.client;

import org.httpkit.*;
import org.httpkit.ProtocolException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.currentTimeMillis;
import static java.nio.channels.SelectionKey.*;
import static org.httpkit.HttpUtils.SP;
import static org.httpkit.HttpUtils.getServerAddr;
import static org.httpkit.client.State.ALL_READ;
import static org.httpkit.client.State.READ_INITIAL;

public final class HttpClient implements Runnable {
    private static final AtomicInteger ID = new AtomicInteger(0);

    public static final SSLContext DEFAULT_CONTEXT;

    static {
        try {
            DEFAULT_CONTEXT = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new Error("Failed to initialize SSLContext", e);
        }
    }

    // queue request, for only issue connection in the IO thread
    private final Queue<Request> pending = new ConcurrentLinkedQueue<Request>();
    // ongoing requests, saved for timeout check
    private final PriorityQueue<Request> requests = new PriorityQueue<Request>();
    // reuse TCP connection
    private final PriorityQueue<PersistentConn> keepalives = new PriorityQueue<PersistentConn>();

    private volatile boolean running = true;

    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);
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
     * http-kit think all connections are keep-alived (since some say it is, but
     * actually is not). but, some are not, http-kit pick them out after the fact
     * <ol>
     * <li>The connection is reused</li>
     * <li>No data received</li>
     * </ol>
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
            return true; // retry: re-open a connection to server, sent the request again
        }
        return false;
    }

    private void doRead(SelectionKey key, long now) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        int read = 0;
        try {
            buffer.clear();
            if (req instanceof HttpsRequest) {
                HttpsRequest httpsReq = (HttpsRequest) req;
                if (httpsReq.handshaken) {
                    // SSLEngine closed => fine, will return -1 in the next run
                    read = httpsReq.unwrapRead(buffer);
                } else {
                    read = httpsReq.doHandshake(buffer);
                }
            } else {
                read = ch.read(buffer);
            }
        } catch (IOException e) { // The remote forcibly closed the connection
            if (!cleanAndRetryIfBroken(key, req)) {
                req.finish(e); // os X get Connection reset by peer error,
            }
            // java.security.InvalidAlgorithmParameterException: Prime size must be multiple of 64, and can only range from 512 to 1024 (inclusive)
            // java.lang.RuntimeException: Could not generate DH keypair
        } catch (Exception e) {
            req.finish(e);
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
                    if (req.cfg.keepAlive > 0) {
                        keepalives.offer(new PersistentConn(now + req.cfg.keepAlive, req.addr, key));
                    } else {
                        closeQuietly(key);
                    }
                }
            } catch (HTTPException e) {
                closeQuietly(key);
                req.finish(e);
            } catch (Exception e) {
                closeQuietly(key);
                req.finish(e);
                HttpUtils.printError("should not happen", e); // decoding
            }
        }
    }

    private void closeQuietly(SelectionKey key) {
        try {
            // TODO engine.closeInbound
            key.channel().close();
        } catch (Exception ignore) {
        }
    }

    private void doWrite(SelectionKey key) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            if (req instanceof HttpsRequest) {
                HttpsRequest httpsReq = (HttpsRequest) req;
                if (httpsReq.handshaken) {
                    // will flip to OP_READ
                    httpsReq.writeWrappedRequest();
                } else {
                    buffer.clear();
                    if (httpsReq.doHandshake(buffer) < 0) {
                        req.finish(); // will be a No status exception
                    }
                }
            } else {
                ByteBuffer[] buffers = req.request;
                ch.write(buffers);
                if (!buffers[buffers.length - 1].hasRemaining()) {
                    key.interestOps(OP_READ);
                }
            }
        } catch (IOException e) {
            if (!cleanAndRetryIfBroken(key, req)) {
                req.finish(e);
            }
        } catch (Exception e) { // rarely happen
            req.finish(e);
        }
    }

    public void exec(String url, RequestConfig cfg, SSLEngine engine, IRespListener cb) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            cb.onThrowable(e);
            return;
        }

        if (uri.getHost() == null) {
            cb.onThrowable(new IllegalArgumentException("host is null: " + url));
            return;
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            String message = (scheme == null) ? "No protocol specified" : scheme + " is not supported";
            cb.onThrowable(new ProtocolException(message));
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
        HeaderMap headers = HeaderMap.camelCase(cfg.headers);
        headers.put("Host", HttpUtils.getHost(uri));

        if (!headers.containsKey("Accept")) // allow override
            headers.put("Accept", "*/*");
        if (!headers.containsKey("User-Agent")) // allow override
            headers.put("User-Agent", RequestConfig.DEFAULT_USER_AGENT); // default
        if (!headers.containsKey("Accept-Encoding"))
            headers.put("Accept-Encoding", "gzip, deflate"); // compression is good

        ByteBuffer request[];
        try {
            request = encode(cfg.method, headers, cfg.body, uri);
        } catch (IOException e) {
            cb.onThrowable(e);
            return;
        }
        if ("https".equals(scheme)) {
            if (engine == null) {
                engine = DEFAULT_CONTEXT.createSSLEngine();
            }
            engine.setUseClientMode(true);
            pending.offer(new HttpsRequest(addr, request, cb, requests, cfg, engine));
        } else {
            pending.offer(new Request(addr, request, cb, requests, cfg));
        }

//        pending.offer(new Request(addr, request, cb, requests, cfg));
        selector.wakeup();
    }

    private ByteBuffer[] encode(HttpMethod method, HeaderMap headers, Object body,
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
        headers.encodeHeaders(bytes);
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
                boolean connected = ch.connect(job.addr);
                job.isConnected = connected;

                // if connection is established immediatelly, should wait for write. Fix #98
                job.key = ch.register(selector, connected ? OP_WRITE : OP_CONNECT, job);
                // save key for timeout check
                requests.offer(job);
            } catch (IOException e) {
                job.finish(e);
                // HttpUtils.printError("Try to connect " + job.addr, e);
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
            } catch (Throwable e) { // catch any exception (including OOM), print it: do not exits the loop
                HttpUtils.printError("select exception, should not happen", e);
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
