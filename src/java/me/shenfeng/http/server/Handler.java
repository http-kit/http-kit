package me.shenfeng.http.server;

import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import me.shenfeng.http.PrefixThreafFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.*;

import static me.shenfeng.http.server.ServerConstant.*;

public class Handler implements IHandler {

    final ExecutorService execs;
    final IFn f;

    static Logger logger = LoggerFactory.getLogger(Handler.class);

    public Handler(int thread, IFn f) {
        execs = new ThreadPoolExecutor(thread, thread, 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(386),
                new PrefixThreafFactory("worker-"));
        this.f = f;
    }

    public static IPersistentMap buildRequestMap(HttpRequest req) {

        Map<Object, Object> m = new TreeMap<Object, Object>();
        m.put(SERVER_PORT, req.getServerPort());
        m.put(SERVER_NAME, req.getServerName());
        m.put(REMOTE_ADDR, req.getRemoteAddr());
        m.put(URI, req.getUri());
        m.put(QUERY_STRING, req.getQueryString());
        m.put(SCHEME, HTTP); // only http is supported

        switch (req.getMethod()) {
            case DELETE:
                m.put(REQUEST_METHOD, M_DELETE);
                break;
            case GET:
                m.put(REQUEST_METHOD, M_GET);
                break;
            case POST:
                m.put(REQUEST_METHOD, M_POST);
                break;
            case PUT:
                m.put(REQUEST_METHOD, M_PUT);
                break;
        }

        // downcase key, required by ring spec
        Set<Entry<String, String>> sets = req.getHeaders().entrySet();
        Map<String, String> downCased = new TreeMap<String, String>();
        for (Entry<String, String> e : sets) {
            downCased.put(e.getKey().toLowerCase(), e.getValue());
        }

        m.put(HEADERS, PersistentArrayMap.create(downCased));
        m.put(CONTENT_TYPE, req.getContentType());
        m.put(CONTENT_LENGTH, req.getContentLength());
        m.put(CHARACTER_ENCODING, req.getCharactorEncoding());
        m.put(BODY, req.getBody());
        // m.put(KEEP_ALIVE, req.isKeepAlive()); it handled by server,
        return PersistentArrayMap.create(m);
    }

    public void handle(final HttpRequest req, final IResponseCallback cb) {
        execs.submit(new Runnable() {
            @SuppressWarnings({"rawtypes", "unchecked"})
            public void run() {
                try {
                    Map resp = (Map) f.invoke(buildRequestMap(req));
                    if (resp != null) {
                        int status = ((Long) resp.get(STATUS)).intValue();
                        Map headers = (Map) resp.get(HEADERS);
                        Object body = resp.get(BODY);
                        cb.run(status, headers, body);
                    } else {
                        // when handler return null: 404
                        cb.run(404, null, null);
                    }
                } catch (Throwable e) {
                    cb.run(500, null, e.getMessage());
                    logger.error(req.getUri(), e);
                }
            }
        });
    }

    public void close() {
        execs.shutdownNow();
    }
}
