package org.httpkit.server;

import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.server.ClojureRing.*;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.httpkit.HttpUtils;
import org.httpkit.PrefixThreadFactory;
import org.httpkit.ws.TextFrame;

import clojure.lang.IFn;

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

class TextFrameHandler implements Runnable {
    private TextFrame frame;
    private AsyncChannel channel;

    public TextFrameHandler(AsyncChannel channel, TextFrame frame) {
        this.channel = channel;
        this.frame = frame;
    }

    public void run() {
        try {
            channel.messageReceived(frame.getText());
        } catch (Throwable e) {
            HttpUtils.printError("handle websocket frame " + frame, e);
        }
    }

}

class CloseHandler implements Runnable {
    final AsyncChannel channel;
    final int status;

    public CloseHandler(AsyncChannel channel, int status) {
        this.channel = channel;
        this.status = status;
    }

    public void run() {
        try {
            channel.onClose(status);
        } catch (Exception e) {
            HttpUtils.printError("on close handler", e);
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

    public void handle(AsyncChannel channel, TextFrame frame) {
        TextFrameHandler task = new TextFrameHandler(channel, frame);
        serializePerChannel(channel, task);
    }

    public void clientClose(AsyncChannel channel, int status) {
        if (channel.closeHandler.get() != null && !channel.closedRan.get())
            serializePerChannel(channel, new CloseHandler(channel, status));
    }

    private void serializePerChannel(AsyncChannel channel, Runnable task) {
        LinkingRunnable job = new LinkingRunnable(task);
        LinkingRunnable old = channel.serialTask;
        try {
            if (old == null) { // No previous job
                channel.serialTask = job;
                execs.submit(job);
            } else {
                channel.serialTask = job; // keep the reference
                if (old.next.compareAndSet(null, job)) {
                    // successfully append to previous task
                } else {
                    // previous job finished. ordered executing is guaranteed.
                    execs.submit(job);
                }
            }
        } catch (RejectedExecutionException e) {
            // TODO notify client if server is overloaded
            HttpUtils.printError("increase :queue-size if this happens often", e);
        }
    }
}
