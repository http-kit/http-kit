package me.shenfeng.http;

import static java.lang.Character.isWhitespace;
import static java.net.InetAddress.getByName;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.util.Date;
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

    public static final int MAX_LINE = 4096;

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

    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    public static final String CONTENT_LENGTH = "Content-Length";

    public static final String CACHE_CONTROL = "Cache-Control";

    // space ' '
    public static final byte SP = 32;

    public static DynamicBytes encodeResponseHeader(int status, Map<String, Object> headers) {
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
                bytes.append((String) v, HttpUtils.UTF_8);
                bytes.append(CR);
                bytes.append(LF);
                // ring spec says it could be a seq
            } else if (v instanceof Seqable) {
                ISeq seq = ((Seqable) v).seq();
                while (seq != null) {
                    bytes.append(k);
                    bytes.append(COLON);
                    bytes.append(SP);
                    bytes.append(seq.first().toString(), HttpUtils.UTF_8);
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

    public static int getChunkSize(String hex) throws ProtocolException {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (Exception e) {
            throw new ProtocolException("Expect chunk size to be a number: " + hex);
        }
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

    public static InetSocketAddress getServerAddr(URI uri) throws UnknownHostException {
        InetAddress host = getByName(uri.getHost());
        return new InetSocketAddress(host, getPort(uri));
    }

    public static ByteBuffer readAll(File f) throws IOException {
        int length = (int) f.length();
        if (length >= 1024 * 1024 * 2) { // 2M
            FileInputStream fs = new FileInputStream(f);
            MappedByteBuffer buffer = fs.getChannel().map(MapMode.READ_ONLY, 0, length);
            fs.close();
            return buffer;
        } else {
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
            return ByteBuffer.wrap(bytes);
        }
    }

    public static DynamicBytes readAll(InputStream is) throws IOException {
        DynamicBytes bytes = new DynamicBytes(32768); // init 16k
        byte[] buffer = new byte[16384];
        int read;
        while ((read = is.read(buffer)) != -1) {
            bytes.append(buffer, 0, read);
        }
        is.close();
        return bytes;
    }

    public static final void printError(String msg, Throwable t) {
        String error = String.format("%s [%s] ERROR - %s", new Date(), Thread.currentThread()
                .getName(), msg);
        StringWriter str = new StringWriter();
        PrintWriter pw = new PrintWriter(str, false);
        pw.println(error);
        t.printStackTrace(pw);
        System.err.print(str.getBuffer().toString());
    }

    public static void splitAndAddHeader(String sb, Map<String, String> headers) {
        final int length = sb.length();
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;

        nameStart = findNonWhitespace(sb, 0);
        for (nameEnd = nameStart; nameEnd < length; nameEnd++) {
            char ch = sb.charAt(nameEnd);
            if (ch == ':' || Character.isWhitespace(ch)) {
                break;
            }
        }

        for (colonEnd = nameEnd; colonEnd < length; colonEnd++) {
            if (sb.charAt(colonEnd) == ':') {
                colonEnd++;
                break;
            }
        }

        valueStart = findNonWhitespace(sb, colonEnd);
        valueEnd = findEndOfString(sb);

        String key = sb.substring(nameStart, nameEnd);
        if (valueStart > valueEnd) { // ignore
            // logger.warn("header error: " + sb);
        } else {
            String value = sb.substring(valueStart, valueEnd);
            headers.put(key, value);
        }
    }
}
