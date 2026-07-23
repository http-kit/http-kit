package org.httpkit.client;

import org.httpkit.*;
import org.httpkit.ProtocolException;
import org.httpkit.logger.ContextLogger;
import org.httpkit.logger.EventNames;
import org.httpkit.logger.EventLogger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
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

public class HttpClient implements Runnable {
    private static final AtomicInteger ID = new AtomicInteger(0);

    private static SSLContext defaultContext = null;

    // queue request, for only issue connection in the IO thread
    private final Queue<Request> pending = new ConcurrentLinkedQueue<Request>();
    // ongoing requests, saved for timeout check
    private final PriorityQueue<Request> requests = new PriorityQueue<Request>();
    // reuse TCP connection
    private final PriorityQueue<PersistentConn> keepalives = new PriorityQueue<PersistentConn>();
    private final long maxConnections;
    private volatile long numConnections = 0;
    private volatile boolean running = true;
    private Thread loopThread;

    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);
    private final Selector selector;

    private final ContextLogger<String, Throwable> errorLogger;
    private final EventLogger<String> eventLogger;
    private final EventNames eventNames;

    public static interface AddressFinder {
        public static final AddressFinder DEFAULT = new AddressFinder() {
            public SocketAddress findAddress(URI uri) throws UnknownHostException {
                return getServerAddr(uri);
            }
        };
        SocketAddress findAddress(URI uri) throws UnknownHostException;
    }

    public static interface ChannelFactory {
        public static final ChannelFactory DEFAULT = new ChannelFactory() {
            public SocketChannel createChannel(SocketAddress address) throws IOException {
                SocketChannel ch = SocketChannel.open();
                ch.setOption(StandardSocketOptions.SO_KEEPALIVE, Boolean.TRUE);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
                return ch;
            }
        };

        SocketChannel createChannel(SocketAddress address) throws IOException;
    }
    public static interface SSLEngineURIConfigurer {
        public static final SSLEngineURIConfigurer NOP = new SSLEngineURIConfigurer() {
            public void configure(SSLEngine sslEngine, URI uri) { /* do nothing */ }
        };
        void configure(SSLEngine sslEngine, URI uri);
    }

    private final AddressFinder addressFinder;

    private final ChannelFactory channelFactory;

    private final SSLEngineURIConfigurer sslEngineUriConfigurer;
    private final SocketAddress bindAddress;

    public static SSLContext getDefaultContext() {
        if(defaultContext==null) {
            try {
                defaultContext = SSLContext.getDefault();
            } catch (Exception e) {
                throw new Error("Failed to initialize SSLContext", e);
            }
        }
        return defaultContext;
    }

    public HttpClient() throws IOException {
        this(-1);
    }

    public HttpClient (long maxConnections, AddressFinder addressFinder, ChannelFactory channelFactory, SSLEngineURIConfigurer sslEngineUriConfigurer,
            ContextLogger<String, Throwable> errorLogger,
            EventLogger<String> eventLogger, EventNames eventNames, SocketAddress bindAddress) throws IOException {
        this.maxConnections = maxConnections;
        this.addressFinder = addressFinder;
        this.channelFactory = channelFactory;
        this.sslEngineUriConfigurer = sslEngineUriConfigurer;
        this.errorLogger = errorLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
        this.bindAddress = bindAddress;
        selector = Selector.open();
        init();
    }

    public HttpClient(long maxConnections, AddressFinder addressFinder, SSLEngineURIConfigurer sslEngineUriConfigurer,
            ContextLogger<String, Throwable> errorLogger,
            EventLogger<String> eventLogger, EventNames eventNames) throws IOException {
      this(maxConnections, addressFinder, sslEngineUriConfigurer, errorLogger, eventLogger, eventNames, null);
    }

    private final void init(){
        getDefaultContext();
        int id = ID.incrementAndGet();
        String name = "client-loop";
        if (id > 1) {
            name = name + "#" + id;
        }

        Thread t = new Thread(this, name);
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }
    public HttpClient(long maxConnections, AddressFinder addressFinder, SSLEngineURIConfigurer sslEngineUriConfigurer,
            ContextLogger<String, Throwable> errorLogger,
            EventLogger<String> eventLogger, EventNames eventNames, SocketAddress bindAddress) throws IOException {
        this.maxConnections = maxConnections;
        this.addressFinder = addressFinder;
        this.sslEngineUriConfigurer = sslEngineUriConfigurer;
        this.errorLogger = errorLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
        this.bindAddress = bindAddress;
        this.channelFactory = ChannelFactory.DEFAULT;
        selector = Selector.open();
        init();
    }

    public HttpClient(long maxConnections) throws IOException {
        this(maxConnections, AddressFinder.DEFAULT, SSLEngineURIConfigurer.NOP,
                ContextLogger.ERROR_PRINTER, EventLogger.NOP, EventNames.DEFAULT);
    }

    private void clearTimeout(long now) {
        Request r;
        while ((r = requests.peek()) != null) {
            if (r.isTimeout(now)) {
                boolean connected = r.isConnected();
                String msg = connected ? "idle timeout: " : "connect timeout: ";
                long timeout = connected ? r.cfg.idleTimeout : r.cfg.connTimeout;
                // will remove it from queue
                r.finish(new TimeoutException(msg + timeout + "ms"));
                if (r.key != null) {
                    closeQuietly(r.key);
                } else {
                    pending.remove(r);
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
        if (req.isReuseConn && req.decoder.state == READ_INITIAL
                && (req.cfg.method == HttpMethod.GET || req.cfg.method == HttpMethod.HEAD)) {
            req.unrecycle();
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
            closeQuietly(key);
            req.finish(e);
        }

        if (req instanceof HttpsRequest
                && ((HttpsRequest) req).consumeIoProgress() > 0) {
            req.onProgress(now);
        }

        if (read == -1) { // read all, remote closed it cleanly
            if (!cleanAndRetryIfBroken(key, req)) {
                if (req.decoder.completesOnEof()) {
                    req.finish();
                } else {
                    req.finish(new ProtocolException("Unexpected EOF before response completed"));
                }
            }
        } else if (read > 0) {
            if (!(req instanceof HttpsRequest)) {
                req.onProgress(now);
            }
            buffer.flip();
            try {
                State oldState = req.decoder.state;
                if (req.decoder.decode(buffer) == ALL_READ) {
                    req.finish();
                    boolean tlsClosed = req instanceof HttpsRequest
                            && ((HttpsRequest) req).isConnectionClosed();
                    if (req.cfg.keepAlive > 0 && req.decoder.isPersistent() && !tlsClosed) {
                        // Ensure that the key is added to keepalives exactly once on a state transition. There could be cases where decoder reaches
                        // ALL_READ state multiple times.
                        if (oldState != ALL_READ) {
                            keepalives.offer(new PersistentConn(now + req.cfg.keepAlive, req.addr, req.host, key));
                        }
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
                errorLogger.log("should not happen", e); // decoding
                eventLogger.log(eventNames.clientImpossible);
            }
        }
    }

    private void closeQuietly(SelectionKey key) {
        boolean closed = false;
        try {
            // TODO engine.closeInbound
            if (key != null && key.channel().isOpen()) {
                key.channel().close();
                closed = true;
            }
        } catch (Exception ignore) {
        }
        if (closed) {
            numConnections--;
        }
    }

    private void doWrite(SelectionKey key, long now) {
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
                        closeQuietly(key);
                        req.finish(new EOFException("EOF during TLS handshake"));
                    }
                }
            } else {
                ByteBuffer[] buffers = req.request;
                long written = ch.write(buffers);
                if (written > 0) {
                    req.onProgress(now);
                }
                if (!buffers[buffers.length - 1].hasRemaining()) {
                    key.interestOps(OP_READ);
                }
            }
        } catch (IOException e) {
            if (!cleanAndRetryIfBroken(key, req)) {
                req.finish(e);
            }
        } catch (Exception e) { // rarely happen
            closeQuietly(key);
            req.finish(e);
        }
        if (req instanceof HttpsRequest
                && ((HttpsRequest) req).consumeIoProgress() > 0) {
            req.onProgress(now);
        }
    }

    private synchronized void submit(Request request) {
        if (running) {
            pending.offer(request);
            selector.wakeup();
        } else {
            request.finish(new IOException("HTTP client is stopped"));
        }
    }



    public void exec(String url, RequestConfig cfg, SSLEngine engine, IRespListener cb) {
        if (!running) {
            cb.onThrowable(new IOException("HTTP client is stopped"));
            return;
        }

        String host = null;
        URI uri,proxyUri = null;
        try {
            uri = new URI(url);
            if (cfg.proxy_url != null) {
                proxyUri = new URI(cfg.proxy_url);
            }
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

        SocketAddress addr;
        try {
            if (proxyUri == null) {
                addr = addressFinder.findAddress(uri);
                host = uri.getHost();
            } else {
                addr = addressFinder.findAddress(proxyUri);
                host = proxyUri.getHost() + uri.getHost();
            }
        } catch (Exception e) {
            cb.onThrowable(e);
            return;
        }

        // copy to modify, normalize header
        HeaderMap headers = HeaderMap.camelCase(cfg.headers);

        if (!headers.containsKey("Host")) // if caller set it explicitly, let he do it
            headers.put("Host", HttpUtils.getHost(uri));
        /**
         * commented on 2014/3/18: Accept is not required
         */
//        if (!headers.containsKey("Accept")) // allow override
//            headers.put("Accept", "*/*");
        if (!headers.containsKey("User-Agent")) // allow override
            headers.put("User-Agent", RequestConfig.DEFAULT_USER_AGENT); // default
        if (!headers.containsKey("Accept-Encoding") && cfg.autoCompression)
            headers.put("Accept-Encoding", "gzip, deflate"); // compression is good

        ByteBuffer request[];
        try {
            if (proxyUri == null) {
                request = encode(cfg.method, headers, cfg.body, HttpUtils.getPath(uri));
            } else {
                String proxyScheme = proxyUri.getScheme();
                headers.put("Proxy-Connection","Keep-Alive");
                if (("http".equals(proxyScheme) && ! "https".equals(scheme)) || cfg.tunnel == false)  {
                    request = encode(cfg.method, headers, cfg.body, uri.toString());
                } else if ( "https".equals(proxyScheme) || "https".equals(scheme) ){
                    headers.put("Host", HttpUtils.getProxyHost(uri));
                    headers.put("Protocol","https");
                    HttpMethod https_method = cfg.tunnel == true ? HttpMethod.valueOf("CONNECT") : cfg.method;
                    request = encode(https_method, headers, cfg.body, HttpUtils.getProxyHost(uri));
                } else {
                    String message = (proxyScheme == null) ? "No proxy protocol specified" : proxyScheme + " for proxy is not supported";
                    cb.onThrowable(new ProtocolException(message));
                    return;
                }
            }
        } catch (Exception e) {
            cb.onThrowable(e);
            return;
        }

        if ((proxyUri == null && "https".equals(scheme))
            || (proxyUri != null && "https".equals(proxyUri.getScheme()))) {
            try {
                URI tlsUri = proxyUri != null && "https".equals(proxyUri.getScheme())
                        ? proxyUri : uri;
                if (engine == null) {
                    engine = getDefaultContext().createSSLEngine(
                            tlsUri.getHost(), HttpUtils.getPort(tlsUri));
                    engine.setUseClientMode(true);
                }
                if(!engine.getUseClientMode())
                    engine.setUseClientMode(true);

                sslEngineUriConfigurer.configure(engine, tlsUri);

                submit(new HttpsRequest(addr, host, request, cb, requests, cfg, engine));
            } catch (Exception e) {
                cb.onThrowable(e);
            }
        } else {
            submit(new Request(addr, host, request, cb, requests, cfg));
        }

//        pending.offer(new Request(addr, request, cb, requests, cfg));
    }

    private ByteBuffer[] encode(HttpMethod method, HeaderMap headers, Object body,
                                String path) throws IOException {
        ByteBuffer bodyBuffer = HttpUtils.bodyBuffer(body);

        if (body != null) {
            String value = Integer.toString(bodyBuffer.remaining());
            headers.putOrReplace("Content-Length", value);
        }

        DynamicBytes bytes = new DynamicBytes(196);
        bytes.append(method.toString()).append(SP).append(path);
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
                req.setConnected(true);
                req.onProgress(now);
                key.interestOps(OP_WRITE);
                if (req instanceof HttpsRequest) {
                    ((HttpsRequest) req).engine.beginHandshake();
                }
            }
        } catch (Exception e) {
            closeQuietly(key); // not added to kee-alive yet;
            req.finish(e);
        }
    }

    private void processPending() {
        for (Request queued : pending) {
            queued.startTimeout();
        }
        Request job = pending.peek();
        if (job != null) {
            if (job.isDone()) {
                pending.poll();
                return;
            }
            job.startTimeout();
            if (job.cfg.keepAlive > 0) {
                PersistentConn con = keepalives.remove(job);
                if (con != null) { // keep alive
                    SelectionKey key = con.key;
                    if (key.isValid()) {
                        // reuse key, engine
                        try {
                            job.recycle((Request) key.attachment());
                            key.attach(job);
                            key.interestOps(OP_WRITE);
                            pending.poll();
                            return;
                        } catch (Exception e) {
                            job.unrecycle();
                            closeQuietly(key);
                        }
                    } else {
                        // this should not happen often
                        closeQuietly(key);
                    }
                }
            }
            if (maxConnections != -1 && numConnections >= maxConnections) {
                PersistentConn idle = keepalives.poll();
                if (idle != null) {
                    closeQuietly(idle.key);
                }
            }
            if (maxConnections == -1 || numConnections < maxConnections) {
                SocketChannel ch = null;
                try {
                    pending.poll();
                    ch = channelFactory.createChannel(job.addr);
                    if (bindAddress != null) {
                      ch.bind(bindAddress);
                    }
                    ch.configureBlocking(false);
                    boolean connected = ch.connect(job.addr);
                    job.setConnected(connected);
                    // if connection is established immediatelly, should wait for write. Fix #98
                    job.key = ch.register(selector, connected ? OP_WRITE : OP_CONNECT, job);
                    numConnections++;
                } catch (Exception e) {
                    if (ch != null) {
                        try {
                            ch.close();
                        } catch (IOException ignore) {
                        }
                    }
                    job.finish(e);
                    // HttpUtils.printError("Try to connect " + job.addr, e);
                }
            }

        }
    }

    public void run() {
        while (running) {
            try {
                Request first = requests.peek();
                long timeout = 2000;
                if (first != null) {
                    timeout = Math.max(first.toTimeout(currentTimeMillis()), 200L);
                }
                int select = selector.select(timeout);
                long now = currentTimeMillis();
                if (select > 0) {
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> ite = selectedKeys.iterator();
                    while (ite.hasNext()) {
                        SelectionKey key = ite.next();
                        ite.remove();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isConnectable()) {
                            finishConnect(key, now);
                        } else if (key.isReadable()) {
                            doRead(key, now);
                        } else if (key.isWritable()) {
                            doWrite(key, now);
                        }
                    }
                }
                clearTimeout(now);
                processPending();
            } catch (Throwable e) { // catch any exception (including OOM), print it: do not exits the loop
                errorLogger.log("select exception, should not happen", e);
                eventLogger.log(eventNames.clientImpossible);
            }
        }
    }

    public synchronized void stop() throws IOException {
        running = false;
        selector.wakeup();

        if (Thread.currentThread() != loopThread) {
            boolean interrupted = false;
            while (loopThread.isAlive()) {
                try {
                    loopThread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        IOException stopped = new IOException("HTTP client is stopped");
        Request request;
        while ((request = pending.poll()) != null) {
            request.finish(stopped);
        }
        while ((request = requests.poll()) != null) {
            request.finish(stopped);
        }

        if (selector.isOpen()) {
            for (SelectionKey selectionKey : selector.keys()) {
                selectionKey.channel().close();
            }
            selector.close();
        }
    }

    @Override
    public String toString() {
        return this.getClass().getCanonicalName();
    }
}
