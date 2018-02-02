package org.httpkit.server;

import static clojure.lang.Keyword.intern;
import static org.httpkit.HttpUtils.HttpEncode;
import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.server.ClojureRing.BODY;
import static org.httpkit.server.ClojureRing.HEADERS;
import static org.httpkit.server.ClojureRing.buildRequestMap;
import static org.httpkit.server.ClojureRing.getStatus;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.httpkit.HeaderMap;
import org.httpkit.PrefixThreadFactory;
import org.httpkit.logger.ContextLogger;
import org.httpkit.logger.EventNames;
import org.httpkit.logger.EventLogger;
import org.httpkit.server.Frame.TextFrame;
import org.httpkit.server.Frame.BinaryFrame;
import org.httpkit.server.Frame.PingFrame;

import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

@SuppressWarnings({"rawtypes", "unchecked"})
class ClojureRing {

    static final Keyword SERVER_PORT = intern("server-port");
    static final Keyword SERVER_NAME = intern("server-name");
    static final Keyword REMOTE_ADDR = intern("remote-addr");
    static final Keyword URI = intern("uri");
    static final Keyword QUERY_STRING = intern("query-string");
    static final Keyword SCHEME = intern("scheme");
    static final Keyword REQUEST_METHOD = intern("request-method");
    static final Keyword HEADERS = intern("headers");
    static final Keyword CONTENT_TYPE = intern("content-type");
    static final Keyword CONTENT_LENGTH = intern("content-length");
    static final Keyword CHARACTER_ENCODING = intern("character-encoding");
    static final Keyword BODY = intern("body");
    static final Keyword WEBSOCKET = intern("websocket?");
    static final Keyword ASYC_CHANNEL = intern("async-channel");

    static final Keyword HTTP = intern("http");

    static final Keyword STATUS = intern("status");

    public static int getStatus(Map<Keyword, Object> resp) {
        int status = 200;
        Object s = resp.get(STATUS);
        if (s instanceof Long) {
            status = ((Long) s).intValue();
        } else if (s instanceof Integer) {
            status = (Integer) s;
        }
        return status;
    }

    public static IPersistentMap buildRequestMap(HttpRequest req) {
        // ring spec
        Map<Object, Object> m = new TreeMap<Object, Object>();
        m.put(SERVER_PORT, req.serverPort);
        m.put(SERVER_NAME, req.serverName);
        m.put(REMOTE_ADDR, req.getRemoteAddr());
        m.put(URI, req.uri);
        m.put(QUERY_STRING, req.queryString);
        m.put(SCHEME, HTTP); // only http is supported
        m.put(ASYC_CHANNEL, req.channel);
        m.put(WEBSOCKET, req.isWebSocket);
        m.put(REQUEST_METHOD, req.method.KEY);

        // key is already lower cased, required by ring spec
        m.put(HEADERS, PersistentArrayMap.create(req.headers));
        m.put(CONTENT_TYPE, req.contentType);
        m.put(CONTENT_LENGTH, req.contentLength);
        m.put(CHARACTER_ENCODING, req.charset);
        m.put(BODY, req.getBody());
        return PersistentArrayMap.create(m);
    }
}


@SuppressWarnings({"rawtypes", "unchecked"})
class HttpHandler implements Runnable {

    final HttpRequest req;
    final RespCallback cb;
    final IFn handler;

    final ContextLogger<String, Throwable> errorLogger;
    final EventLogger<String> eventLogger;
    final EventNames eventNames;

    public HttpHandler(HttpRequest req, RespCallback cb, IFn handler,
            ContextLogger<String, Throwable> errorLogger, EventLogger<String> eventLogger, EventNames eventNames) {
        this.req = req;
        this.cb = cb;
        this.handler = handler;
        this.errorLogger = errorLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
    }

    public void run() {
        try {
            Map resp = (Map) handler.invoke(buildRequestMap(req));
            if (resp == null) { // handler return null
                cb.run(HttpEncode(404, new HeaderMap(), null));
                eventLogger.log(eventNames.serverStatus404);
            } else {
                Object body = resp.get(BODY);
                if (!(body instanceof AsyncChannel)) { // hijacked
                    HeaderMap headers = HeaderMap.camelCase((Map) resp.get(HEADERS));
                    if (req.version == HTTP_1_0 && req.isKeepAlive) {
                        headers.put("Connection", "Keep-Alive");
                    }
                    final int status = getStatus(resp);
                    cb.run(HttpEncode(status, headers, body));
                    eventLogger.log(eventNames.serverStatusPrefix + status);
                }
            }
        } catch (Throwable e) {
            cb.run(HttpEncode(500, new HeaderMap(), e.getMessage()));
            errorLogger.log(req.method + " " + req.uri, e);
            eventLogger.log(eventNames.serverStatus500);
        }
    }
}

class LinkingRunnable implements Runnable {
    private final Runnable impl;
    AtomicReference<LinkingRunnable> next = new AtomicReference<LinkingRunnable>(null);

    public LinkingRunnable(Runnable r) {
        this.impl = r;
    }

    public void run() {
        impl.run();

        // Run all jobs in this chain without consuming extra call stack
        LinkingRunnable r = this;
        while (!r.next.compareAndSet(null, r)) {
            r = r.next.get();
            r.impl.run();
        }
    }
}

class WSHandler implements Runnable {
    private Frame frame;
    private AsyncChannel channel;

    private final ContextLogger<String, Throwable> errorLogger;
    private final EventLogger<String> eventLogger;
    private final EventNames eventNames;

    protected WSHandler(AsyncChannel channel, Frame frame,
            ContextLogger<String, Throwable> errorLogger,
            EventLogger<String> eventLogger, EventNames eventNames) {
        this.channel = channel;
        this.frame = frame;
        this.errorLogger = errorLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
    }

    @Override
    public void run() {
        try {
            if (frame instanceof TextFrame) {
                channel.messageReceived(((TextFrame) frame).getText());
            } else if (frame instanceof BinaryFrame) {
                channel.messageReceived(frame.data);
            } else if (frame instanceof PingFrame) {
                channel.pingReceived(frame.data);
            } else {
                errorLogger.log("Unknown frame received in websocket handler " + frame, null);
            }
        } catch (Throwable e) {
            errorLogger.log("handle websocket frame " + frame, e);
            eventLogger.log(eventNames.serverWsFrameError);
        }
    }
}

public class RingHandler implements IHandler {
    final ExecutorService execs;
    final IFn handler;

    final ContextLogger<String, Throwable> errorLogger;
    final EventLogger<String> eventLogger;
    final EventNames eventNames;

    public RingHandler(IFn handler, ExecutorService execs) {
        this(handler, execs, ContextLogger.ERROR_PRINTER, EventLogger.NOP, EventNames.DEFAULT);
    }

    public RingHandler(int thread, IFn handler, String prefix, int queueSize) {
        this(thread, handler, prefix, queueSize, ContextLogger.ERROR_PRINTER, EventLogger.NOP, EventNames.DEFAULT);
    }

    public RingHandler(int thread, IFn handler, String prefix, int queueSize,
            ContextLogger<String, Throwable> errorLogger, EventLogger<String> eventLogger, EventNames eventNames) {
        this.errorLogger = errorLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
        PrefixThreadFactory factory = new PrefixThreadFactory(prefix);
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(queueSize);
        execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
        this.handler = handler;
    }

    public RingHandler(IFn handler, ExecutorService execs,
            ContextLogger<String, Throwable> errorLogger, EventLogger<String> eventLogger, EventNames eventNames) {
        this.handler = handler;
        this.execs = execs;
        this.errorLogger = errorLogger;
        this.eventLogger = eventLogger;
        this.eventNames = eventNames;
    }

    public void handle(HttpRequest req, RespCallback cb) {
        try {
            execs.submit(new HttpHandler(req, cb, handler, errorLogger, eventLogger, eventNames));
        } catch (RejectedExecutionException e) {
            errorLogger.log("increase :queue-size if this happens often", e);
            eventLogger.log(eventNames.serverStatus503);
            cb.run(HttpEncode(503, new HeaderMap(), "Server is overloaded, please try later"));
        }
    }

    public void close(int timeoutTs) {
        if (timeoutTs > 0) {
            execs.shutdown();
            try {
                if (!execs.awaitTermination(timeoutTs, TimeUnit.MILLISECONDS)) {
                    execs.shutdownNow();
                }
            } catch (InterruptedException ie) {
                execs.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } else {
            execs.shutdownNow();
        }
    }

    public void handle(AsyncChannel channel, Frame frame) {
        WSHandler task = new WSHandler(channel, frame, errorLogger, eventLogger, eventNames);

        // messages from the same client are handled orderly
        LinkingRunnable job = new LinkingRunnable(task);
        LinkingRunnable old = channel.serialTask;
        channel.serialTask = job;
        try {
            if (old == null) { // No previous job
                execs.submit(job);
            } else {
                if (!old.next.compareAndSet(null, job)) { // successfully append to previous task
                    // previous message is handled, order is guaranteed.
                    execs.submit(job);
                }
            }
        } catch (RejectedExecutionException e) {
            // TODO notify client if server is overloaded
            errorLogger.log("increase :queue-size if this happens often", e);
            eventLogger.log(eventNames.serverStatus503Todo);
        }
    }

    public void clientClose(final AsyncChannel channel, final int status) {
        if (channel.closedRan == 0) { // server did not close it first
            // has close handler, execute it in another thread
            if (channel.closeHandler != null) {
                try {
                    // no need to maintain order
                    execs.submit(new Runnable() {
                        public void run() {
                            try {
                                channel.onClose(status);
                            } catch (Exception e) {
                                errorLogger.log("on close handler", e);
                                eventLogger.log(eventNames.serverChannelCloseError);
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    /*
                    https://github.com/http-kit/http-kit/issues/152
                    https://github.com/http-kit/http-kit/pull/155

                    When stop-server get called, the thread-pool will call shutdown, wait for sometime
                    for work to be finished.

                    For websocket and long polling with closeHandler registered, we exec closeHandler
                    in the current thread. Get this idea from @pyr, by #155
                     */
                    if (execs.isShutdown()) {
                        try {
                            channel.onClose(status);  // do it in current thread
                        } catch (Exception e1) {
                            errorLogger.log("on close handler", e);
                            eventLogger.log(eventNames.serverChannelCloseError);
                        }
                    } else {
                        errorLogger.log("increase :queue-size if this happens often", e);
                        eventLogger.log(eventNames.serverStatus503Todo);
                    }
                }
            } else {
                // no close handler, mark the connection as closed
                // channel.closedRan = 1;
                // lazySet
                AsyncChannel.unsafe.putOrderedInt(channel, AsyncChannel.closedRanOffset, 1);
            }
        }
    }
}
