package org.httpkit.server;

import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.server.ClojureRing.*;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.httpkit.HttpUtils;
import org.httpkit.PrefixThreadFactory;
import org.httpkit.ws.BinaryFrame;
import org.httpkit.ws.TextFrame;

import clojure.lang.IFn;
import org.httpkit.ws.WSFrame;

@SuppressWarnings({ "rawtypes", "unchecked" })
class HttpHandler implements Runnable {

    final HttpRequest req;
    final ResponseCallback cb;
    final IFn handler;

    public HttpHandler(HttpRequest req, ResponseCallback cb, IFn handler) {
        this.req = req;
        this.cb = cb;
        this.handler = handler;
    }

    public void run() {
        try {
            Map resp = (Map) handler.invoke(buildRequestMap(req));
            if (resp == null) { // handler return null
                cb.run(encode(404, null, null));
            } else {
                Object body = resp.get(BODY);
                if (!(body instanceof AsyncChannel)) { // hijacked
                    boolean addKeepalive = req.version == HTTP_1_0 && req.isKeepAlive;
                    cb.run(encode(getStatus(resp), getHeaders(resp, addKeepalive), body));
                }
            }
        } catch (Throwable e) {
            cb.run(encode(500, new TreeMap<String, Object>(), e.getMessage()));
            HttpUtils.printError(req.method + " " + req.uri, e);
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
        if (!next.compareAndSet(null, this)) { // has more job to run
            next.get().run();
        }
    }
}

class WSFrameHandler implements Runnable {
    private WSFrame frame;
    private AsyncChannel channel;

    protected WSFrameHandler(AsyncChannel channel, WSFrame frame) {
        this.channel = channel;
        this.frame = frame;
    }

    @Override
    public void run() {
        try {
            if(frame instanceof TextFrame) {
                channel.messageReceived(((TextFrame) frame).getText());
            } else {
                channel.messageReceived(frame.data);
            }
        } catch (Throwable e) {
            HttpUtils.printError("handle websocket frame " + frame, e);
        }
    }
}

public class RingHandler implements IHandler {
    final ExecutorService execs;
    final IFn handler;

    public RingHandler(int thread, IFn handler, String prefix, int queueSize) {
        PrefixThreadFactory factory = new PrefixThreadFactory(prefix);
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(queueSize);
        execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
        this.handler = handler;
    }

    public void handle(HttpRequest req, ResponseCallback cb) {
        try {
            execs.submit(new HttpHandler(req, cb, handler));
        } catch (RejectedExecutionException e) {
            HttpUtils.printError("increase :queue-size if this happens often", e);
            cb.run(encode(503, null, "Server is overloaded, please try later"));
        }
    }

    public void close() {
        execs.shutdownNow();
    }

    public void handle(AsyncChannel channel, WSFrame frame) {
        WSFrameHandler task = new WSFrameHandler(channel, frame);

        // messages from the same client are handled orderly
        LinkingRunnable job = new LinkingRunnable(task);
        LinkingRunnable old = channel.serialTask;
        channel.serialTask = job;
        try {
            if (old == null) { // No previous job
                execs.submit(job);
            } else {
                if (old.next.compareAndSet(null, job)) {
                    // successfully append to previous task
                } else {
                    // previous message is handled, order is guaranteed.
                    execs.submit(job);
                }
            }
        } catch (RejectedExecutionException e) {
            // TODO notify client if server is overloaded
            HttpUtils.printError("increase :queue-size if this happens often", e);
        }
    }

    public void clientClose(final AsyncChannel channel, final int status) {
        if (!channel.closedRan.get()) { // server did not close it first
            // has close handler, execute it in another thread
            if (channel.closeHandler.get() != null) {
                try {
                    // no need to maintain order
                    execs.submit(new Runnable() {
                        public void run() {
                            try {
                                channel.onClose(status);
                            } catch (Exception e) {
                                HttpUtils.printError("on close handler", e);
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    HttpUtils.printError("increase :queue-size if this happens often", e);
                }
            } else {
                // no close handler, just mark the connection as closed
                channel.closedRan.set(false);
            }
        }
    }
}
