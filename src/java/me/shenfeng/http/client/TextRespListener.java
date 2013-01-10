package me.shenfeng.http.client;

import static java.lang.Math.min;
import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.CHARSET;
import static me.shenfeng.http.HttpUtils.CONTENT_ENCODING;
import static me.shenfeng.http.HttpUtils.CONTENT_TYPE;
import static me.shenfeng.http.HttpUtils.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpVersion;

public class TextRespListener implements IRespListener {

    public static class AbortException extends Exception {
        public AbortException() {
            super("aborted");
        }

        private static final long serialVersionUID = 1L;

    }

    public static interface IFilter {
        public boolean accept(Map<String, String> headers);

        public boolean accept(DynamicBytes partialBody);
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

    // <?xml version='1.0' encoding='GBK'?>
    // <?xml version="1.0" encoding="UTF-8"?>
    static final Pattern ENCODING = Pattern.compile("encoding=('|\")([\\w|-]+)('|\")",
            Pattern.CASE_INSENSITIVE);

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

    private DynamicBytes body;
    private Map<String, String> headers;
    private HttpStatus status;
    private ITextHandler handler;
    private IFilter filter;

    public TextRespListener(ITextHandler h) {
        this(h, null);
    }

    public TextRespListener(ITextHandler h, IFilter filter) {
        body = new DynamicBytes(1024 * 16);
        this.filter = filter;
        this.handler = h;
    }

    public State onBodyReceived(byte[] buf, int length) {
        body.append(buf, 0, length);
        if (filter != null && !filter.accept(body)) {
            return State.ABORT;
        }
        return State.CONTINUE;
    }

    public void onCompleted() {
        String encoding = headers.get(CONTENT_ENCODING);
        String html;
        try {
            if (encoding != null) {
                encoding = encoding.toLowerCase();
                ByteArrayInputStream bis = new ByteArrayInputStream(body.get(), 0,
                        body.length());
                DynamicBytes unzipped = new DynamicBytes(body.length() * 6);
                // deflate || x-deflate
                InputStream is = ("gzip".equals(encoding) || "x-gzip".equals(encoding)) ? new GZIPInputStream(
                        bis) : new DeflaterInputStream(bis);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    unzipped.append(buffer, 0, read);
                }
                html = new String(unzipped.get(), 0, unzipped.length(), detectCharset(headers,
                        unzipped));
            } else {
                html = new String(body.get(), 0, body.length(), detectCharset(headers, body));
            }
            handler.onSuccess(status.getCode(), headers, html);
        } catch (Exception e) {
            handler.onThrowable(e);
        }
    }

    public State onHeadersReceived(Map<String, String> headers) {
        this.headers = headers;
        if (filter != null && !filter.accept(headers)) {
            return State.ABORT;
        }
        return State.CONTINUE;
    }

    public State onInitialLineReceived(HttpVersion version, HttpStatus status) {
        this.status = status;
        return State.CONTINUE;
    }

    public void onThrowable(Throwable t) {
        handler.onThrowable(t);
    }
}
