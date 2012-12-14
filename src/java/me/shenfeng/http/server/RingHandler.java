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

public class RingHandler implements IHandler {

    final ExecutorService execs;
    final IFn handler;

    public RingHandler(int thread, IFn handler, String prefix) {
        PrefixThreafFactory factory = new PrefixThreafFactory(prefix);
        // max pending request: 386
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(386);
        // TODO RejectedExecutionHandler
        execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
        this.handler = handler;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void handle(final HttpRequest req, final ResponseCallback cb) {
        execs.submit(new Runnable() {
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
                        cb.run(encode(404, null, null));
                    }
                } catch (Throwable e) {
                    cb.run(encode(500, null, e.getMessage()));
                    HttpUtils.printError("ring handler: " + e.getMessage(), e);
                }
            }

            private Map<String, Object> getHeaders(final Map resp) {
                Map<String, Object> headers = (Map) resp.get(HEADERS);
                if (headers != null && req.version == HttpVersion.HTTP_1_0 && req.isKeepAlive()
                        && !headers.containsKey("Connection")) {
                    // copy to modify
                    headers = new TreeMap<String, Object>(headers);
                    // ab -k, or Nginx reverse proxy need it
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
        });
    }

    public void close() {
        execs.shutdownNow();
    }

    public void handle(final WsCon con, final WSFrame frame) {
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
