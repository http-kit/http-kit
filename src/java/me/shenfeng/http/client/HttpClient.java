package me.shenfeng.http.client;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.client.TextRespListener.AbortException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.System.currentTimeMillis;
import static java.net.InetAddress.getByName;
import static java.nio.ByteBuffer.wrap;
import static java.nio.channels.SelectionKey.*;
import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.client.ClientConnState.*;
import static me.shenfeng.http.client.ClientDecoderState.ABORTED;
import static me.shenfeng.http.client.ClientDecoderState.ALL_READ;

public final class HttpClient {

    // socks proxy
    static final byte PROTO_VER5 = 5;

    static final byte CONNECT = 1;

    // socks proxy auth is not implemented
    static final byte NO_AUTH = 0;

    static final byte IPV4 = 1;

    static final byte[] SOCKSV5_VERSION_AUTH = new byte[]{PROTO_VER5, 1,
            NO_AUTH};

    static final byte[] SOCKSV5_CON = new byte[]{PROTO_VER5, CONNECT, 0,
            IPV4};

    private class SelectorLoopThread extends Thread {
        public void run() {
            setName("http-client");
            try {
                eventLoop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

    private final HttpClientConfig config;
    private Queue<ClientAtta> pendingConnect = new ConcurrentLinkedQueue<ClientAtta>();
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
                String msg = "read socks server timeout: ";
                switch (client.state) {
                    case DIRECT_CONNECTING:
                        msg = "connect timeout: ";
                        break;
                    case SOCKS_CONNECTTING:
                        msg = "connect socks server timeout: ";
                        break;
                    case DIRECT_CONNECTED:
                        msg = "read timeout: ";
                        break;
                }
                client.finish(new TimeoutException(msg + config.timeOutMs
                        + "ms"));
            }
        }
    }

    private void doRead(SelectionKey key, long currentTime) {
        ClientAtta atta = (ClientAtta) key.attachment();
        try {
            buffer.clear();
            int read = ((SocketChannel) key.channel()).read(buffer);
            if (read == -1) {
                atta.finish();
            } else if (read > 0) {
                // update for timeout check
                atta.lastActiveTime = currentTime;
                buffer.flip();
                switch (atta.state) {
                    case DIRECT_CONNECTED:
                    case SOCKS_HTTP_REQEUST:
                        ClientDecoder decoder = atta.decoder;
                        ClientDecoderState state = decoder.decode(buffer);
                        if (state == ALL_READ) {
                            atta.finish();
                        } else if (state == ABORTED) {
                            atta.finish(new AbortException());
                        }
                        break;
                    case SOCKS_VERSION_AUTH:
                        if (read == 2) {
                            atta.state = SOCKS_INIT_CONN;
                            key.interestOps(OP_WRITE);
                            // socks server should reply 2 bytes
                        } else {
                            atta.finish(new SocketException(
                                    "Malformed reply from SOCKS server"));
                        }
                        break;
                    case SOCKS_INIT_CONN:
                        if (read == 10 && buffer.get(1) == 0) {
                            atta.state = SOCKS_HTTP_REQEUST;
                            key.interestOps(OP_WRITE);
                        } else {
                            atta.finish(new SocketException(
                                    "Malformed reply from SOCKS server"));
                        }
                        break;
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
        try {
            switch (atta.state) {
                case DIRECT_CONNECTED:
                case SOCKS_HTTP_REQEUST:
                    ByteBuffer request = atta.request;
                    ch.write(request);
                    if (!request.hasRemaining()) {
                        key.interestOps(OP_READ);
                    }
                    break;
                case SOCKS_VERSION_AUTH:
                    ByteBuffer versionAuth = wrap(SOCKSV5_VERSION_AUTH);
                    // no remaining check, since TCP has a large buffer
                    ch.write(versionAuth);
                    key.interestOps(OP_READ);
                    break;
                case SOCKS_INIT_CONN:
                    ByteBuffer con = ByteBuffer.allocate(10);
                    con.put(SOCKSV5_CON); // 4 bytes
                    con.put(getByName(atta.url.getHost()).getAddress()); // 4 bytes
                    con.putShort((short) getPort(atta.url)); // 2 bytes
                    con.flip();
                    ch.write(con);
                    key.interestOps(OP_READ);
                    break;
            }
        } catch (IOException e) {
            atta.finish(e);
        }
    }

    public void get(URI uri, Map<String, String> headers, IRespListener cb)
            throws URISyntaxException, UnknownHostException {
        get(uri, headers, Proxy.NO_PROXY, cb);
    }

    @Override
    public String toString() {
       return this.getClass().getCanonicalName() + config.toString();
    }

    public void post(URI uri, Map<String, String> headers, Map<String, Object> body,
                     IRespListener cb) {
        post(uri, headers, body, Proxy.NO_PROXY, cb);
    }

    public void get(URI uri, Map<String, String> headers, Proxy proxy,
                    IRespListener cb) {
        // copy to modify
        if (headers == null) {
            headers = new HashMap<String, String>();
        } else {
            headers = new HashMap<String, String>(headers);
        }
        exec(uri, HttpMethod.GET, headers, null, proxy, cb);
    }

    public void post(URI uri, Map<String, String> headers,
                     Map<String, Object> body, Proxy proxy, IRespListener cb) {
        // copy to modify
        if (headers == null) {
            headers = new HashMap<String, String>();
        } else {
            headers = new HashMap<String, String>(headers);
        }

        byte[] data = null;
        if (body != null) {
            StringBuilder sb = new StringBuilder(32);
            for (Map.Entry<String, Object> e : body.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("&");
                }
                try {
                    sb.append(URLEncoder.encode(e.getKey(), "utf8"));
                    sb.append("=");
                    sb.append(URLEncoder.encode(e.getValue().toString(),
                            "utf8"));
                } catch (UnsupportedEncodingException ignore) {
                }
            }

            data = sb.toString().getBytes(UTF_8);
            headers.put(CONTENT_TYPE,
                    "application/x-www-form-urlencoded");
            headers.put(CONTENT_LENGTH, Integer.toString(data.length));
        }
        exec(uri, HttpMethod.POST, headers, data, proxy, cb);
    }

    public void exec(URI uri, HttpMethod method, Map<String, String> headers,
                     byte[] body, Proxy proxy, IRespListener cb) {

        headers.put(HOST, uri.getHost());
        headers.put(ACCEPT, "*/*");
        if (headers.get(USER_AGENT) == null) // allow override
            headers.put(USER_AGENT, config.userAgent); // default
        headers.put(ACCEPT_ENCODING, "gzip, deflate");

        int length = 64 + headers.size() * 48;
        if (body != null) {
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
                bytes.append(e.getKey()).append(COLON).append(SP)
                        .append(e.getValue());
                bytes.append(CR).append(LF);
            }
        }
        bytes.append(CR).append(LF);
        if (body != null) {
            bytes.append(body, 0, body.length);
        }

        ByteBuffer request = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        pendingConnect.offer(new ClientAtta(request, cb, proxy, uri));
        selector.wakeup();
    }

    public static void main(String[] args) {
        System.out.println(HttpMethod.DELETE.toString());
    }

    private void processPendings(long currentTime) {
        ClientAtta job;
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
                            switch (attr.state) {
                                case SOCKS_CONNECTTING:
                                    // begin socks handleshake
                                    attr.state = SOCKS_VERSION_AUTH;
                                    break;
                                case DIRECT_CONNECTING:
                                    attr.state = DIRECT_CONNECTED;
                                    break;
                            }
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
                    doRead(key, currentTime);
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
