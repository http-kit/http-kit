package org.httpkit.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.httpkit.HttpMethod;
import org.httpkit.HttpUtils;
import org.httpkit.HttpVersion;
import org.httpkit.LineReader;
import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;
import org.httpkit.RequestTooLargeException;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.HttpVersion.HTTP_1_1;

public class HttpDecoder {

    public enum State {
        ALL_READ, CONNECTION_OPEN, READ_INITIAL, READ_HEADER, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_FOOTER, READ_CHUNK_DELIMITER,
    }

    /**
     * Pattern for matching numbers 0 to 255.  We use this in the IPV4 address pattern to prevent invalid sequences
     * from be parsed by InetAddress.getByName and thus being treated as a name instead of an address.
     */
    private static final String IPV4SEG = "(?:0|1\\d{0,2}|2(?:[0-4]\\d*|5[0-5]?|[6-9])?|[3-9]\\d?)";
    private static final String IPV4ADDR = IPV4SEG + "(?:\\." + IPV4SEG + "){3}";
    /**
     * Pattern for a port number.  We are not as strict in our pattern matching as we are with ipv4 address
     * and instead rely upon Integer.parseInt and a range check.  Port 0 is invalid, so we disallow that here.
     */
    private static final String PORT = "[1-9]\\d{0,4}";
    /**
     * The PROXY protocol header is quite strict in what it allows, specifying for example that only
     * a single space character (\x20) is allowed between components.
     */
    private static final Pattern PROXY_PATTERN = Pattern.compile(
        "PROXY\\x20TCP4\\x20(" + IPV4ADDR + ")\\x20(" + IPV4ADDR +")\\x20(" + PORT + ")\\x20(" + PORT + ")"
    );

    private State state;
    private ProxyProtocolOption proxyProtocolOption;
    private int readRemaining = 0; // bytes need read
    private int readCount = 0; // already read bytes count

    private String xForwardedFor;
    private String xForwardedProto;
    private int xForwardedPort;
    HttpRequest request; // package visible
    private Map<String, Object> headers = new TreeMap<String, Object>();
    byte[] content;

    private final int maxBody;
    private final LineReader lineReader;

    public HttpDecoder(int maxBody, int maxLine, ProxyProtocolOption proxyProtocolOption) {
        this.maxBody = maxBody;
        this.lineReader = new LineReader(maxLine);
        this.proxyProtocolOption = (proxyProtocolOption == null)
            ? ProxyProtocolOption.DISABLED : proxyProtocolOption;

        this.state = (proxyProtocolOption == ProxyProtocolOption.DISABLED)
            ? State.READ_INITIAL : State.CONNECTION_OPEN;
    }

    private boolean parseProxyLine(String line) throws ProtocolException {
        // PROXY TCP4 255.255.255.255 255.255.255.255 65535 65535\r\n
        if (!line.startsWith("PROXY ")) {
            return false;
        }

        final Matcher m = PROXY_PATTERN.matcher(line);
        if (!m.matches()) {
            throw new ProtocolException("Unsupported or malformed proxy header: "+line);
        }

        try {
            final InetAddress clientAddr = InetAddress.getByName(m.group(1));
            final InetAddress proxyAddr = InetAddress.getByName(m.group(2));

            final int clientPort = Integer.parseInt(m.group(3), 10);
            final int proxyPort = Integer.parseInt(m.group(4), 10);

            if (((clientPort | proxyPort) & ~0xffff) != 0) {
                throw new ProtocolException("Invalid port number: "+line);
            }

            xForwardedFor = clientAddr.getHostAddress();
            if (proxyPort == 80) {
                xForwardedProto = "http";
            } else if (proxyPort == 443) {
                xForwardedProto = "https";
            }
            xForwardedPort = proxyPort;

            return true;

        } catch (NumberFormatException ex) {
            throw new ProtocolException("Malformed port in: "+line);
        } catch (UnknownHostException ex) {
            throw new ProtocolException("Malformed address in: "+line);
        }
    }

    private void createRequest(String sb) throws ProtocolException {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonWhitespace(sb, 0);
        aEnd = findWhitespace(sb, aStart);

        bStart = findNonWhitespace(sb, aEnd);
        bEnd = findWhitespace(sb, bStart);

        cStart = findNonWhitespace(sb, bEnd);
        cEnd = findEndOfString(sb, cStart);

        if (cStart < cEnd) {
            try {
                HttpMethod method = HttpMethod.valueOf(sb.substring(aStart, aEnd).toUpperCase());
                HttpVersion version = HTTP_1_1;
                if ("HTTP/1.0".equals(sb.substring(cStart, cEnd))) {
                    version = HTTP_1_0;
                }
                request = new HttpRequest(method, sb.substring(bStart, bEnd), version);
            } catch (Exception e) {
                throw new ProtocolException("method not understand");
            }
        } else {
            throw new ProtocolException("not http?");
        }
    }

    public boolean requiresContinue() {
        String expect = (String) headers.get(EXPECT);
        return (request != null && request.version == HTTP_1_1 &&
            expect != null && CONTINUE.equalsIgnoreCase(expect));
    }

    public HttpRequest decode(ByteBuffer buffer) throws LineTooLargeException,
            ProtocolException, RequestTooLargeException {
        String line;
        while (buffer.hasRemaining()) {
            switch (state) {
                case ALL_READ:
                    return request;
                case CONNECTION_OPEN:
                    line = lineReader.readLine(buffer);
                    if (line != null) {
                        // parseProxyLines returns true if the line parsed
                        // false if it was not a PROXY line
                        // or throws ProtocolException, if the PROXY line is malformed or unsupported.
                        if (parseProxyLine(line)) {
                            // valid proxy header
                            state = State.READ_INITIAL;
                        } else if (proxyProtocolOption == ProxyProtocolOption.OPTIONAL) {
                            // did not parse as a proxy header, try to create a request from it
                            // as the READ_INITIAL state would.
                            createRequest(line);
                            state = State.READ_HEADER;
                        } else {
                            throw new ProtocolException("Expected PROXY header, got: "+line);
                        }
                    }
                    break;
                case READ_INITIAL:
                    line = lineReader.readLine(buffer);
                    if (line != null) {
                        createRequest(line);
                        state = State.READ_HEADER;
                    }
                    break;
                case READ_HEADER:
                    readHeaders(buffer);
                    break;
                case READ_CHUNK_SIZE:
                    line = lineReader.readLine(buffer);
                    if (line != null) {
                        readRemaining = getChunkSize(line);
                        if (readRemaining == 0) {
                            state = State.READ_CHUNK_FOOTER;
                        } else {
                            throwIfBodyIsTooLarge();
                            if (content == null) {
                                content = new byte[readRemaining];
                            } else if (content.length < readCount + readRemaining) {
                                // *1.3 to protect slow client
                                int newLength = (int) ((readRemaining + readCount) * 1.3);
                                content = Arrays.copyOf(content, newLength);
                            }
                            state = State.READ_CHUNKED_CONTENT;
                        }
                    }
                    break;
                case READ_FIXED_LENGTH_CONTENT:
                    readFixedLength(buffer);
                    if (readRemaining == 0) {
                        finish();
                    }
                    break;
                case READ_CHUNKED_CONTENT:
                    readFixedLength(buffer);
                    if (readRemaining == 0) {
                        state = State.READ_CHUNK_DELIMITER;
                    }
                    break;
                case READ_CHUNK_FOOTER:
                    readEmptyLine(buffer);
                    finish();
                    break;
                case READ_CHUNK_DELIMITER:
                    readEmptyLine(buffer);
                    state = State.READ_CHUNK_SIZE;
                    break;
            }
        }
        return state == State.ALL_READ ? request : null;
    }

    private void finish() {
        state = State.ALL_READ;
        request.setBody(content, readCount);
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR && buffer.hasRemaining()) {
            buffer.get(); // should be LF
        }
    }

    void readFixedLength(ByteBuffer buffer) {
        int toRead = Math.min(buffer.remaining(), readRemaining);
        buffer.get(content, readCount, toRead);
        readRemaining -= toRead;
        readCount += toRead;
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException,
            RequestTooLargeException, ProtocolException {
        if (proxyProtocolOption == ProxyProtocolOption.OPTIONAL
            || proxyProtocolOption == ProxyProtocolOption.ENABLED) {
            headers.put("x-forwarded-for", xForwardedFor);
            headers.put("x-forwarded-proto", xForwardedProto);
            headers.put("x-forwarded-port", xForwardedPort);
        }
        String line = lineReader.readLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = lineReader.readLine(buffer);
        }

        if (line == null) {
            return;
        }

        request.setHeaders(headers);

        String te = HttpUtils.getStringValue(headers, TRANSFER_ENCODING);
        if (CHUNKED.equals(te)) {
            state = State.READ_CHUNK_SIZE;
        } else {
            String cl = HttpUtils.getStringValue(headers, CONTENT_LENGTH);
            if (cl != null) {
                try {
                    readRemaining = Integer.parseInt(cl);
                    if (readRemaining > 0) {
                        throwIfBodyIsTooLarge();
                        content = new byte[readRemaining];
                        state = State.READ_FIXED_LENGTH_CONTENT;
                    } else {
                        state = State.ALL_READ;
                    }
                } catch (NumberFormatException e) {
                    throw new ProtocolException(e.getMessage());
                }
            } else {
                state = State.ALL_READ;
            }
        }
    }

    public void reset() {
        state = State.READ_INITIAL;
        headers = new TreeMap<String, Object>();
        readCount = 0;
        content = null;
        lineReader.reset();
        request = null;
    }

    private void throwIfBodyIsTooLarge() throws RequestTooLargeException {
        if (readCount + readRemaining > maxBody) {
            throw new RequestTooLargeException("request body " + (readCount + readRemaining)
                    + "; max request body " + maxBody);
        }
    }
}
