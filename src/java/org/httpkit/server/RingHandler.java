package org.httpkit.server;

import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.server.ClojureRing.*;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;

import org.httpkit.HttpUtils;
import org.httpkit.PrefixThreadFactory;
import org.httpkit.ws.CloseFrame;
import org.httpkit.ws.TextFrame;
import org.httpkit.ws.WSFrame;

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

public class RingHandler implements IHandler {

    final ExecutorService execs;
    final IFn handler;

    public RingHandler(int thread, IFn handler, String prefix, int queueSize) {
        PrefixThreadFactory factory = new PrefixThreadFactory(prefix);
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(queueSize);
        execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
        this.handler = handler;
    }

    public void handle(final HttpRequest req, final ResponseCallback cb) {
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

    public void handle(final AsyncChannel channel, final WSFrame frame) {
        try {
            execs.submit(new Runnable() {
                public void run() {
                    try {
                        if (frame instanceof TextFrame) {
                            channel.messageReceived(((TextFrame) frame).getText());
                        } else if (frame instanceof CloseFrame) {
                            channel.clientClosed(((CloseFrame) frame).getStatus());
                        }
                    } catch (Throwable e) {
                        HttpUtils.printError("handle websocket frame " + frame, e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // TODO notify client if server is overloaded
            HttpUtils.printError("increase :queue-size if this happens often", e);
        }
    }
}
