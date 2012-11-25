package me.shenfeng.http.server;

import static me.shenfeng.http.server.ClojureRing.BODY;
import static me.shenfeng.http.server.ClojureRing.HEADERS;
import static me.shenfeng.http.server.ClojureRing.buildRequestMap;
import static me.shenfeng.http.server.ClojureRing.encode;
import static me.shenfeng.http.server.ClojureRing.getStatus;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import me.shenfeng.http.PrefixThreafFactory;
import me.shenfeng.http.ws.TextFrame;
import me.shenfeng.http.ws.WSFrame;
import clojure.lang.IFn;

public class RingHandler implements IHandler {

    final ExecutorService execs;
    final IFn handler;

    public RingHandler(int thread, IFn handler) {
        PrefixThreafFactory factory = new PrefixThreafFactory("worker-");
        // max pending request: 386
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(386);
        execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
        this.handler = handler;
    }

    public void handle(final HttpRequest req, final ResponseCallback cb) {
        execs.submit(new Runnable() {
            @SuppressWarnings({ "rawtypes", "unchecked" })
            public void run() {
                try {
                    Map resp = (Map) handler.invoke(buildRequestMap(req));
                    if (resp != null) {
                        final Map headers = (Map) resp.get(HEADERS);
                        Object body = resp.get(BODY);
                        final int status = getStatus(resp);
                        if (body instanceof IListenableFuture) {
                            final IListenableFuture future = (IListenableFuture) body;
                            future.addListener(new Runnable() {
                                public void run() {
                                    Object r = future.get();
                                    if (r instanceof Map) {
                                        Map resp2 = (Map) r;
                                        Map<String, Object> headers2 = (Map) resp2.get(HEADERS);
                                        int status2 = getStatus(resp2);
                                        Object body2 = resp2.get(BODY);
                                        cb.run(encode(status2, headers2, body2));
                                    } else {
                                        cb.run(encode(status, headers, r));
                                    }
                                }
                            });

                        } else {
                            cb.run(encode(status, headers, body));
                        }
                    } else {
                        // when handler return null: 404
                        cb.run(encode(404, null, null));
                    }
                } catch (Throwable e) {
                    cb.run(encode(500, null, e.getMessage()));
                    e.printStackTrace();
                }
            }
        });
    }

    public void close() {
        execs.shutdownNow();
    }

    public void handle(final WSFrame frame) {
        execs.submit(new Runnable() {
            public void run() {
                if (frame instanceof TextFrame) {
                    frame.wsCon.onText(((TextFrame) frame).getText());
                }
            }
        });
    }
}
