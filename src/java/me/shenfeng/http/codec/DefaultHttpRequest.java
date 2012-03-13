package me.shenfeng.http.codec;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class DefaultHttpRequest implements IHttpRequest {
    static final byte CR = 13;
    static final byte LF = 10;
    static final String CONTENT_LENGTH = "content-length";
    static final int MAX_LINE = 2048;

    static int findEndOfString(String sb) {
        int result;
        for (result = sb.length(); result > 0; result--) {
            if (!Character.isWhitespace(sb.charAt(result - 1))) {
                break;
            }
        }
        return result;
    }

    static int findNonWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result++) {
            if (!Character.isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    static int findWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result++) {
            if (Character.isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    static String readLine(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder(64);
        char b;
        for (;;) {
            b = (char) buffer.get();
            if (b == CR) {
                if (buffer.get() == LF)
                    return sb.toString();
            } else if (b == LF) {
                return sb.toString();
            } else {
                sb.append(b);
                if (sb.length() >= MAX_LINE) {
                    return sb.toString();
                }
            }
        }
    }

    void splitAndAddHeader(String sb) {
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

        // TODO make sure does not break anything
        // lowercase for easier processing
        String key = sb.substring(nameStart, nameEnd).toLowerCase();
        String value = sb.substring(valueStart, valueEnd);
        headers.put(key, value);
    }

    private HttpMethod method;
    private State state = State.READ_INITIAL;
    private String url;
    private HttpVersion version;
    private HashMap<String, String> headers = new HashMap<String, String>(6);
    private int contentLength = 0;
    private int contentRemain = 0;
    private byte[] content;

    public void resetState() {
        state = State.READ_INITIAL;
    }

    public byte[] getContent() {
        return content;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Set<String> getHeaderNames() {
        return headers.keySet();
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public HttpVersion getProtocolVersion() {
        return version;
    }

    public String getUri() {
        return url;
    }

    public boolean isKeepAlive() {
        return version == HttpVersion.HTTP_1_1;
    }

    public State decode(ByteBuffer buffer) {
        try {
            while (buffer.hasRemaining()
                    && (state != State.PROTOCOL_ERROR || state != State.ALL_READ)) {
                switch (state) {
                case READ_INITIAL:
                    readInitialLine(buffer);
                    state = State.READ_HEADER;
                    break;
                case READ_HEADER:
                    String line = readLine(buffer);
                    while (!line.isEmpty()) {
                        splitAndAddHeader(line);
                        line = readLine(buffer);
                    }
                    String cl = headers.get(CONTENT_LENGTH);
                    if (cl != null) {
                        try {
                            contentLength = Integer.parseInt(cl);
                            content = new byte[contentLength];
                            contentRemain = contentLength;
                            state = State.READ_FIXED_LENGTH_CONTENT;
                        } catch (NumberFormatException e) {
                            state = State.PROTOCOL_ERROR;
                        }
                    } else {
                        state = State.ALL_READ;
                    }
                    break;
                case READ_FIXED_LENGTH_CONTENT:
                    int remain = buffer.remaining();
                    int read = Math.min(remain, contentRemain);
                    buffer.get(content, contentLength - contentRemain, read);
                    break;
                }
            }
        } catch (Exception e) {
            state = State.PROTOCOL_ERROR;
        }
        return state;
    }

    void readInitialLine(ByteBuffer buffer) {
        String sb = readLine(buffer);
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonWhitespace(sb, 0);
        aEnd = findWhitespace(sb, aStart);

        String m = sb.substring(aStart, aEnd).toUpperCase();
        if (m.equals("GET")) {
            method = HttpMethod.GET;
        } else if (m.equals("POST")) {
            method = HttpMethod.POST;
        } else if (m.equals("PUT")) {
            method = HttpMethod.PUT;
        } else if (m.equals("DELETE")) {
            method = HttpMethod.DELETE;
        } else {
            state = State.PROTOCOL_ERROR;
        }

        bStart = findNonWhitespace(sb, aEnd);
        bEnd = findWhitespace(sb, bStart);
        url = sb.substring(bStart, bEnd);

        cStart = findNonWhitespace(sb, bEnd);
        cEnd = findEndOfString(sb);

        String v = sb.substring(cStart, cEnd).toUpperCase();
        if (v.equals("HTTP/1.1")) {
            version = HttpVersion.HTTP_1_1;
        } else if (v.equals("HTTP/1.0")) {
            version = HttpVersion.HTTP_1_0;
        } else {
            state = State.PROTOCOL_ERROR;
        }
    }
}
