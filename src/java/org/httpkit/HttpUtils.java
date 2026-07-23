package org.httpkit;

import clojure.lang.ISeq;
import clojure.lang.PersistentList;
import clojure.lang.Seqable;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.time.*;
import java.time.format.DateTimeFormatter;

import static java.lang.Character.isWhitespace;
import static java.lang.Math.min;
import static java.net.InetAddress.getByName;

class DateFormatter {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);
    private static volatile CachedDate cached = new CachedDate(Long.MIN_VALUE, "");

    private static class CachedDate {
        final long epochSecond;
        final String value;

        CachedDate(long epochSecond, String value) {
            this.epochSecond = epochSecond;
            this.value = value;
        }
    }

    public static String getDate() {
        long epochSecond = Math.floorDiv(System.currentTimeMillis(), 1000L);
        CachedDate current = cached;
        if (current.epochSecond != epochSecond) {
            current = new CachedDate(epochSecond,
                    FORMATTER.format(Instant.ofEpochSecond(epochSecond)));
            cached = current;
        }
        return current.value;
    }
}

public class HttpUtils {

    // Files with size above this threshold should be memory mapped.
    private static final int MAPPED_BUFFER_THRESH_SIZE_BYTES = initMappedThreshold();
    private static int initMappedThreshold()
    {
        return 1024 * 1024
            * Integer.getInteger("org.http-kit.memmap-file-threshold", 20);
    }

    public static final Charset ASCII = Charset.forName("US-ASCII");
    public static final Charset UTF_8 = Charset.forName("utf8");

    public static final String CHARSET = "charset=";
    private static final Pattern CHARSET_PARAMETER = Pattern.compile(
            "(?:^|;)\\s*charset\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;\\s]*))",
            Pattern.CASE_INSENSITIVE);
    // Colon ':'
    public static final byte COLON = 58;

    public static final byte CR = 13; // \r

    public static final byte LF = 10; // \n

    // public static final int ABORT_PROCESSING = -1;

    // public static final String USER_AGENT = "user-agent";

    // public static final String ACCEPT = "Accept";

    // public static final String ETAG = "ETag";

    // public static final String ACCEPT_ENCODING = "accept-encoding";

    public static final String TRANSFER_ENCODING = "transfer-encoding";

    public static final String CONTENT_ENCODING = "content-encoding";
    public static final String CONTENT_TYPE = "content-type";
    public static final String CONTENT_LENGTH = "content-length";

    public static final Set<String> NON_TEXT_CONTENT_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "image/svg+xml",                                                             // .svg
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",   // .docx
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",   // .dotx
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",         // .xlsx
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",      // .xltx
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", // .pptx
            "application/vnd.openxmlformats-officedocument.presentationml.slide",        // .sldx
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",    // .ppsx
            "application/vnd.openxmlformats-officedocument.presentationml.template",     // .potx
            "application/vnd.oasis.opendocument.text",                                   // .odt
            "application/vnd.oasis.opendocument.text-template",                          // .ott
            "application/vnd.oasis.opendocument.text-web",                               // .oth
            "application/vnd.oasis.opendocument.text-master"                             // .odm
    )));

    public static final String CHUNKED = "chunked";
    public static final String TRAILER = "trailer";

    public static final String CONNECTION = "connection";

    // public static final String LOCATION = "location";

    // public static final String IF_MODIFIED_SINCE = "If-Modified-Since";

    // public static final String IF_NONE_MATCH = "If-None-Match";

    // public static final String LAST_MODIFIED = "Last-Modified";

    public static final String X_FORWARDED_FOR = "x-forwarded-for";

    // public static final String CACHE_CONTROL = "Cache-Control";

    // space ' '
    public static final byte SP = 32;

    public static final String EXPECT = "expect";

    public static final String CONTINUE = "100-continue";


    public static ByteBuffer bodyBuffer(Object body) throws IOException {
        if (body == null) {
            return null;
        } else if (body instanceof String) {
            byte[] b = ((String) body).getBytes(UTF_8);
            return ByteBuffer.wrap(b);
        } else if (body instanceof InputStream) {
            DynamicBytes b = readAll((InputStream) body);
            return ByteBuffer.wrap(b.get(), 0, b.length());
        } else if (body instanceof byte[]) {
            return ByteBuffer.wrap((byte[]) body);
        } else if (body instanceof File) {
            // serving file is better be done by Nginx
            return readAll((File) body);
        } else if (body instanceof Seqable) {
            ISeq seq = ((Seqable) body).seq();
            if (seq == null) {
                return null;
            } else {
                DynamicBytes b = new DynamicBytes(seq.count() * 512);
                while (seq != null) {
                    b.append(seq.first().toString(), UTF_8);
                    seq = seq.next();
                }
                return ByteBuffer.wrap(b.get(), 0, b.length());
            }
        // makes ultimate optimization possible: no copy
        } else if (body instanceof ByteBuffer) {
            return (ByteBuffer) body;
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
                        // https://github.com/http-kit/http-kit/issues/70
//                    case '%':
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

    public static int findEndOfString(String sb, int offset) {
        int result;
        for (result = sb.length(); result > offset; result--) {
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
        int extension = hex.indexOf(';');
        String sizeToken = extension < 0 ? hex : hex.substring(0, extension);
        if (sizeToken.isEmpty()) {
            throw new ProtocolException("Expected a non-negative chunk size, got " + sizeToken);
        }
        for (int i = 0; i < sizeToken.length(); i++) {
            char c = sizeToken.charAt(i);
            if (!((c >= '0' && c <= '9') ||
                  (c >= 'a' && c <= 'f') ||
                  (c >= 'A' && c <= 'F'))) {
                throw new ProtocolException("Expected a non-negative chunk size, got " + sizeToken);
            }
        }
        try {
            return Integer.parseInt(sizeToken, 16);
        } catch (Exception e) {
            throw new ProtocolException("Expected a non-negative chunk size, got " + sizeToken);
        }
    }

    // content-type => Content-Type
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
        String path = encodeURI(uri.getRawPath());
        String query = uri.getRawQuery();
        if ("".equals(path))
            path = "/";
        if (query == null)
            return path;
        else
            return path + "?" + encodeURI(query);
    }

    public static String getProxyTarget(URI uri) {
        String target = uri.toASCIIString();
        int fragment = target.indexOf('#');
        return fragment < 0 ? target : target.substring(0, fragment);
    }

    public static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            if ("https".equalsIgnoreCase(uri.getScheme()))
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

    public static String getProxyHost(URI uri){
        if (uri.getPort() == -1){
            return uri.getHost();
        }

        return uri.getHost() + ":" + uri.getPort();
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
                int read = fs.read(bytes, offset, length - offset);
                if (read < 0) {
                    throw new EOFException("Unexpected end of file while reading " + f);
                }
                offset += read;
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
        if (length >= MAPPED_BUFFER_THRESH_SIZE_BYTES) {
            FileInputStream fs = new FileInputStream(f);
            MappedByteBuffer buffer = fs.getChannel().map(MapMode.READ_ONLY, 0, length);
            fs.close();
            return buffer;
        } else {
            return ByteBuffer.wrap(readContent(f, length));
        }
    }

    public static DynamicBytes readAll(InputStream is) throws IOException {
        try {
            DynamicBytes bytes = new DynamicBytes(32768); // init 32k
            byte[] buffer = new byte[16384];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bytes.append(buffer, read);
            }
            return bytes;
        } finally {
            is.close();
        }
    }

    public static String getStringValue(Map<String, Object> headers, String key) {
        Object o = headers.get(key);
        if (o instanceof String) {
            return (String) o;
        }
        return null;
    }

    public static void printError(String msg, Throwable t) {
        String error = String.format("%s [%s] ERROR - %s", new Date(), Thread.currentThread()
                .getName(), msg);
        StringWriter str = new StringWriter();
        PrintWriter pw = new PrintWriter(str, false);
        pw.println(error);
        t.printStackTrace(pw);
        System.err.print(str.getBuffer().toString());
    }

    public static void splitAndAddHeader(String line, Map<String, Object> headers)
            throws ProtocolException {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new ProtocolException("Malformed HTTP header: " + line);
        }

        String key = line.substring(0, colon);
        if (!isToken(key)) {
            throw new ProtocolException("Invalid HTTP header name: " + key);
        }

        int valueStart = colon + 1;
        int valueEnd = line.length();
        while (valueStart < valueEnd
                && (line.charAt(valueStart) == ' ' || line.charAt(valueStart) == '\t')) {
            valueStart++;
        }
        while (valueEnd > valueStart
                && (line.charAt(valueEnd - 1) == ' ' || line.charAt(valueEnd - 1) == '\t')) {
            valueEnd--;
        }

        String value = line.substring(valueStart, valueEnd);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || (c < 32 && c != '\t') || c == 127) {
                throw new ProtocolException("Invalid character in HTTP header value");
            }
        }

        key = key.toLowerCase(Locale.ROOT);
        Object previous = headers.get(key);
        if (previous != null) {
            value = previous.toString() + "\n" + value;
        }
        headers.put(key, value);
    }

    public static boolean isToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean token = c >= '0' && c <= '9'
                    || c >= 'A' && c <= 'Z'
                    || c >= 'a' && c <= 'z'
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasHeaderToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String part : value.split("[,\\n]")) {
            if (token.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isForbiddenTrailer(String name) {
        return "host".equals(name)
                || CONTENT_LENGTH.equals(name)
                || TRANSFER_ENCODING.equals(name)
                || CONNECTION.equals(name)
                || TRAILER.equals(name)
                || "upgrade".equals(name)
                || "authorization".equals(name)
                || "proxy-authorization".equals(name)
                || "cookie".equals(name)
                || EXPECT.equals(name);
    }

    /*----------------charset--------------------*/

    public static String parseCharsetName(String type) {
        if (type == null) {
            return null;
        }
        Matcher matcher = CHARSET_PARAMETER.matcher(type);
        if (!matcher.find()) {
            return null;
        }
        String quoted = matcher.group(1);
        if (quoted == null) {
            return matcher.group(2);
        }
        StringBuilder value = new StringBuilder(quoted.length());
        boolean escaped = false;
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (escaped) {
                value.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                value.append(c);
            }
        }
        if (escaped) {
            value.append('\\');
        }
        return value.toString();
    }

    public static Charset parseCharset(String type) {
        try {
            String charset = parseCharsetName(type);
            return charset == null ? null : Charset.forName(charset);
        } catch (Exception ignore) {
            return null;
        }
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
    public static Charset detectCharset(Map<String, Object> headers, DynamicBytes body) {
        // 1. first from http header: Content-Type: text/html; charset=utf8
        Charset result = parseCharset(getStringValue(headers, CONTENT_TYPE));
        if (result == null) {
            // 2. decode a little to find charset=???
            String s = new String(body.get(), 0, min(512, body.length()), ASCII);
            // content="text/html;charset=gb2312"
            result = guess(s, CHARSET);
            if (result == null) {
                // for xml
                Matcher matcher = ENCODING.matcher(s);
                if (matcher.find()) {
                    try {
                        result = Charset.forName(matcher.group(2));
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        // default utf8
        return result == null ? UTF_8 : result;
    }

    public static ByteBuffer[] HttpEncode(int status, HeaderMap headers, Object body) {
        return HttpEncode(status, headers, body, null);
    }

    public static ByteBuffer[] HttpEncode(int status, HeaderMap headers, Object body, String serverHeader) {
        return HttpEncode(status, headers, body, serverHeader, true);
    }

    public static ByteBuffer[] HttpEncode(int status, HeaderMap headers, Object body, String serverHeader, boolean legacyContentLength) {
        return HttpEncode(status, headers, body, serverHeader, legacyContentLength, false, false, false);
    }

    public static ByteBuffer[] HttpEncode(int status, HeaderMap headers, Object body, String serverHeader,
                                          boolean legacyContentLength, boolean headRequest) {
        return HttpEncode(status, headers, body, serverHeader, legacyContentLength, headRequest, false, false);
    }

    public static ByteBuffer[] HttpEncodeChunked(int status, HeaderMap headers, Object body, String serverHeader) {
        return HttpEncode(status, headers, body, serverHeader, true, false, false, true);
    }

    public static ByteBuffer[] HttpEncodeCloseDelimited(int status, HeaderMap headers, Object body, String serverHeader) {
        return HttpEncode(status, headers, body, serverHeader, true, false, true, false);
    }

    private static ByteBuffer[] HttpEncode(int status, HeaderMap headers, Object body, String serverHeader,
                                           boolean legacyContentLength, boolean headRequest,
                                           boolean closeDelimited, boolean chunked) {
        ByteBuffer bodyBuffer;
        try {
            boolean bodyForbidden = isBodyForbidden(status);
            String userContentLength = headers.getUserContentLength();
            bodyBuffer = bodyForbidden ? null : bodyBuffer(body);
            if (closeDelimited) {
                headers.remove(CONTENT_LENGTH);
                headers.remove("Transfer-Encoding");
            } else if (chunked) {
                headers.remove(CONTENT_LENGTH);
                headers.remove("Transfer-Encoding");
                headers.put("Transfer-Encoding", CHUNKED);
            } else {
                headers.remove("Transfer-Encoding");
            }
            if (status / 100 == 1 || status == 204) {
                headers.remove(CONTENT_LENGTH);
                headers.remove("Transfer-Encoding");
            } else if (status == 205) {
                headers.remove("Transfer-Encoding");
                headers.remove(CONTENT_LENGTH);
                headers.put(CONTENT_LENGTH, "0");
            }
            if (!bodyForbidden && !closeDelimited && !chunked) {
                int length = bodyBuffer == null ? 0 : bodyBuffer.remaining();
                String contentLength = !legacyContentLength && headRequest
                    && userContentLength != null ? userContentLength : Integer.toString(length);
                headers.remove(CONTENT_LENGTH);
                headers.put(CONTENT_LENGTH, contentLength);
            } else if (status == 304 && !closeDelimited && userContentLength != null) {
                headers.remove(CONTENT_LENGTH);
                headers.put(CONTENT_LENGTH, userContentLength);
            }
        } catch (IOException e) {
            byte[] b = e.getMessage().getBytes(ASCII);
            status = 500;
            headers.clear();
            if (chunked) {
                headers.put("Transfer-Encoding", CHUNKED);
            } else if (!closeDelimited) {
                headers.put(CONTENT_LENGTH, Integer.toString(b.length));
            }
            bodyBuffer = ByteBuffer.wrap(b);
        }
        if (serverHeader != null && !headers.containsKey("Server")) {
          headers.put("Server", serverHeader);
        }
        if (!headers.containsKey("Date")) {
          headers.put("Date", DateFormatter.getDate()); // rfc says the Date is needed
        }
        DynamicBytes bytes = new DynamicBytes(196);
        byte[] bs = HttpStatus.valueOf(status).getInitialLineBytes();
        bytes.append(bs, bs.length);
        headers.encodeHeaders(bytes);
        ByteBuffer headBuffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());

        if (bodyBuffer != null && !headRequest)
            return new ByteBuffer[]{headBuffer, bodyBuffer};
        else
            return new ByteBuffer[]{headBuffer};
    }

    public static boolean isBodyForbidden(int status) {
        return status / 100 == 1 || status == 204 || status == 205 || status == 304;
    }

    public static ByteBuffer WsEncode(byte opcode, byte[] data, int length) {
        if ((opcode & 0x08) != 0 && length > 125) {
            throw new IllegalArgumentException(
                    "Websocket control frame payload exceeds 125 bytes");
        }
        byte b0 = 0;
        b0 |= 1 << 7; // FIN
        b0 |= opcode;
        ByteBuffer buffer = ByteBuffer.allocate(length + 10); // max
        buffer.put(b0);

        if (length <= 125) {
            buffer.put((byte) (length));
        } else if (length <= 0xFFFF) {
            buffer.put((byte) 126);
            buffer.putShort((short) length);
        } else {
            buffer.put((byte) 127);
            buffer.putLong(length);
        }
        buffer.put(data, 0, length);
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer WsEncode(byte opcode, byte[] data) {
        return WsEncode(opcode, data, data.length);
    }
}
