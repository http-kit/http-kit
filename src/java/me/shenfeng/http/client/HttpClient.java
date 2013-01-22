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
import java.util.Map.Entry;
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
        String name = "client-loop";
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
                // will remove it
                r.finish(new TimeoutException(msg + r.timeOutMs + "ms"));
                if (r.key != null) {
                    closeQuitely(r.key);
                }
            } else {
                break;
            }
        }

        PersistentConn pc;
        while ((pc = keepalives.peek()) != null) {
            if (pc.isTimeout(now)) {
                closeQuitely(pc.key);
                keepalives.poll();
            } else {
                break;
            }
        }
    }

    private void doRead(SelectionKey key, long now) {
        Request req = (Request) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        buffer.clear();
        int read = 0;
        try {
            read = ch.read(buffer);
        } catch (IOException e) { // The remote forcibly closed the connection
            closeQuitely(key);
            req.finish(e);
        }

        if (read == -1) { // read all, remote closed it cleanly
            keepalives.remove(key);// opps, just added
            closeQuitely(key);
            req.finish();
        } else if (read > 0) {
            req.onProgress(now);
            buffer.flip();
            try {
                if (req.decoder.decode(buffer) == ALL_READ) {
                    req.finish();
                    keepalives.offer(new PersistentConn(now + config.keepalive, req.addr, key));
                }
            } catch (HTTPException e) {
                closeQuitely(key);
                req.finish(e);
            } catch (Exception e) {
                closeQuitely(key);
                req.finish();
                HttpUtils.printError("Should not happend!!", e); // decoding
            }
        }
    }

    private void closeQuitely(SelectionKey key) {
        try {
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
            closeQuitely(key);
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
            cb.onThrowable(new ProtocolException(uri.getScheme() + " is not supported"));
            return;
        }

        // copy to modify, normalize header
        TreeMap<String, String> tmp = new TreeMap<String, String>();
        if (headers != null) {
            for (Entry<String, String> e : headers.entrySet()) {
                tmp.put(HttpUtils.camelCase(e.getKey()), e.getValue());
            }
        }
        headers = tmp;
        headers.put("Host", HttpUtils.getHost(uri));
        headers.put("Accept", "*/*");

        if (!headers.containsKey("User-Agent")) // allow override
            headers.put("User-Agent", config.userAgent); // default
        if (!headers.containsKey("Accept-Encoding"))
            headers.put("Accept-Encoding", "gzip, deflate");

        int length = 64 + headers.size() * 48;
        if (body != null) {
            headers.put("Content-Length", Integer.toString(body.length));
            length += body.length;
        }
        DynamicBytes path = HttpUtils.encodeURI(getPath(uri));
        DynamicBytes bytes = new DynamicBytes(length);

        bytes.append(method.toString()).append(SP).append(path.get(), 0, path.length());
        bytes.append(" HTTP/1.1\r\n");
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
            pendings.offer(new Request(addr, request, cb, requests, timeoutMs, method));
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
            closeQuitely(key); // not added to kee-alive yet;
            req.finish(e);
        }
    }

    private void processPendings(long currentTime) {
        Request job = null;
        while ((job = pendings.poll()) != null) {
            PersistentConn con = keepalives.remove(job.addr);
            if (con != null) { // keep alive
                SelectionKey key = con.key;
                if (key.isValid()) {
                    key.attach(job);
                    key.interestOps(OP_WRITE);
                    requests.offer(job);
                    continue;
                } else {
                    // this should not happen often
                    closeQuitely(key);
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
                        try {
                            if (key.isConnectable()) {
                                finishConnect(key, now);
                            } else if (key.isWritable()) {
                                doWrite(key);
                            } else if (key.isReadable()) {
                                doRead(key, now);
                            }
                        } catch (Exception e) {
                            // error in user's response handler
                            ((Request) key.attachment()).finish(e);
                            closeQuitely(key);
                            HttpUtils.printError("Please catch this exception", e);
                        } finally {
                            ite.remove();
                        }
                    }
                }
                clearTimeouted(now);
                processPendings(now);
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
