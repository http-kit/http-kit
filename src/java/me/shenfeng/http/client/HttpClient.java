package me.shenfeng.http.client;

import static java.lang.System.currentTimeMillis;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static me.shenfeng.http.HttpUtils.ACCEPT;
import static me.shenfeng.http.HttpUtils.ACCEPT_ENCODING;
import static me.shenfeng.http.HttpUtils.BUFFER_SIZE;
import static me.shenfeng.http.HttpUtils.COLON;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.HOST;
import static me.shenfeng.http.HttpUtils.LF;
import static me.shenfeng.http.HttpUtils.SP;
import static me.shenfeng.http.HttpUtils.USER_AGENT;
import static me.shenfeng.http.HttpUtils.getPath;
import static me.shenfeng.http.client.DState.ABORTED;
import static me.shenfeng.http.client.DState.ALL_READ;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.HttpUtils;

public final class HttpClient implements Runnable {
    private static final AtomicInteger ID = new AtomicInteger(0);

    private final Queue<Request> pendings = new ConcurrentLinkedQueue<Request>();

    private final PriorityQueue<Request> requests = new PriorityQueue<Request>();

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

    private void clearTimeouted(long currentTime) {
        Request r;
        while ((r = requests.peek()) != null) {
            if (!r.isTimeout(currentTime)) {
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
    }

    private void doRead(SelectionKey key, long currentTime) {
        Request req = (Request) key.attachment();
        try {
            buffer.clear();
            int read = ((SocketChannel) key.channel()).read(buffer);
            if (read == -1) {
                req.finish();
            } else if (read > 0) {
                req.onProgress(currentTime);
                buffer.flip();
                Decoder decoder = req.decoder;
                DState state = decoder.decode(buffer);
                if (state == ALL_READ) {
                    req.finish();
                } else if (state == ABORTED) {
                    req.finish(new AbortException());
                }
            }
        } catch (Exception e) {
            // IOException the remote forcibly closed the connection
            // LineTooLargeException
            // ProtoalException
            req.finish(e);
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
            req.finish(e);
        }
    }

    @Override
    public String toString() {
        return this.getClass().getCanonicalName() + config.toString();
    }

    public void exec(URI uri, HttpMethod method, Map<String, String> headers, byte[] body,
            int timeoutMs, IRespListener cb) {
        // copy to modify
        if (headers == null) {
            headers = new HashMap<String, String>();
        } else {
            headers = new HashMap<String, String>(headers);
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
        bytes.append("HTTP/1.1").append(CR).append(LF);
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
        pendings.offer(new Request(request, cb, timeoutMs, uri, requests));
        selector.wakeup();
    }

    private void processPendings(long currentTime) {
        Request job;
        try {
            while ((job = pendings.poll()) != null) {
                if (job.addr != null) { // if DNS lookup fail
                    SocketChannel ch = SocketChannel.open();
                    job.ch = ch; // save for use when timeout
                    ch.configureBlocking(false);
                    ch.register(selector, OP_CONNECT, job);
                    ch.connect(job.addr);
                    requests.offer(job);
                }
            }
        } catch (IOException e) {
            HttpUtils.printError("error when process requests", e);
        }
    }

    public void run() {
        while (running) {
            try {
                long currentTime = currentTimeMillis();
                processPendings(currentTime);
                int select = selector.select(1000);
                if (select > 0) {
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> ite = selectedKeys.iterator();
                    while (ite.hasNext()) {
                        SelectionKey key = ite.next();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isConnectable()) {
                            finishConnect(key, currentTime);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        } else if (key.isReadable()) {
                            doRead(key, currentTime);
                        }
                    }
                    selectedKeys.clear();
                }
                clearTimeouted(currentTime);
            } catch (Exception e) {
                HttpUtils.printError("Please catch this exception", e);
            }
        }
    }

    private void finishConnect(SelectionKey key, long currentTime) {
        SocketChannel ch = (SocketChannel) key.channel();
        Request attr = (Request) key.attachment();
        try {
            if (ch.finishConnect()) {
                attr.setConnected();
                attr.onProgress(currentTime);
                key.interestOps(OP_WRITE);
            }
        } catch (IOException e) {
            attr.finish(e);
        }
    }

    public void stop() throws IOException {
        running = false;
        if (selector != null) {
            selector.close();
        }
    }
}
