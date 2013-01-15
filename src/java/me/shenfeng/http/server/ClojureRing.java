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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import me.shenfeng.http.DynamicBytes;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.Seqable;

//  SimpleDateFormat is not threadsafe
class DateFormater extends ThreadLocal<SimpleDateFormat> {
    protected SimpleDateFormat initialValue() {
        // Formats into HTTP date format (RFC 822/1123).
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f;
    }

    private static final DateFormater FORMATER = new DateFormater();

    public static String getDate() {
        return FORMATER.get().format(new Date());
    }
}

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
    
    public static final String CL = "Content-Length";

    public static ByteBuffer[] encode(int status, Map<String, Object> headers, Object body) {
        // headers can be modified
        ByteBuffer bodyBuffer = null, headBuffer = null;
        headers.put("Server", "http-kit");
        headers.put("Date", DateFormater.getDate());
        try {
            if (body == null) {
                headers.put(CL, "0");
            } else if (body instanceof String) {
                byte[] b = ((String) body).getBytes(UTF_8);
                bodyBuffer = ByteBuffer.wrap(b);
                headers.put(CL, Integer.toString(b.length));
            } else if (body instanceof InputStream) {
                DynamicBytes b = readAll((InputStream) body);
                bodyBuffer = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(CL, Integer.toString(b.length()));
            } else if (body instanceof File) {
                // length header is set by upper logic
                // serving file is better be done by Nginx
                bodyBuffer = readAll((File) body);
            } else if (body instanceof Seqable) {
                ISeq seq = ((Seqable) body).seq();
                DynamicBytes b = new DynamicBytes(seq.count() * 512);
                while (seq != null) {
                    b.append(seq.first().toString(), UTF_8);
                    seq = seq.next();
                }
                bodyBuffer = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(CL, Integer.toString(b.length()));
            } else {
                throw new RuntimeException(body.getClass() + " is not understandable");
            }
        } catch (IOException e) {
            byte[] b = e.getMessage().getBytes(ASCII);
            status = 500;
            headers.clear();
            headers.put(CL, Integer.toString(b.length));
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

        // key is already downcased, required by ring spec
        m.put(HEADERS, PersistentArrayMap.create(req.getHeaders()));
        m.put(CONTENT_TYPE, req.getContentType());
        m.put(CONTENT_LENGTH, req.getContentLength());
        m.put(CHARACTER_ENCODING, req.getCharactorEncoding());
        m.put(BODY, req.getBody());
        return PersistentArrayMap.create(m);
    }
}
