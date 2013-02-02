package org.httpkit.client;

import static org.httpkit.HttpUtils.CONTENT_ENCODING;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.httpkit.*;

class Handler implements Runnable {

    private final int status;
    private final Map<String, String> headers;
    private final Object body;
    private final Throwable e;
    private final IResponseHandler handler;

    public Handler(IResponseHandler handler, int status, Map<String, String> headers,
            Object body, Throwable e) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.e = e;
        this.handler = handler;
    }

    public Handler(IResponseHandler handler, Throwable e) {
        this(handler, 0, null, null, e);
    }

    public Handler(IResponseHandler handler, int status, Map<String, String> headers,
            Object body) {
        this(handler, status, headers, body, null);
    }

    public void run() {
        if (e != null) {
            handler.onThrowable(e);
        } else {
            handler.onSuccess(status, headers, body);
        }
    }
}

/**
 * Accumulate all the response, call upper logic at once, for easy use
 */
public class RespListener implements IRespListener {

    private boolean isText() {
        if (status == HttpStatus.OK) {
            String type = headers.get(HttpUtils.CONTENT_TYPE);
            if (type != null) {
                type = type.toLowerCase();
                // TODO may miss something
                return type.contains("text") || type.contains("json");
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    private DynamicBytes unzipBody() throws IOException {
        String encoding = headers.get(CONTENT_ENCODING);
        if (encoding == null || body.length() == 0) {
            return body;
        }

        encoding = encoding.toLowerCase();
        BytesInputStream bis = new BytesInputStream(body.get(), 0, body.length());
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
            unzipped.append(buffer, 0, read);
        }
        is.close();
        return unzipped;
    }

    private final DynamicBytes body;

    // can be empty
    private Map<String, String> headers = new TreeMap<String, String>();
    private HttpStatus status;
    private final IResponseHandler handler;
    private final IFilter filter;
    private final ExecutorService pool;

    public RespListener(IResponseHandler handler, IFilter filter, ExecutorService pool) {
        body = new DynamicBytes(1024 * 16);
        this.filter = filter;
        this.handler = handler;
        this.pool = pool;
    }

    public void onBodyReceived(byte[] buf, int length) throws AbortException {
        body.append(buf, 0, length);
        if (filter != null && !filter.accept(body)) {
            throw new AbortException("Regected when reading body, length: " + body.length());
        }
    }

    public void onCompleted() {
        if (status == null) {
            pool.submit(new Handler(handler, new ProtocolException("No status")));
            return;
        }
        try {
            DynamicBytes bytes = unzipBody();
            if (isText()) {
                Charset charset = HttpUtils.detectCharset(headers, bytes);
                String html = new String(bytes.get(), 0, bytes.length(), charset);
                pool.submit(new Handler(handler, status.getCode(), headers, html));
            } else {
                BytesInputStream is = new BytesInputStream(bytes.get(), 0, bytes.length());
                pool.submit(new Handler(handler, status.getCode(), headers, is));
            }
        } catch (IOException e) {
            handler.onThrowable(e);
        }
    }

    public void onThrowable(Throwable t) {
        pool.submit(new Handler(handler, t));
    }

    public void onHeadersReceived(Map<String, String> headers) throws AbortException {
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
