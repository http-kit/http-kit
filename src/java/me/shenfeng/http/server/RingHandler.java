package me.shenfeng.http.server;

import static me.shenfeng.http.server.ClojureRing.BODY;
import static me.shenfeng.http.server.ClojureRing.HEADERS;
import static me.shenfeng.http.server.ClojureRing.buildRequestMap;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import me.shenfeng.http.PrefixThreafFactory;
import clojure.lang.IFn;

public class RingHandler implements IHandler {

    final ExecutorService execs;
    final IFn f;

    public RingHandler(int thread, IFn f) {
        PrefixThreafFactory factory = new PrefixThreafFactory("worker-");
        // max pending request: 386
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(386);
        execs = new ThreadPoolExecutor(thread, thread, 0,
                TimeUnit.MILLISECONDS, queue, factory);
        this.f = f;
    }

    public void handle(final HttpRequest req, final IResponseCallback cb) {
        execs.submit(new Runnable() {
            @SuppressWarnings({ "rawtypes", "unchecked" })
            public void run() {
                try {
                    Map resp = (Map) f.invoke(buildRequestMap(req));
                    if (resp != null) {
                        Map headers = (Map) resp.get(HEADERS);
                        Object body = resp.get(BODY);
                        cb.run(ClojureRing.getStatus(resp), headers, body);
                    } else {
                        // when handler return null: 404
                        cb.run(404, null, null);
                    }
                } catch (Throwable e) {
                    cb.run(500, null, e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    public void close() {
        execs.shutdownNow();
    }
}
