package me.shenfeng.http.client;

import static java.lang.Math.min;
import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.CONTENT_ENCODING;
import static me.shenfeng.http.HttpUtils.CONTENT_TYPE;
import static me.shenfeng.http.HttpUtils.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.codec.HttpStatus;
import me.shenfeng.http.codec.HttpVersion;

public class TextRespListener implements IRespListener {

    private static final String CS = "charset=";

    static Charset detectCharset(Map<String, String> headers,
            DynamicBytes body) {
        Charset result = parseCharset(headers.get(CONTENT_TYPE));
        if (result == null) {
            // decode a little the find charset=???
            String s = new String(body.get(), 0, min(350, body.length()),
                    ASCII);
            int idx = s.indexOf(CS);
            if (idx != -1) {
                int start = idx + CS.length();
                int end = s.indexOf('"', start);
                if (end != -1) {
                    try {
                        result = Charset.forName(s.substring(start, end));
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
                int i = type.indexOf(CS);
                if (i != -1) {
                    String charset = type.substring(i + CS.length()).trim();
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

    public TextRespListener(ITextHandler h) {
        body = new DynamicBytes(1024 * 16);
        this.handler = h;
    }

    public int onBodyReceived(byte[] buf, int length) {
        body.append(buf, 0, length);
        return CONTINUE;
    }

    public void onCompleted() {

        String encoding = headers.get(CONTENT_ENCODING);
        String html = "";
        try {
            if (encoding != null) {
                encoding = encoding.toLowerCase();
                ByteArrayInputStream bis = new ByteArrayInputStream(
                        body.get(), 0, body.length());
                System.out.println(body.get()[0] + "\t" + body.get()[1]
                        + "\t" + body.get()[2]);
                System.out.println(encoding + "\t" + headers);
                System.out.println(body.length() + " " + bis);
                DynamicBytes unzipped = new DynamicBytes(body.length() * 6);
                // deflate || x-deflate
                InputStream is = ("gzip".equals(encoding) || "x-gzip"
                        .equals(encoding)) ? new GZIPInputStream(bis)
                        : new DeflaterInputStream(bis);
                byte[] buffer = new byte[4096];
                int read = 0;
                while ((read = is.read(buffer)) != -1) {
                    unzipped.append(buffer, 0, read);
                }
                html = new String(unzipped.get(), 0, unzipped.length(),
                        detectCharset(headers, unzipped));
            } else {
                html = new String(body.get(), 0, body.length(),
                        detectCharset(headers, body));
            }
            handler.onSuccess(status.getCode(), headers, html);
        } catch (Exception e) {
            handler.onThrowable(e);
        }
    }

    public int onHeadersReceived(Map<String, String> headers) {
        this.headers = headers;
        return CONTINUE;
    }

    public int onInitialLineReceived(HttpVersion version, HttpStatus status) {
        this.status = status;
        return CONTINUE;
    }

    public void onThrowable(Throwable t) {
        handler.onThrowable(t);
    }
}
