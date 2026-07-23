package org.httpkit.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static org.httpkit.HttpUtils.HttpEncode;
import static org.httpkit.HttpUtils.WsEncode;
import static org.httpkit.server.Frame.CloseFrame.CLOSE_AWAY;
import static org.httpkit.server.Frame.CloseFrame.CLOSE_NORMAL;

import java.io.IOException;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.httpkit.HeaderMap;
import org.httpkit.HeadersTooLargeException;
import org.httpkit.HttpMethod;
import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;
import org.httpkit.RequestTooLargeException;
import org.httpkit.logger.ContextLogger;
import org.httpkit.logger.EventNames;
import org.httpkit.logger.EventLogger;
import org.httpkit.server.Frame.BinaryFrame;
import org.httpkit.server.Frame.CloseFrame;
import org.httpkit.server.Frame.PingFrame;
import org.httpkit.server.Frame.PongFrame;
import org.httpkit.server.Frame.TextFrame;

class PendingKey {
    public final SelectionKey key;
    // operation: can be register for write or close the selectionkey
    public final int Op;

    PendingKey(SelectionKey key, int op) {
        this.key = key;
        Op = op;
    }

    public static final int OP_WRITE = -1;
    public static final int RESPONSE_COMPLETE = -2;
}

public class HttpServer implements Runnable {

    static final String THREAD_NAME = "server-loop";

    private final IHandler handler;
    private final int maxBody; // max http body size
    private final int maxLine; // max header line size

    private final int maxWs; // websocket, max message size

    private final Selector selector;
    private final ServerSocketChannel serverChannel;

    private final ProxyProtocolOption proxyProtocolOption;
    private final boolean legacyUnsafeRemoteAddr;

    public final String serverHeader;
    private final SocketAddress socketAddress;

    private Thread serverThread;

    // queue operations from worker threads to the IO thread
    private final ConcurrentLinkedQueue<PendingKey> pending = new ConcurrentLinkedQueue<PendingKey>();

    private final ConcurrentHashMap<SelectionKey, Boolean> keptAlive = new ConcurrentHashMap<SelectionKey, Boolean>();

    enum Status { STOPPED, RUNNING, STOPPING }

    // Will not set keep-alive headers when STOPPING, allowing reqs to drain
    private final AtomicReference<Status> status = new AtomicReference<Status> (Status.STOPPED);

    // shared, single thread
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64 - 1);

    private final ContextLogger<String, Throwable> errorLogger;
    private final ContextLogger<String, Throwable> warnLogger;
    private final EventLogger<String> eventLogger;
    private final EventNames eventNames;

    public static final ContextLogger<String, Throwable> DEFAULT_WARN_LOGGER = new ContextLogger<String, Throwable>() {
        @Override
        public void log(String event, Throwable e) {
            System.err.printf("%s [%s] WARN - %s\n", new Date(), THREAD_NAME, e.getMessage());
        }
    };

    public static interface ServerChannelFactory {
        ServerSocketChannel createChannel(SocketAddress address) throws IOException;
    }
    public static interface AddressFinder {
        SocketAddress findAddress() throws IOException;
    }
    public HttpServer(String ip, int port, IHandler handler, int maxBody, int maxLine, int maxWs,
                      ProxyProtocolOption proxyProtocolOption)
            throws IOException {
        this(ip, port, handler, maxBody, maxLine, maxWs, proxyProtocolOption, "http-kit", true,
                ContextLogger.ERROR_PRINTER, DEFAULT_WARN_LOGGER, EventLogger.NOP, EventNames.DEFAULT);
    }

    public HttpServer(String ip, int port, IHandler handler, int maxBody, int maxLine, int maxWs,
                      ProxyProtocolOption proxyProtocolOption,
                      String serverHeader,
                      boolean legacyUnsafeRemoteAddr,
                      ContextLogger<String, Throwable> errorLogger,
                      ContextLogger<String, Throwable> warnLogger,
                      EventLogger<String> eventLogger, EventNames eventNames)
            throws IOException {
        this.errorLogger = errorLogger;
        this.warnLogger = warnLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
        this.handler = handler;
        this.maxLine = maxLine;
        this.maxBody = maxBody;
        this.maxWs = maxWs;
        this.proxyProtocolOption = proxyProtocolOption == null
            ? ProxyProtocolOption.DISABLED : proxyProtocolOption;
        this.legacyUnsafeRemoteAddr = legacyUnsafeRemoteAddr;
        this.serverHeader = serverHeader;

        this.socketAddress = new InetSocketAddress(ip, port);

        Selector openedSelector = Selector.open();
        ServerSocketChannel openedChannel = null;
        try {
            openedChannel = ServerSocketChannel.open();
            openedChannel.configureBlocking(false);
            openedChannel.socket().bind(socketAddress);
            openedChannel.register(openedSelector, OP_ACCEPT);
        } catch (IOException | RuntimeException | Error e) {
            closeSetupResource(openedChannel);
            closeSetupResource(openedSelector);
            throw e;
        }
        this.selector = openedSelector;
        this.serverChannel = openedChannel;
    }


    public HttpServer (AddressFinder addressFinder, ServerChannelFactory channelFactory, IHandler handler, int maxBody, int maxLine, int maxWs,
        ProxyProtocolOption proxyProtocolOption,
        String serverHeader,
        boolean legacyUnsafeRemoteAddr,
        ContextLogger<String, Throwable> errorLogger,
        ContextLogger<String, Throwable> warnLogger,
        EventLogger<String> eventLogger, EventNames eventNames)
        throws IOException {
            this.errorLogger = errorLogger;
            this.warnLogger = warnLogger;
            this.eventLogger = eventLogger;
            this.eventNames = eventNames;
            this.handler = handler;
            this.maxLine = maxLine;
            this.maxBody = maxBody;
            this.maxWs = maxWs;
            this.proxyProtocolOption = proxyProtocolOption == null
                ? ProxyProtocolOption.DISABLED : proxyProtocolOption;
            this.legacyUnsafeRemoteAddr = legacyUnsafeRemoteAddr;
            this.serverHeader = serverHeader;
            this.socketAddress = addressFinder.findAddress();

            ServerSocketChannel openedChannel = channelFactory.createChannel(socketAddress);
            Selector openedSelector = null;
            try {
                openedChannel.configureBlocking(false);
                openedSelector = Selector.open();
                openedChannel.bind(socketAddress);
                openedChannel.register(openedSelector, OP_ACCEPT);
            } catch (IOException | RuntimeException | Error e) {
                closeSetupResource(openedChannel);
                closeSetupResource(openedSelector);
                throw e;
            }
            this.serverChannel = openedChannel;
            this.selector = openedSelector;
    }

    private static void closeSetupResource(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ignored) {
            }
        }
    }

    void accept(SelectionKey key) {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        try {
            while ((s = ch.accept()) != null) {
                try {
                    s.configureBlocking(false);
                    HttpAtta atta = new HttpAtta(maxBody, maxLine, proxyProtocolOption, legacyUnsafeRemoteAddr);
                    SelectionKey k = s.register(selector, OP_READ, atta);
                    atta.channel = new AsyncChannel(k, this);
                } catch (IOException | RuntimeException | Error e) {
                    closeSetupResource(s);
                    throw e;
                }
            }
        } catch (Exception e) {
            // eg: too many open files. do not quit
            Telemetry.log(errorLogger, "accept incoming request", e);
            Telemetry.log(eventLogger, eventNames.serverAcceptError);
        }
    }

    private void closeKey(final SelectionKey key, int status) {

        keptAlive.remove(key);

        try {
            key.channel().close();
        } catch (Exception ex) {
            Telemetry.log(warnLogger, "failed to close key", ex);
        }

        ServerAtta att = (ServerAtta) key.attachment();
        if (att instanceof HttpAtta) {
            handler.clientClose(att.channel, -1);
        } else if (att != null) {
            WsAtta wsAtta = (WsAtta) att;
            handler.clientClose(att.channel,
                    wsAtta.closeStatus == 0 ? status : wsAtta.closeStatus,
                    wsAtta.closeReason);
        }
    }

    private void savePendingInput(ServerAtta atta, ByteBuffer input) {
        if (input.hasRemaining()) {
            if (atta.pendingInput == null) {
                atta.pendingInput = ByteBuffer.allocate(
                    Math.min(buffer.capacity(), Math.max(1024, input.remaining())));
            } else if (atta.pendingInput.remaining() < input.remaining()) {
                int capacity = Math.min(buffer.capacity(), Math.max(
                    atta.pendingInput.position() + input.remaining(), atta.pendingInput.capacity() * 2));
                ByteBuffer expanded = ByteBuffer.allocate(capacity);
                atta.pendingInput.flip();
                expanded.put(atta.pendingInput);
                atta.pendingInput = expanded;
            }
            atta.pendingInput.put(input);
        }
    }

    private boolean canReadWhileInProgress(ServerAtta atta) {
        return atta.pendingInput == null || atta.pendingInput.position() < buffer.capacity();
    }

    private void updateInterestOps(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }

        ServerAtta atta = (ServerAtta) key.attachment();
        boolean close = false;
        synchronized (atta) {
            int readOp = atta.requestInProgress && canReadWhileInProgress(atta) ? OP_READ : 0;
            if (!atta.toWrites.isEmpty()) {
                key.interestOps(OP_WRITE | readOp);
            } else if (atta.requestInProgress) {
                key.interestOps(readOp);
            } else if (atta.isKeepAlive()) {
                key.interestOps(OP_READ);
                keptAlive.put(key, true);
            } else {
                close = true;
            }
        }
        if (close) {
            closeKey(key, CLOSE_NORMAL);
        }
    }

    private void resumeAfterResponse(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }

        ServerAtta atta = (ServerAtta) key.attachment();
        if (!atta.requestInProgress) {
            return;
        }
        atta.requestInProgress = false;

        ByteBuffer pendingInput = atta.pendingInput;
        atta.pendingInput = null;
        if (pendingInput != null && atta.isKeepAlive()) {
            pendingInput.flip();
            SocketChannel ch = (SocketChannel) key.channel();
            if (atta instanceof HttpAtta) {
                decodeHttp((HttpAtta) atta, key, ch, pendingInput);
            } else {
                decodeWs((WsAtta) atta, key, pendingInput);
            }
        }
        updateInterestOps(key);
    }

    private void decodeHttp(HttpAtta atta, SelectionKey key, SocketChannel ch, ByteBuffer input) {
        try {
            HttpRequest request = atta.decoder.decode(input);

            if (request != null) {

                    // Get AsyncChannel to associate with this request.
                    // Logic has had subtle issues in the past.
                    //
                    // 1. If HttpAtta's channel is open, use that (pre-existing) channel.
                    //    [#578] Important to ensure that we preserve attached handlers, etc.
                    //
                    // 2. If HttpAtta's channel is closed, KEEP it closed and create a new channel.
                    //    [#375] Important to ensure that AsyncChannels (which may be held by users!)
                    //    stay closed once closed, and don't get accidentally reset+reused for
                    //    different logical requests.

                    // Is this reasonable?
                AsyncChannel channel = atta.channel.isClosed() ? new AsyncChannel(key, this) : atta.channel;
                atta.channel = channel;

                if (status.get() != Status.RUNNING) {
                    request.isKeepAlive = false;
                }

                request.setStartTime(System.nanoTime());
                channel.reset(request);

                ServerAtta activeAtta;
                if (request.isWebSocket) {
                    request.isKeepAlive = false;
                    activeAtta = new WsAtta(channel, maxWs);
                    key.attach(activeAtta);
                } else {
                    atta.keepalive = request.isKeepAlive;
                    activeAtta = atta;
                }
                request.channel = channel;
                // can't call socket() on anything else
                if (socketAddress instanceof InetSocketAddress){
                    request.remoteAddr = (InetSocketAddress) ch.socket().getRemoteSocketAddress();
                }

                activeAtta.requestInProgress = true;
                savePendingInput(activeAtta, input);
                atta.decoder.reset();
                updateInterestOps(key);
                handler.handle(request, new RespCallback(key, this));
            } else if (atta.decoder.requiresContinue()) {
                tryWrite(key, HttpEncode(100, new HeaderMap(), null, serverHeader));
                atta.decoder.setSentContinue();
            }
        } catch (HeadersTooLargeException e) {
            atta.keepalive = false;
            Telemetry.log(eventLogger, eventNames.serverStatusPrefix + 431);
            tryWriteHttpResponse(key, atta, 431, e.getMessage());
        } catch (ProtocolException e) {
            atta.keepalive = false;
            tryWriteHttpResponse(key, atta, 400, e.getMessage());
        } catch (RequestTooLargeException e) {
            atta.keepalive = false;
            Telemetry.log(eventLogger, eventNames.serverStatus413);
            tryWriteHttpResponse(key, atta, 413, e.getMessage());
        } catch (LineTooLargeException e) {
            atta.keepalive = false; // close after write
            Telemetry.log(eventLogger, eventNames.serverStatus414);
            tryWriteHttpResponse(key, atta, 414, e.getMessage());
        }
    }

    private void tryWriteHttpResponse(SelectionKey key, HttpAtta atta,
                                      int status, String message) {
        HeaderMap headers = new HeaderMap();
        headers.put("Connection", "Close");
        HttpRequest request = atta.decoder.request;
        tryWrite(key, HttpEncode(status, headers, message, serverHeader, true,
            request != null && request.method == HttpMethod.HEAD));
    }

    private void decodeWs(WsAtta atta, SelectionKey key, ByteBuffer input) {
        try {
            do {
                Frame frame = atta.decoder.decode(input);
                if (frame instanceof TextFrame || frame instanceof BinaryFrame) {
                    handler.handle(atta.channel, frame);
                    atta.decoder.reset();
                } else if (frame instanceof PingFrame) {
                    handler.handle(atta.channel, frame);
                    atta.decoder.reset();
                } else if (frame instanceof PongFrame) {
                    handler.handle(atta.channel, frame);
                    atta.decoder.reset();
                } else if (frame instanceof CloseFrame) {
                    // A snapshot
                    boolean closed = atta.channel.isClosed();
                    int status = ((CloseFrame) frame).getStatus();
                    String reason = ((CloseFrame) frame).getReason();
                    atta.closeStatus = status;
                    atta.closeReason = reason;
                    handler.clientClose(atta.channel, status, reason);
                    // close the TCP connection after sent
                    atta.keepalive = false;
                    atta.decoder.reset();

                    // Follow RFC6455 5.5.1
                    // Do not send CLOSE frame again if it has been sent.
                    if (!closed) {
                        tryWrite(key, WsEncode(WSDecoder.OPCODE_CLOSE, frame.data));
                    }
                    return;
                }
            } while (input.hasRemaining()); // consume all
        } catch (WebSocketException e) {
            failWebSocket(atta, key, e.getCloseStatus(), e);
        } catch (ProtocolException e) {
            failWebSocket(atta, key, 1002, e);
        }
    }

    private void failWebSocket(WsAtta atta, SelectionKey key, int status,
                               ProtocolException error) {
        Telemetry.log(warnLogger, null, error);
        Telemetry.log(eventLogger, eventNames.serverWsDecodeError);
        atta.keepalive = false;
        atta.closeStatus = status;
        atta.closeReason = error.getMessage();
        byte[] payload = ByteBuffer.allocate(2).putShort((short) status).array();
        tryWrite(key, WsEncode(WSDecoder.OPCODE_CLOSE, payload));
    }

    private void doRead(final SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            final ServerAtta atta = (ServerAtta) key.attachment();
            if (atta.requestInProgress && !canReadWhileInProgress(atta)) {
                updateInterestOps(key);
                return;
            }

            buffer.clear(); // clear for read
            if (atta.requestInProgress && atta.pendingInput != null) {
                buffer.limit(buffer.capacity() - atta.pendingInput.position());
            }
            int read = ch.read(buffer);
            if (read == -1) {
                // remote entity shut the socket down cleanly.
                closeKey(key, CLOSE_AWAY);
            } else if (read > 0) {
                buffer.flip(); // flip for read
                if (atta.requestInProgress) {
                    savePendingInput(atta, buffer);
                    updateInterestOps(key);
                } else if (atta instanceof HttpAtta) {
                    decodeHttp((HttpAtta) atta, key, ch, buffer);
                } else {
                    decodeWs((WsAtta) atta, key, buffer);
                }
            }
        } catch (IOException e) { // the remote forcibly closed the connection
            closeKey(key, CLOSE_AWAY);
        }
    }

    private void doWrite(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            // the sync is per socket (per client). virtually, no contention
            // 1. keep byte data order, 2. ensure visibility
            synchronized (atta) {
                LinkedList<ByteBuffer> toWrites = atta.toWrites;
                int size = toWrites.size();
                if (size == 1) {
                    ch.write(toWrites.get(0));
                    // TODO investigate why needed.
                    // ws request for write, but has no data?
                } else if (size > 0) {
                    ByteBuffer buffers[] = new ByteBuffer[size];
                    toWrites.toArray(buffers);
                    ch.write(buffers, 0, buffers.length);
                }
                Iterator<ByteBuffer> ite = toWrites.iterator();
                while (ite.hasNext()) {
                    if (!ite.next().hasRemaining()) {
                        ite.remove();
                    }
                }
                // all done
                if (toWrites.size() == 0) {
                    if (atta.isKeepAlive()) {
                        if (atta.requestInProgress) {
                            key.interestOps(canReadWhileInProgress(atta) ? OP_READ : 0);
                        } else {
                            key.interestOps(OP_READ);
                            keptAlive.put(key, true);
                        }
                    } else {
                        closeKey(key, CLOSE_NORMAL);
                    }
                }
            }
        } catch (IOException e) { // the remote forcibly closed the connection
            closeKey(key, CLOSE_AWAY);
        }
    }

    public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
        tryWrite(key, false, buffers);
    }

    public void tryWrite(final SelectionKey key, boolean chunkInprogress, ByteBuffer... buffers) {
        ServerAtta atta = (ServerAtta) key.attachment();
        synchronized (atta) {
            atta.chunkedResponseInprogress(chunkInprogress);
            if (atta.toWrites.isEmpty()) {
                SocketChannel ch = (SocketChannel) key.channel();
                try {
                    // TCP buffer most of time is empty, writable(8K ~ 256k)
                    // One IO thread => One thread reading + Many thread writing
                    // Save 2 system call
                    ch.write(buffers, 0, buffers.length);
                    if (buffers[buffers.length - 1].hasRemaining()) {
                        for (ByteBuffer b : buffers) {
                            if (b.hasRemaining()) {
                                atta.toWrites.add(b);
                            }
                        }
                        pending.add(new PendingKey(key, PendingKey.OP_WRITE));
                        selector.wakeup();
                    } else if (!atta.isKeepAlive()) {
                        pending.add(new PendingKey(key, CLOSE_NORMAL));
                        selector.wakeup();
                    }
                } catch (IOException e) {
                    pending.add(new PendingKey(key, CLOSE_AWAY));
                    selector.wakeup();
                }
            } else {
                // If has pending write, order should be maintained. (WebSocket)
                Collections.addAll(atta.toWrites, buffers);
                pending.add(new PendingKey(key, PendingKey.OP_WRITE));
                selector.wakeup();
            }
        }
    }

    void responseComplete(SelectionKey key) {
        pending.add(new PendingKey(key, PendingKey.RESPONSE_COMPLETE));
        selector.wakeup();
    }

    void closeAfterResponse(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        synchronized (atta) {
            atta.keepalive = false;
        }
    }

    void finishCloseDelimitedResponse(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        synchronized (atta) {
            atta.keepalive = false;
            atta.chunkedResponseInprogress(false);
            pending.add(new PendingKey(key,
                atta.toWrites.isEmpty() ? CLOSE_NORMAL : PendingKey.OP_WRITE));
            selector.wakeup();
        }
    }

    public void run() {
        while (true) {
            try {
                PendingKey k;
                while (!pending.isEmpty()) {
                    k = pending.poll();
                    if (k.Op == PendingKey.OP_WRITE) {
                        if (k.key.isValid()) {
                            updateInterestOps(k.key);
                        }
                    } else if (k.Op == PendingKey.RESPONSE_COMPLETE) {
                        resumeAfterResponse(k.key);
                    } else {
                        closeKey(k.key, k.Op);
                    }
                }
                if (selector.select() <= 0) {
                    continue;
                }
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    keptAlive.remove(key);

                    // TODO I do not know if this is needed
                    // if !valid, isAcceptable, isReadable.. will Exception
                    // run hours happily after commented, but not sure.
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        doRead(key);
                    } else if (key.isWritable()) {
                        doWrite(key);
                    }
                }
            } catch (ClosedSelectorException ignore) {
                return; // stopped
                // do not exits the while IO event loop. if exits, then will not process any IO event
                // jvm can catch any exception, including OOM
            } catch (Throwable e) {
                Telemetry.log(errorLogger,
                        "http server loop error, see stack trace for details", e);
                Telemetry.log(eventLogger, eventNames.serverLoopError);
                if (e instanceof Error) {
                    status.set(Status.STOPPED);
                    closeAndWarn(selector);
                    closeAndWarn(serverChannel);
                    return;
                }
            }
        }
    }

    public boolean start() throws IOException {
        if (!status.compareAndSet(Status.STOPPED, Status.RUNNING)) { return false; }
        serverThread = new Thread(this, THREAD_NAME);
        serverThread.start();
        return true;
    }

    public boolean stop(int timeout                         ) { return stop(timeout, null); }
    public boolean stop(int timeout, final Runnable callback) {

        if (!status.compareAndSet(Status.RUNNING, Status.STOPPING)) { return false; }

        // stop accepting new requests
        closeAndWarn(serverChannel);

        // Shutdown idle connections
        for (SelectionKey key : keptAlive.keySet()) {
            closeKey(key, 0);
        }

        // From this point, no new connections should be entering the system.
        // this.warnLogger.log("Idle connections closed", new Exception("dummy"));

        // Block at most `timeout` msecs waiting for reqs to finish,
        // otherwise attempt interrupt (non-blocking)
        handler.close(timeout);

        // close socket, notify on-close handlers
        if (selector.isOpen()) {
            //            Set<SelectionKey> keys = selector.keys();
            //            SelectionKey[] keys = t.toArray(new SelectionKey[t.size()]);
            boolean cmex = false;
            do {
                cmex = false;
                try{
                    for (SelectionKey k : selector.keys()) {
                        /**
                         * 1. t.toArray will fill null if given array is larger.
                         * 2. compute t.size(), then try to fill the array, if in the mean time, another
                         *    thread close one SelectionKey, will result a NPE
                         *
                         * https://github.com/http-kit/http-kit/issues/125
                         */
                        if (k != null)
                            closeKey(k, 0); // 0 => close by server
                    }
                } catch(java.util.ConcurrentModificationException ex) {
                    /**
                     * The iterator will throw a CMEx as soon as we close an open connection. Since there
                     * seems to be no other way to safely iterate over all keys we just catch the exception
                     * and try again until we manage to notify all open connections.
                     *
                     * https://github.com/http-kit/http-kit/issues/355
                     */
                    cmex = true;
                }
            } while(cmex);

            closeAndWarn(selector);
        }

        // Start daemon thread to run once serverThread actually completes.
        // This could take some time if handler.close() was struggling to
        // actually kill some tasks.
        Thread cbThread = new Thread(new Runnable() {
                public void run() {
                    try { serverThread.join(); } catch (InterruptedException e) { }
                    if (callback != null) {
                        try { callback.run(); } catch (Throwable t) { }
                    }
                    status.set(Status.STOPPED);
                }
            });

        cbThread.setDaemon(true);
        cbThread.start();

        return true;
    }

    public int getPort() {
         if (socketAddress instanceof InetSocketAddress){
           return this.serverChannel.socket().getLocalPort();
         }
         return -1;

    }

    public Status  getStatus() { return status.get();           }
    public boolean isAlive()   { return serverThread.isAlive(); }

    void closeAndWarn(Closeable closable) {
        try {
            closable.close();
        } catch (IOException ex) {
            Telemetry.log(warnLogger,
                    String.format("failed to close %s", closable.getClass().getName()), ex);
        }
    }

    /**
     * Joins the thread in which the server runs; this will block until the server is stopped.
     *
     * @throws InterruptedException
     */
    public void join() throws InterruptedException {
        serverThread.join();
    }
}
