package me.shenfeng.http.client;

import static java.lang.Math.min;
import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.CHARSET;
import static me.shenfeng.http.HttpUtils.CONTENT_ENCODING;
import static me.shenfeng.http.HttpUtils.CONTENT_TYPE;
import static me.shenfeng.http.HttpUtils.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;

import me.shenfeng.http.BytesInputStream;
import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.ProtocolException;

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

    public static interface IFilter {
        public final static IFilter ACCEPT_ALL = new IFilter() {
            public boolean accept(DynamicBytes partialBody) {
                return true;
            }

            public boolean accept(Map<String, String> headers) {
                return true;
            }

            public String toString() {
                return "Response Filter: ACCEPT all response";
            };
        };

        public boolean accept(Map<String, String> headers);

        public boolean accept(DynamicBytes partialBody);
    }

    private final DynamicBytes body;

    // can be empty
    private Map<String, String> headers = new TreeMap<String, String>();
    private HttpStatus status;
    private final IResponseHandler handler;
    private final IFilter filter;

    public RespListener(IResponseHandler handler, IFilter filter) {
        body = new DynamicBytes(1024 * 16);
        this.filter = filter;
        this.handler = handler;
    }

    public RespListener(IResponseHandler handler) {
        this(handler, IFilter.ACCEPT_ALL);
    }

    public void onBodyReceived(byte[] buf, int length) throws AbortException {
        if (filter != null && !filter.accept(body)) {
            throw new AbortException("Regected when reading body, length: " + body.length());
        }
        body.append(buf, 0, length);
    }

    public void onCompleted() {
        if (status == null) {
            handler.onThrowable(new ProtocolException("No status"));
            return;
        }
        try {
            DynamicBytes bytes = unzipBody();
            if (isText()) {
                Charset charset = detectCharset(headers, body);
                String html = new String(bytes.get(), 0, bytes.length(), charset);
                handler.onSuccess(status.getCode(), headers, html);
            } else {
                BytesInputStream is = new BytesInputStream(bytes.get(), 0, bytes.length());
                handler.onSuccess(status.getCode(), headers, is);
            }
        } catch (IOException e) {
            handler.onThrowable(e);
        }
    }

    public void onThrowable(Throwable t) {
        handler.onThrowable(t);
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
