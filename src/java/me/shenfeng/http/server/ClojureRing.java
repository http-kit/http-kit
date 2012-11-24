package me.shenfeng.http.server;

import static clojure.lang.Keyword.intern;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class ClojureRing {

    public static final Keyword SERVER_PORT = intern("server-port");
    public static final Keyword SERVER_NAME = intern("server-name");
    public static final Keyword REMOTE_ADDR = intern("remote-addr");
    public static final Keyword URI = intern("uri");
    public static final Keyword QUERY_STRING = intern("query-string");
    public static final Keyword SCHEME = intern("scheme");
    public static final Keyword REQUEST_METHOD = intern("request-method");
    public static final Keyword HEADERS = intern("headers");
    public static final Keyword CONTENT_TYPE = intern("content-type");
    public static final Keyword CONTENT_LENGTH = intern("content-length");
    public static final Keyword CHARACTER_ENCODING = intern("character-encoding");
    public static final Keyword BODY = intern("body");
    public static final Keyword KEEP_ALIVE = intern("keep_alive");

    public static final Keyword M_GET = intern("get");
    public static final Keyword M_POST = intern("post");
    public static final Keyword M_DELETE = intern("delete");
    public static final Keyword M_PUT = intern("put");

    public static final Keyword HTTP = intern("http");

    public static final Keyword STATUS = intern("status");

    public static final int getStatus(Map<Keyword, Object> resp) {
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
}
