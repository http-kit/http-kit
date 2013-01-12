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
import static me.shenfeng.http.HttpUtils.SELECT_TIMEOUT;
import static me.shenfeng.http.HttpUtils.SP;
import static me.shenfeng.http.HttpUtils.TIMEOUT_CHECK_INTEVAL;
import static me.shenfeng.http.HttpUtils.USER_AGENT;
import static me.shenfeng.http.HttpUtils.getPath;
import static me.shenfeng.http.client.ConnState.DIRECT_CONNECTED;
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
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.HttpUtils;

public final class HttpClient {
    private static final AtomicInteger ID = new AtomicInteger(0);

    private class SelectorLoopThread extends Thread {
        public void run() {
            try {
                eventLoop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final Queue<Attament> pendingConnect = new ConcurrentLinkedQueue<Attament>();

    private long lastTimeoutCheckTime;

    private final LinkedList<Attament> clients = new LinkedList<Attament>();
    private volatile boolean running = true;

    private final HttpClientConfig config;
    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final Selector selector;

    public HttpClient(HttpClientConfig config) throws IOException {
        this.config = config;
        selector = Selector.open();
        SelectorLoopThread thread = new SelectorLoopThread();
        int id = ID.incrementAndGet();
        String name = "http-client";
        if (id > 1) {
            name = name + "#" + id;
        }
        thread.setName(name);
        thread.setDaemon(true);
        thread.start();
    }

    private void clearTimeouted(long currentTime) {
        Iterator<Attament> ite = clients.iterator();
        while (ite.hasNext()) {
            Attament c = ite.next();
            ite.remove();
            if (!c.finished && (c.timeOutMs + c.lastActiveTime < currentTime)) {
                String msg = "read socks server timeout: ";
                switch (c.state) {
                case DIRECT_CONNECTING:
                    msg = "connect timeout: ";
                    break;
                case DIRECT_CONNECTED:
                    msg = "read timeout: ";
                    break;
                }
                c.finish(new TimeoutException(msg + c.timeOutMs + "ms"));
            }
        }
    }

    private void doRead(SelectionKey key, long currentTime) {
        Attament atta = (Attament) key.attachment();
        try {
            buffer.clear();
            int read = ((SocketChannel) key.channel()).read(buffer);
            if (read == -1) {
                atta.finish();
            } else if (read > 0) {
                // update for timeout check
                atta.lastActiveTime = currentTime;
                buffer.flip();
                Decoder decoder = atta.decoder;
                DState state = decoder.decode(buffer);
                if (state == ALL_READ) {
                    atta.finish();
                } else if (state == ABORTED) {
                    atta.finish(new AbortException());
                }
            }
        } catch (Exception e) {
            // IOException the remote forcibly closed the connection
            // LineTooLargeException
            // ProtoalException
            atta.finish(e);
        }
    }

    private void doWrite(SelectionKey key) {
        Attament atta = (Attament) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer request = atta.request;
        try {
            ch.write(request);
            if (!request.hasRemaining()) {
                key.interestOps(OP_READ);
            }
        } catch (IOException e) {
            atta.finish(e);
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
        pendingConnect.offer(new Attament(request, cb, timeoutMs, uri));
        selector.wakeup();
    }

    private void processPendings(long currentTime) {
        Attament job;
        try {
            while ((job = pendingConnect.poll()) != null) {
                if (job.addr != null) { // if DNS lookup fail
                    SocketChannel ch = SocketChannel.open();
                    job.ch = ch; // save for use when timeout
                    job.lastActiveTime = currentTime;
                    ch.configureBlocking(false);
                    ch.register(selector, OP_CONNECT, job);
                    ch.connect(job.addr);
                    clients.add(job);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void eventLoop() throws IOException {
        lastTimeoutCheckTime = System.currentTimeMillis();
        while (running) {
            Set<SelectionKey> selectedKeys;
            try {
                long currentTime = currentTimeMillis();
                processPendings(currentTime);
                // TODO timeout should be check more frequently. use a
                // PriorityQueue?
                if (currentTime - lastTimeoutCheckTime > TIMEOUT_CHECK_INTEVAL) {
                    clearTimeouted(currentTime);
                    lastTimeoutCheckTime = currentTime;
                }
                int select = selector.select(SELECT_TIMEOUT);
                if (select <= 0) {
                    continue;
                }
                selectedKeys = selector.selectedKeys();
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
            } catch (Exception e) {
                HttpUtils.printError("Please catch this exception", e);
            }
        }
    }

    private void finishConnect(SelectionKey key, long currentTime) {
        SocketChannel ch = (SocketChannel) key.channel();
        Attament attr = (Attament) key.attachment();
        try {
            if (ch.finishConnect()) {
                attr.state = DIRECT_CONNECTED;
                attr.lastActiveTime = currentTime;
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
