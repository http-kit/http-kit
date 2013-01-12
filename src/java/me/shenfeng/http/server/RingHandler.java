package me.shenfeng.http.server;

import static me.shenfeng.http.server.ClojureRing.BODY;
import static me.shenfeng.http.server.ClojureRing.HEADERS;
import static me.shenfeng.http.server.ClojureRing.buildRequestMap;
import static me.shenfeng.http.server.ClojureRing.encode;
import static me.shenfeng.http.server.ClojureRing.getStatus;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.PrefixThreafFactory;
import me.shenfeng.http.ws.CloseFrame;
import me.shenfeng.http.ws.TextFrame;
import me.shenfeng.http.ws.WSFrame;
import me.shenfeng.http.ws.WsCon;
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

    private Map<String, Object> getHeaders(final Map resp) {
        Map<String, Object> headers = (Map) resp.get(HEADERS);
        // copy to modify
        if (headers == null) {
            headers = new TreeMap<String, Object>();
        } else {
            headers = new TreeMap<String, Object>(headers);
        }
        if (req.version == HttpVersion.HTTP_1_0 && req.isKeepAlive()
                && !headers.containsKey("Connection")) {
            headers.put("Connection", "Keep-Alive");
        }
        return headers;
    }

    private void asyncHandle(final ResponseCallback cb, final Map resp,
            final IListenableFuture future) {
        future.addListener(new Runnable() {
            public void run() {
                Object r = future.get();
                if (r instanceof Map) {
                    Map resp2 = (Map) r;
                    cb.run(encode(getStatus(resp2), getHeaders(resp2), resp2.get(BODY)));
                } else {
                    cb.run(encode(getStatus(resp), getHeaders(resp), r));
                }
            }
        });
    }

    public void run() {
        try {
            Map resp = (Map) handler.invoke(buildRequestMap(req));
            if (resp != null) {
                Object body = resp.get(BODY);
                if (body instanceof IListenableFuture) {
                    asyncHandle(cb, resp, (IListenableFuture) body);
                } else {
                    cb.run(encode(getStatus(resp), getHeaders(resp), body));
                }
            } else {
                // when handler return null: 404
                cb.run(encode(404, new TreeMap<String, Object>(), null));
            }
        } catch (Throwable e) {
            cb.run(encode(500, new TreeMap<String, Object>(), e.getMessage()));
            HttpUtils.printError("ring handler: " + e.getMessage(), e);
        }
    }
}

public class RingHandler implements IHandler {

    final ExecutorService execs;
    final IFn handler;
    final int queueSize;

    public RingHandler(int thread, IFn handler, String prefix, int queueSize) {
        this.queueSize = queueSize;
        PrefixThreafFactory factory = new PrefixThreafFactory(prefix);
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(queueSize);
        execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
        this.handler = handler;
    }

    public void handle(final HttpRequest req, final ResponseCallback cb) {
        try {
            execs.submit(new HttpHandler(req, cb, handler));
        } catch (RejectedExecutionException e) {
            HttpUtils.printError("queue size exceeds the limit " + queueSize
                    + ", please increase :queue-size when run-server if this happens often", e);
            cb.run(encode(503, new TreeMap<String, Object>(),
                    "server is overloaded, please try later"));
        }
    }

    public void close() {
        execs.shutdownNow();
    }

    public void handle(final WsCon con, final WSFrame frame) {
        // TODO notify client if server is overloaded
        execs.submit(new Runnable() {
            public void run() {
                try {
                    if (frame instanceof TextFrame) {
                        con.messageRecieved(((TextFrame) frame).getText());
                    } else if (frame instanceof CloseFrame) {
                        con.clientClosed(((CloseFrame) frame).getStatus());
                    }
                } catch (Throwable e) {
                    HttpUtils.printError("handle websocket frame " + frame, e);
                }
            }
        });
    }
}
