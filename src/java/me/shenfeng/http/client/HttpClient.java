package me.shenfeng.http.client;

import static java.lang.System.currentTimeMillis;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static me.shenfeng.http.HttpUtils.ACCEPT;
import static me.shenfeng.http.HttpUtils.ACCEPT_ENCODING;
import static me.shenfeng.http.HttpUtils.BUFFER_SIZE;
import static me.shenfeng.http.HttpUtils.HOST;
import static me.shenfeng.http.HttpUtils.SELECT_TIMEOUT;
import static me.shenfeng.http.HttpUtils.TIMEOUT_CHECK_INTEVAL;
import static me.shenfeng.http.HttpUtils.USER_AGENT;
import static me.shenfeng.http.HttpUtils.encodeGetRequest;
import static me.shenfeng.http.HttpUtils.getServerAddr;
import static me.shenfeng.http.client.ClientDecoderState.ABORTED;
import static me.shenfeng.http.client.ClientDecoderState.ALL_READ;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.HttpUtils;

public final class HttpClient {

    private class SelectorLoopThread extends Thread {
        public void run() {
            setName("http-client");
            try {
                startLoop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

    private final HttpClientConfig config;

    private ConcurrentLinkedQueue<ClientAtta> pendings = new ConcurrentLinkedQueue<ClientAtta>();
    private long lastTimeoutCheckTime;

    private LinkedList<ClientAtta> clients = new LinkedList<ClientAtta>();

    private volatile boolean running = true;

    private Selector selector;

    public HttpClient(HttpClientConfig config) throws IOException {
        this.config = config;
        selector = Selector.open();
        lastTimeoutCheckTime = System.currentTimeMillis();
        SelectorLoopThread thread = new SelectorLoopThread();
        thread.setDaemon(true);
        thread.start();
    }

    private void clearTimeouted(long currentTime) {
        Iterator<ClientAtta> ite = clients.iterator();
        while (ite.hasNext()) {
            ClientAtta client = ite.next();
            if (client.finished) {
                ite.remove();
            } else if (config.timeOutMs + client.lastActiveTime < currentTime) {
                ite.remove();
                client.finish(new TimeoutException("timeout after "
                        + config.timeOutMs + "ms"));
            }
        }
    }

    private void doRead(SelectionKey key) {
        ClientAtta atta = (ClientAtta) key.attachment();
        HttpClientDecoder decoder = atta.decoder;
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear();
            int read = ch.read(buffer);
            System.out.println("read " + read);
            if (read == -1) {
                atta.finish();
            } else if (read > 0) {
                buffer.flip();
                ClientDecoderState state = decoder.decode(buffer);
                System.out.println(state);
                if (state == ALL_READ || state == ABORTED) {
                    atta.finish();
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
        ClientAtta atta = (ClientAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer request = atta.request;
        try {
            ch.write(request);
            if (!request.hasRemaining()) {
                key.interestOps(OP_READ);
            }
        } catch (IOException e) {
            atta.finish();
        }
    }

    public void get(String url, Map<String, String> headers, Proxy proxy,
            IRespListener cb) throws URISyntaxException, UnknownHostException {
        URI uri = new URI(url);

        headers.put(HOST, uri.getHost());
        headers.put(ACCEPT, "*/*");
        if (headers.get(USER_AGENT) == null) // allow override
            headers.put(USER_AGENT, config.userAgent);
        headers.put(ACCEPT_ENCODING, "gzip, deflate");

        InetSocketAddress addr = getServerAddr(uri);

        // HTTP proxy is not supported now
        String path = HttpUtils.getPath(uri);

        ByteBuffer request = encodeGetRequest(path, headers);
        pendings.offer(new ClientAtta(request, addr, cb, proxy));
        selector.wakeup();
    }

    public void post(String uri, Map<String, String> headers, Proxy proxy,
            IRespListener cb) {

    }

    private void processPendings(long currentTime) {
        ClientAtta job;
        try {
            while ((job = pendings.poll()) != null) {
                SocketChannel ch = SocketChannel.open();
                job.ch = ch; // save for use when timeout
                job.lastActiveTime = currentTime;
                ch.configureBlocking(false);
                ch.register(selector, OP_CONNECT, job);
                ch.connect(job.addr);
                clients.add(job);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startLoop() throws IOException {
        SelectionKey key;
        SocketChannel ch;
        while (running) {
            long currentTime = currentTimeMillis();
            processPendings(currentTime);
            if (currentTime - lastTimeoutCheckTime > TIMEOUT_CHECK_INTEVAL) {
                clearTimeouted(currentTime);
                lastTimeoutCheckTime = currentTime;
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
                if (key.isConnectable()) {
                    ch = (SocketChannel) key.channel();
                    try {
                        if (ch.finishConnect()) {
                            ClientAtta attr = (ClientAtta) key.attachment();
                            attr.lastActiveTime = currentTime;
                            key.interestOps(OP_WRITE);
                        }
                    } catch (IOException e) {
                        ClientAtta attr = (ClientAtta) key.attachment();
                        attr.finish(e);
                    }
                } else if (key.isWritable()) {
                    doWrite(key);
                } else if (key.isReadable()) {
                    doRead(key);
                }
            }

            selectedKeys.clear();
        }
    }

    public void stop() throws IOException {
        running = false;
        if (selector != null) {
            selector.close();
        }
    }
}
