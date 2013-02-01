package org.httpkit.server;

import static org.httpkit.server.ClojureRing.*;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;

import org.httpkit.HttpUtils;
import org.httpkit.HttpVersion;
import org.httpkit.PrefixThreafFactory;
import org.httpkit.ws.*;

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
        if (req.version == HttpVersion.HTTP_1_0 && req.isKeepAlive()) {
            headers.put("Connection", "Keep-Alive");
        }
        return headers;
    }

    private boolean isBody(Object o) {
        if (o instanceof Map && ((Map) o).containsKey(ClojureRing.STATUS)) {
            return false;
        }
        return true;
    }

    private void asyncHandle(final ResponseCallback cb, final IListenableFuture future) {
        future.addListener(new Runnable() {
            public void run() {
                Object r = future.get();
                if (isBody(r)) {
                    TreeMap<String, Object> h = new TreeMap<String, Object>();
                    // just :body, add some default value
                    h.put("Content-Type", "text/html; charset=utf8");
                    cb.run(encode(200, h, r));
                } else {
                    // standard ring response, :status :header :body
                    Map resp = (Map) r;
                    cb.run(encode(getStatus(resp), getHeaders(resp), resp.get(BODY)));
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
                    asyncHandle(cb, (IListenableFuture) body);
                } else {
                    cb.run(encode(getStatus(resp), getHeaders(resp), body));
                }
            } else {
                // when handler return null: 404
                cb.run(encode(404, null, null));
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
        PrefixThreafFactory factory = new PrefixThreafFactory(prefix);
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

    public void handle(final WsCon con, final WSFrame frame) {
        try {
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
        } catch (RejectedExecutionException e) {
            // TODO notify client if server is overloaded
            HttpUtils.printError("increase :queue-size if this happens often", e);
        }
    }
}
