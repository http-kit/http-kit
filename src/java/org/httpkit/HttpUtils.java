package org.httpkit;

import static java.lang.Character.isWhitespace;
import static java.lang.Math.min;
import static java.net.InetAddress.getByName;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clojure.lang.ISeq;
import clojure.lang.Seqable;

public class HttpUtils {

    public static final Charset ASCII = Charset.forName("US-ASCII");
    public static final Charset UTF_8 = Charset.forName("utf8");

    public static final String CHARSET = "charset=";
    // Colon ':'
    public static final byte COLON = 58;

    public static final byte CR = 13; // \r

    public static final byte LF = 10; // \n

    public static final int MAX_LINE = 4096;

    public static final int BUFFER_SIZE = 1024 * 64;

    public static final int SELECT_TIMEOUT = 3000;

    // public static final int ABORT_PROCESSING = -1;

    public static final int TIMEOUT_CHECK_INTEVAL = 3000;

    // public static final String USER_AGENT = "user-agent";

    // public static final String ACCEPT = "Accept";

    // public static final String ETAG = "ETag";

    // public static final String ACCEPT_ENCODING = "accept-encoding";

    public static final String TRANSFER_ENCODING = "transfer-encoding";

    public static final String CONTENT_ENCODING = "content-encoding";

    public static final String CONTENT_TYPE = "content-type";

    public static final String CHUNKED = "chunked";

    public static final String CONNECTION = "connection";

    // public static final String LOCATION = "location";

    // public static final String IF_MODIFIED_SINCE = "If-Modified-Since";

    // public static final String IF_NONE_MATCH = "If-None-Match";

    // public static final String LAST_MODIFIED = "Last-Modified";

    public static final String X_FORWARDED_FOR = "x-forwarded-for";

    public static final String CONTENT_LENGTH = "content-length";

    // public static final String CACHE_CONTROL = "Cache-Control";

    // space ' '
    public static final byte SP = 32;

    public static void encodeHeaders(DynamicBytes bytes, Map<String, Object> headers) {
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
    }

    public static ByteBuffer bodyBuffer(Object body) throws IOException {
        if (body == null) {
            return null;
        } else if (body instanceof String) {
            byte[] b = ((String) body).getBytes(UTF_8);
            return ByteBuffer.wrap(b);
        } else if (body instanceof InputStream) {
            DynamicBytes b = readAll((InputStream) body);
            return ByteBuffer.wrap(b.get(), 0, b.length());
        } else if (body instanceof File) {
            // serving file is better be done by Nginx
            return readAll((File) body);
        } else if (body instanceof Seqable) {
            ISeq seq = ((Seqable) body).seq();
            DynamicBytes b = new DynamicBytes(seq.count() * 512);
            while (seq != null) {
                b.append(seq.first().toString(), UTF_8);
                seq = seq.next();
            }
            return ByteBuffer.wrap(b.get(), 0, b.length());
        } else {
            throw new RuntimeException(body.getClass() + " is not understandable");
        }
    }

    private static final byte[] ALPHAS = "0123456789ABCDEF".getBytes();

    // like javascript's encodeURI
    // https://developer.mozilla.org/en-US/docs/JavaScript/Reference/Global_Objects/encodeURI
    public static String encodeURI(String url) {
        byte[] bytes = url.getBytes(UTF_8);
        DynamicBytes buffer = new DynamicBytes(bytes.length * 2);
        boolean e = true;
        for (byte b : bytes) {
            int c = b < 0 ? b + 256 : b;
            if (c < '!' || c > '~') {
                e = true;
            } else {
                switch (c) {
                case '"':
                case '%':
                case '<':
                case '>':
                case '\\':
                case '^':
                case '`':
                case '{':
                case '}':
                case '|':
                    e = true;
                    break;
                default:
                    e = false;
                }
            }
            if (e) {
                buffer.append((byte) '%');
                buffer.append(ALPHAS[c / 16]);
                buffer.append(ALPHAS[c % 16]);
            } else {
                buffer.append(b);
            }
        }
        return new String(buffer.get(), 0, buffer.length(), UTF_8);
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

    public static Map<String, Object> camelCase(Map<String, Object> headers) {
        TreeMap<String, Object> tmp = new TreeMap<String, Object>();

        if (headers != null) {
            for (Entry<String, Object> e : headers.entrySet()) {
                tmp.put(HttpUtils.camelCase(e.getKey()), e.getValue());
            }
        }
        return tmp;
    }

    public static String camelCase(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        boolean upper = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (upper) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
            upper = c == '-';
        }
        return sb.toString();
    }

    public static String getPath(URI uri) {
        String path = encodeURI(uri.getPath());
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

    public static String getHost(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();

        if (port != -1) {
            host += ":" + port;
        }
        return host;
    }

    public static InetSocketAddress getServerAddr(URI uri) throws UnknownHostException {
        InetAddress host = getByName(uri.getHost());
        return new InetSocketAddress(host, getPort(uri));
    }

    public static byte[] readContent(File f, int length) throws IOException {
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

    public static ByteBuffer readAll(File f) throws IOException {
        int length = (int) f.length();
        if (length >= 1024 * 1024 * 2) { // 2M
            FileInputStream fs = new FileInputStream(f);
            MappedByteBuffer buffer = fs.getChannel().map(MapMode.READ_ONLY, 0, length);
            fs.close();
            return buffer;
        } else {
            return ByteBuffer.wrap(readContent(f, length));
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
        if (valueStart > valueEnd) { // ignoreq
            // logger.warn("header error: " + sb);
        } else {
            String value = sb.substring(valueStart, valueEnd);
            headers.put(key.toLowerCase(), value);
        }
    }

    /*----------------charset--------------------*/

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

    // <?xml version='1.0' encoding='GBK'?>
    // <?xml version="1.0" encoding="UTF-8"?>
    static final Pattern ENCODING = Pattern.compile("encoding=('|\")([\\w|-]+)('|\")",
            Pattern.CASE_INSENSITIVE);

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

    // unit test in utils-test.clj
    public static Charset detectCharset(Map<String, String> headers, DynamicBytes body) {
        Charset result = parseCharset(headers.get(CONTENT_TYPE));
        if (result == null) {
            // decode a little the find charset=???
            String s = new String(body.get(), 0, min(512, body.length()), ASCII);
            // content="text/html;charset=gb2312"
            result = guess(s, CHARSET);
            if (result == null) {
                Matcher matcher = ENCODING.matcher(s); // for xml
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
}
