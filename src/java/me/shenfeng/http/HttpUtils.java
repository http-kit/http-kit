package me.shenfeng.http;

import static java.lang.Character.isWhitespace;
import static java.net.InetAddress.getByName;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import clojure.lang.ISeq;
import clojure.lang.Seqable;

public class HttpUtils {

    public static final Charset ASCII = Charset.forName("US-ASCII");
    public static final Charset UTF_8 = Charset.forName("utf8");

    public static final String CHARSET = "charset=";
    // Colon ':'
    public static final byte COLON = 58;

    public static final byte CR = 13;

    public static final byte LF = 10;

    public static final int MAX_LINE = 2048;

    public static final int BUFFER_SIZE = 1024 * 64;

    public static final int SELECT_TIMEOUT = 3000;

    // public static final int ABORT_PROCESSING = -1;

    public static final int TIMEOUT_CHECK_INTEVAL = 3000;

    public static final String USER_AGENT = "User-Agent";

    public static final String ACCEPT = "Accept";

    public static final String ETAG = "ETag";

    public static final String ACCEPT_ENCODING = "Accept-Encoding";

    public static final String TRANSFER_ENCODING = "Transfer-Encoding";

    public static final String CONTENT_ENCODING = "Content-Encoding";

    public static final String CONTENT_TYPE = "Content-Type";

    public static final String CHUNKED = "chunked";

    public static final String HOST = "Host";

    public static final String CONNECTION = "Connection";

    public static final String LOCATION = "Location";

    public static final String IF_MODIFIED_SINCE = "If-Modified-Since";

    public static final String IF_NONE_MATCH = "If-None-Match";

    public static final String LAST_MODIFIED = "Last-Modified";

    public static final String CONTENT_LENGTH = "Content-Length";

    public static final String CACHE_CONTROL = "Cache-Control";

    // space ' '
    public static final byte SP = 32;

    public static void closeQuiety(SelectableChannel c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignore) {
        }
    }

    public static ByteBuffer encodeGetRequest(String path,
            Map<String, String> headers) {
        DynamicBytes bytes = new DynamicBytes(64 + headers.size() * 48);

        bytes.append("GET").append(SP).append(path).append(SP);
        bytes.append("HTTP/1.1").append(CR).append(LF);
        Iterator<Entry<String, String>> ite = headers.entrySet().iterator();
        while (ite.hasNext()) {
            Entry<String, String> e = ite.next();
            if (e.getValue() != null) {
                bytes.append(e.getKey()).append(COLON).append(SP)
                        .append(e.getValue());
                bytes.append(CR).append(LF);
            }
        }

        bytes.append(CR).append(LF);
        ByteBuffer request = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        return request;
    }

    public static DynamicBytes encodeResponseHeader(int status,
            Map<String, Object> headers) {
        DynamicBytes bytes = new DynamicBytes(196);
        byte[] bs = HttpStatus.valueOf(status).getResponseIntialLineBytes();
        bytes.append(bs, 0, bs.length);
        Iterator<Entry<String, Object>> ite = headers.entrySet().iterator();
        while (ite.hasNext()) {
            Entry<String, Object> e = ite.next();
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof String) {
                bytes.append(k);
                bytes.append(COLON);
                bytes.append(SP);
                bytes.append((String) v);
                bytes.append(CR);
                bytes.append(LF);
                // ring spec says it could be a seq
            } else if (v instanceof Seqable) {
                ISeq seq = ((Seqable) v).seq();
                while (seq != null) {
                    bytes.append(k);
                    bytes.append(COLON);
                    bytes.append(SP);
                    bytes.append(seq.first().toString());
                    bytes.append(CR);
                    bytes.append(LF);
                    seq = seq.next();
                }
            }
        }

        bytes.append(CR);
        bytes.append(LF);
        return bytes;
    }

    public static int findEndOfString(String sb) {
        int result;
        for (result = sb.length(); result > 0; result--) {
            if (!isWhitespace(sb.charAt(result - 1))) {
                break;
            }
        }
        return result;
    }

    public static int findNonWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result++) {
            if (!isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    public static int findWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result++) {
            if (isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    public static int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c)
                    || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

    public static String getPath(URI uri) {
        String path = uri.getPath();
        String query = uri.getRawQuery();
        if ("".equals(path))
            path = "/";
        if (query == null)
            return path;
        else
            return path + "?" + query;
    }

    public static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            if ("https".equals(uri.getScheme()))
                port = 443;
            else
                port = 80;
        }
        return port;
    }

    public static InetSocketAddress getServerAddr(URI uri)
            throws UnknownHostException {
        InetAddress host = getByName(uri.getHost());
        return new InetSocketAddress(host, getPort(uri));

    }

    public static byte[] readAll(File f, int length) throws IOException {
        byte[] bytes = new byte[length];
        FileInputStream fs = null;
        try {
            fs = new FileInputStream(f);
            int offset = 0;
            while (offset < length) {
                offset += fs.read(bytes, offset, length - offset);
            }
        } finally {
            if (fs != null) {
                try {
                    fs.close();
                } catch (Exception ignore) {
                }
            }
        }
        return bytes;
    }

    public static DynamicBytes readAll(InputStream is) throws IOException {
        DynamicBytes bytes = new DynamicBytes(1024);
        byte[] buffer = new byte[4096];
        int read = 0;
        while ((read = is.read(buffer)) != -1) {
            bytes.append(buffer, 0, read);
        }
        is.close();
        return bytes;
    }
}
