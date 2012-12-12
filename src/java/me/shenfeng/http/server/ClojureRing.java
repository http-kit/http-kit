package me.shenfeng.http.server;

import static clojure.lang.Keyword.intern;
import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.UTF_8;
import static me.shenfeng.http.HttpUtils.encodeResponseHeader;
import static me.shenfeng.http.HttpUtils.readAll;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpUtils;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.Seqable;

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
    public static final Keyword WEBSOCKET = intern("websocket");

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

    public static ByteBuffer[] encode(int status, Map<String, Object> headers, Object body) {
        if (headers != null) {
            // copy to modify
            headers = new TreeMap<String, Object>(headers);
        } else {
            headers = new TreeMap<String, Object>();
        }
        ByteBuffer bodyBuffer = null, headBuffer = null;
        try {
            if (body == null) {
                headers.put(HttpUtils.CONTENT_LENGTH, "0");
            } else if (body instanceof String) {
                byte[] b = ((String) body).getBytes(UTF_8);
                bodyBuffer = ByteBuffer.wrap(b);
                headers.put(HttpUtils.CONTENT_LENGTH, Integer.toString(b.length));
            } else if (body instanceof InputStream) {
                DynamicBytes b = readAll((InputStream) body);
                bodyBuffer = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(HttpUtils.CONTENT_LENGTH, Integer.toString(b.length()));
            } else if (body instanceof File) {
                File f = (File) body;
                // serving file is better be done by nginx
                byte[] b = readAll(f);
                bodyBuffer = ByteBuffer.wrap(b);
            } else if (body instanceof Seqable) {
                ISeq seq = ((Seqable) body).seq();
                DynamicBytes b = new DynamicBytes(seq.count() * 512);
                while (seq != null) {
                    b.append(seq.first().toString(), UTF_8);
                    seq = seq.next();
                }
                bodyBuffer = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(HttpUtils.CONTENT_LENGTH, Integer.toString(b.length()));
            } else {
                throw new RuntimeException(body.getClass() + " is not understandable");
            }
        } catch (IOException e) {
            byte[] b = e.getMessage().getBytes(ASCII);
            status = 500;
            headers.clear();
            headers.put(HttpUtils.CONTENT_LENGTH, Integer.toString(b.length));
            bodyBuffer = ByteBuffer.wrap(b);
        }
        DynamicBytes bytes = encodeResponseHeader(status, headers);
        headBuffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());

        return new ByteBuffer[] { headBuffer, bodyBuffer };
    }

    public static IPersistentMap buildRequestMap(HttpRequest req) {

        Map<Object, Object> m = new TreeMap<Object, Object>();
        m.put(SERVER_PORT, req.getServerPort());
        m.put(SERVER_NAME, req.getServerName());
        m.put(REMOTE_ADDR, req.getRemoteAddr());
        m.put(URI, req.uri);
        m.put(QUERY_STRING, req.queryString);
        m.put(SCHEME, HTTP); // only http is supported
        if (req.isWs()) {
            m.put(WEBSOCKET, req.getWebSocketCon());
        }

        switch (req.method) {
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
