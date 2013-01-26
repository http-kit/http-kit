package org.httpkit.client;

import static java.lang.Math.min;
import static org.httpkit.HttpUtils.ASCII;
import static org.httpkit.HttpUtils.CHARSET;
import static org.httpkit.HttpUtils.CONTENT_ENCODING;
import static org.httpkit.HttpUtils.CONTENT_TYPE;
import static org.httpkit.HttpUtils.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;

import org.httpkit.BytesInputStream;
import org.httpkit.DynamicBytes;
import org.httpkit.HttpStatus;
import org.httpkit.HttpUtils;
import org.httpkit.HttpVersion;
import org.httpkit.ProtocolException;


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
    // <?xml version='1.0' encoding='GBK'?>
    // <?xml version="1.0" encoding="UTF-8"?>
    static final Pattern ENCODING = Pattern.compile("encoding=('|\")([\\w|-]+)('|\")",
            Pattern.CASE_INSENSITIVE);

    public static Charset parseCharset(String type) {
        if (type != null) {
            try {
                type = type.toLowerCase();
                int i = type.indexOf(CHARSET);
                if (i != -1) {
                    String charset = type.substring(i + CHARSET.length()).trim();
                    return Charset.forName(charset);
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static Charset guess(String html, String patten) {
        int idx = html.indexOf(patten);
        if (idx != -1) {
            int start = idx + patten.length();
            int end = html.indexOf('"', start);
            if (end != -1) {
                try {
                    return Charset.forName(html.substring(start, end));
                } catch (Exception ignore) {
                }
            }
        }
        return null;
    }

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
        if (encoding != null && body.length() > 0) {
            encoding = encoding.toLowerCase();
            ByteArrayInputStream bis = new ByteArrayInputStream(body.get(), 0, body.length());
            DynamicBytes unzipped = new DynamicBytes(body.length() * 6);
            boolean zipped = ("gzip".equals(encoding) || "x-gzip".equals(encoding));
            if (zipped) {
                headers.remove(CONTENT_ENCODING);
            }
            // deflate || x-deflate
            InputStream is = zipped ? new GZIPInputStream(bis) : new DeflaterInputStream(bis);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                unzipped.append(buffer, 0, read);
            }
            return unzipped;
        }
        return body;

    }

    public static Charset detectCharset(Map<String, String> headers, DynamicBytes body) {
        Charset result = parseCharset(headers.get(CONTENT_TYPE));
        if (result == null) {
            // decode a little the find charset=???
            String s = new String(body.get(), 0, min(512, body.length()), ASCII);
            // content="text/html;charset=gb2312"
            result = guess(s, CHARSET);
            if (result == null) {
                Matcher matcher = ENCODING.matcher(s);
                if (matcher.find()) {
                    try {
                        result = Charset.forName(matcher.group(2));
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return result == null ? UTF_8 : result;
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
            // if blocking request in callback, will deadlock
            pool.submit(new Handler(handler, new ProtocolException("No status")));
            return;
        }
        try {
            DynamicBytes bytes = unzipBody();
            if (isText()) {
                Charset charset = detectCharset(headers, body);
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
