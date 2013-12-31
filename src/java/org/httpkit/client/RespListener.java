package org.httpkit.client;

import org.httpkit.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.httpkit.HttpUtils.CONTENT_ENCODING;
import static org.httpkit.HttpUtils.CONTENT_TYPE;

class Handler implements Runnable {

    private final int status;
    private final Map<String, Object> headers;
    private final Object body;
    private final IResponseHandler handler;

    public Handler(IResponseHandler handler, int status, Map<String, Object> headers,
                   Object body) {
        this.handler = handler;
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public Handler(IResponseHandler handler, Throwable e) {
        this(handler, 0, null, e);
    }

    public void run() {
        try {
            if (body instanceof Throwable) {
                handler.onThrowable((Throwable) body);
            } else {
                handler.onSuccess(status, headers, body);
            }
        } catch (Exception e) { // onSuccess may throw Exception
            handler.onThrowable(e); // should not throw exception
        }
    }
}

/**
 * Accumulate all the response, call upper logic at once, for easy use
 */
public class RespListener implements IRespListener {

    private boolean isText() {
        if (status.getCode() == 200) {
            String type = HttpUtils.getStringValue(headers, CONTENT_TYPE);
            if (type != null) {
                type = type.toLowerCase();
                // TODO may miss something
                return type.contains("text") || type.contains("json") || type.contains("xml");
            } else {
                return false;
            }
        } else {  // non 200: treat as text
            return true;
        }
    }

    private DynamicBytes unzipBody() throws IOException {
        String encoding = HttpUtils.getStringValue(headers, CONTENT_ENCODING);
        if (encoding == null || body.length() == 0) {
            return body;
        }

        encoding = encoding.toLowerCase();
        BytesInputStream bis = new BytesInputStream(body.get(), body.length());
        InputStream is;

        if ("gzip".equals(encoding) || "x-gzip".equals(encoding)) {
            is = new GZIPInputStream(bis);
        } else if ("deflate".equals(encoding) || "x-deflate".equals(encoding)) {
            // http://stackoverflow.com/questions/3932117/handling-http-contentencoding-deflate
            is = new InflaterInputStream(bis, new Inflater(true));
        } else {
            return body; // not compressed
        }

        DynamicBytes unzipped = new DynamicBytes(body.length() * 5);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = is.read(buffer)) != -1) {
            unzipped.append(buffer, read);
        }
        is.close();
        return unzipped;
    }

    private final DynamicBytes body;

    // can be empty
    private Map<String, Object> headers = new TreeMap<String, Object>();
    private HttpStatus status;
    private final IResponseHandler handler;
    private final IFilter filter;
    private final ExecutorService pool;
    final int coercion;

    public RespListener(IResponseHandler handler, IFilter filter, ExecutorService pool, int coercion) {
        body = new DynamicBytes(1024 * 8);
        this.filter = filter;
        this.handler = handler;
        this.coercion = coercion;
        this.pool = pool;
    }

    public void onBodyReceived(byte[] buf, int length) throws AbortException {
        body.append(buf, length);
        if (filter != null && !filter.accept(body)) {
            throw new AbortException("Rejected when reading body, length: " + body.length());
        }
    }

    public void onCompleted() {
        if (status == null) {
            pool.submit(new Handler(handler, new ProtocolException("No status")));
            return;
        }
        try {
            DynamicBytes bytes = unzipBody();
            // 1=> auto, 2=>text, 3=>stream, 4=>byte-array
            if (coercion == 2 || (coercion == 1 && isText())) {
                Charset charset = HttpUtils.detectCharset(headers, bytes);
                String html = new String(bytes.get(), 0, bytes.length(), charset);
                pool.submit(new Handler(handler, status.getCode(), headers, html));
            } else {
                BytesInputStream is = new BytesInputStream(bytes.get(), bytes.length());
                if (coercion == 4) { // byte-array
                    pool.submit(new Handler(handler, status.getCode(), headers, is.bytes()));
                } else {
                    pool.submit(new Handler(handler, status.getCode(), headers, is));
                }
            }
        } catch (IOException e) {
            handler.onThrowable(e);
        }
    }

    public void onThrowable(Throwable t) {
        pool.submit(new Handler(handler, t));
    }

    public void onHeadersReceived(Map<String, Object> headers) throws AbortException {
        this.headers = headers;
        if (filter != null && !filter.accept(headers)) {
            throw new AbortException("Rejected when header received");
        }
    }

    public void onInitialLineReceived(HttpVersion version, HttpStatus status)
            throws AbortException {
        this.status = status;
    }
}
