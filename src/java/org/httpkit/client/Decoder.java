package org.httpkit.client;

import org.httpkit.*;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.HttpVersion.HTTP_1_1;
import static org.httpkit.client.State.*;

enum State {
    ALL_READ, READ_CHUNK_DELIMITER, READ_CHUNK_TRAILER, READ_CHUNK_SIZE,
    READ_CHUNKED_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_HEADER, READ_INITIAL,
    READ_VARIABLE_LENGTH_CONTENT
}

public class Decoder {

    private final Map<String, Object> headers = new TreeMap<String, Object>();
    // package visible
    final IRespListener listener;
    private final LineReader lineReader;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private int headerBytes = 0;
    long readRemaining = 0;
    State state = READ_INITIAL;
    private final HttpMethod method;

    private boolean emptyBodyExpected = false;
    private boolean interimResponse = false;
    private boolean persistent = false;
    private boolean trailerReceived = false;
    private HttpVersion version = HTTP_1_1;
    private int statusCode = 0;

    public Decoder(IRespListener listener, HttpMethod method) {
        this.listener = listener;
        this.method = method;
        lineReader = new LineReader(16192); // max 16k header line
    }

    private void parseInitialLine(String sb) throws ProtocolException, AbortException {
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

        if ((cStart < cEnd)
                // Account for buggy web servers that omit Reason-Phrase from Status-Line.
                // http://www.w3.org/Protocols/HTTP/1.0/draft-ietf-http-spec.html#Response
                || (cStart == cEnd && bStart < bEnd)) {
            String versionToken = sb.substring(aStart, aEnd);
            String statusToken = sb.substring(bStart, bEnd);
            if (!"HTTP/1.0".equals(versionToken) && !"HTTP/1.1".equals(versionToken)) {
                throw new ProtocolException("Unsupported HTTP version: " + versionToken);
            }
            if (statusToken.length() != 3
                    || !isAsciiDigit(statusToken.charAt(0))
                    || !isAsciiDigit(statusToken.charAt(1))
                    || !isAsciiDigit(statusToken.charAt(2))) {
                throw new ProtocolException("Invalid HTTP status code: " + statusToken);
            }
            try {
                int status = Integer.parseInt(statusToken);
                statusCode = status;
                emptyBodyExpected = method == HttpMethod.HEAD || status / 100 == 1
                        || status == 204 || status == 205 || status == 304;
                interimResponse = status / 100 == 1 && status != 101;
                HttpStatus s = HttpStatus.valueOf(status);

                version = "HTTP/1.0".equals(versionToken) ? HTTP_1_0 : HTTP_1_1;

                if (!interimResponse) {
                    listener.onInitialLineReceived(version, s);
                }
                state = READ_HEADER;
            } catch (NumberFormatException e) {
                throw new ProtocolException("not http protocol? " + sb);
            }
        } else {
            throw new ProtocolException("not http protocol? " + sb);
        }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public State decode(ByteBuffer buffer) throws LineTooLargeException, ProtocolException,
            AbortException {
        String line;
        while (buffer.hasRemaining() && state != State.ALL_READ) {
            switch (state) {
                case READ_INITIAL:
                    if ((line = readHeaderLine(buffer)) != null) {
                        parseInitialLine(line);
                    }
                    break;
                case READ_HEADER:
                    readHeaders(buffer);
                    break;
                case READ_CHUNK_SIZE:
                    line = lineReader.readLine(buffer);
                    if (line != null && !line.isEmpty()) {
                        readRemaining = getChunkSize(line);
                        if (readRemaining < 0) {
                            throw new ProtocolException("Invalid negative chunk size");
                        }
                        if (readRemaining == 0) {
                            state = READ_CHUNK_TRAILER;
                        } else {
                            state = READ_CHUNKED_CONTENT;
                        }
                    }
                    break;
                case READ_FIXED_LENGTH_CONTENT:
                    readBody(buffer, ALL_READ);
                    break;
                case READ_CHUNKED_CONTENT:
                    readBody(buffer, READ_CHUNK_DELIMITER);
                    break;
                case READ_CHUNK_TRAILER:
                    String trLine = readHeaderLine(buffer);
                    while (trLine != null) {
                        if (trLine.isEmpty()) {
                            if (trailerReceived) {
                                listener.onHeadersReceived(headers);
                            }
                            state = ALL_READ;
                            break;
                        }
                        int colon = trLine.indexOf(':');
                        String name = colon > 0
                                ? trLine.substring(0, colon).toLowerCase(Locale.ROOT) : "";
                        if (HttpUtils.isForbiddenTrailer(name)) {
                            throw new ProtocolException("Forbidden trailer field: " + name);
                        }
                        HttpUtils.splitAndAddHeader(trLine, headers);
                        trailerReceived = true;
                        trLine = readHeaderLine(buffer);
                    }
                    break;
                case READ_CHUNK_DELIMITER:
                    readEmptyLine(buffer, READ_CHUNK_SIZE);
                    break;
                case READ_VARIABLE_LENGTH_CONTENT:
                    readBody(buffer, null);
                    break;
            }
        }
        return state;
    }

    private void readBody(ByteBuffer buffer, State nextState) throws AbortException {
        int toRead = (int) Math.min(buffer.remaining(), readRemaining);
        byte[] bytes = new byte[toRead];
        buffer.get(bytes, 0, toRead);
        listener.onBodyReceived(bytes, toRead);
        if (nextState != null) {
            readRemaining -= toRead;
            if (readRemaining == 0) {
                state = nextState;
            }
        }
    }

    private void readEmptyLine(ByteBuffer buffer, State nextState) throws ProtocolException, LineTooLargeException {
        String line = lineReader.readLine(buffer);
        if (line != null) {
            if (line.isEmpty()) {
                state = nextState;
            } else {
                throw new ProtocolException("Expected an empty line, but found " + line);
            }
        }
    }

    private long parseContentLength() throws ProtocolException {
        String cl = HttpUtils.getStringValue(headers, CONTENT_LENGTH);
        if (cl == null) {
            return -1;
        }

        for (int i = 0; i < cl.length(); i++) {
            if (!isAsciiDigit(cl.charAt(i))) {
                throw new ProtocolException("Invalid Content-Length: " + cl);
            }
        }
        if (cl.isEmpty()) {
            throw new ProtocolException("Invalid Content-Length: " + cl);
        }

        try {
            long parsed = Long.parseLong(cl);
            return parsed;
        } catch (NumberFormatException e) {
            throw new ProtocolException("Invalid Content-Length: " + cl);
        }
    }

    private boolean isChunkedTransferEncoding() throws ProtocolException {
        String te = HttpUtils.getStringValue(headers, TRANSFER_ENCODING);
        if (te == null) {
            return false;
        }

        String[] codings = te.toLowerCase(Locale.ROOT).split(",", -1);
        if (codings.length == 1 && CHUNKED.equals(codings[0].trim())) {
            return true;
        }
        throw new ProtocolException("Unsupported Transfer-Encoding: " + te);
    }

    private boolean hasConnectionToken(String token) {
        String connection = HttpUtils.getStringValue(headers, CONNECTION);
        if (connection == null) {
            return false;
        }
        for (String value : connection.split("[,\\n]")) {
            if (token.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException, AbortException, ProtocolException {
        String line = readHeaderLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = readHeaderLine(buffer);
        }
        if (line == null)
            return; // data is not received enough. for next run

        if (interimResponse) {
            headers.clear();
            interimResponse = false;
            state = READ_INITIAL;
            return;
        }

        if (headers.containsKey(TRANSFER_ENCODING) && headers.containsKey(CONTENT_LENGTH)) {
            throw new ProtocolException(
                    "Response contains both Transfer-Encoding and Content-Length");
        }
        listener.onHeadersReceived(headers);
        persistent = version == HTTP_1_1 && statusCode != 101
                && !hasConnectionToken("close");
        if (emptyBodyExpected) {
            state = ALL_READ;
            return;
        }

        if (isChunkedTransferEncoding()) {
            state = READ_CHUNK_SIZE;
        } else {
            long cl = parseContentLength();
            if (cl >= 0) {
                readRemaining = cl;
                if (readRemaining == 0) {
                    state = ALL_READ;
                } else {
                    state = READ_FIXED_LENGTH_CONTENT;
                }

            } else {
                state = READ_VARIABLE_LENGTH_CONTENT;
                readRemaining = Long.MAX_VALUE;
                persistent = false;
            }
        }
    }

    boolean isPersistent() {
        return persistent;
    }

    boolean completesOnEof() {
        return state == READ_VARIABLE_LENGTH_CONTENT;
    }

    private String readHeaderLine(ByteBuffer buffer)
            throws LineTooLargeException, ProtocolException {
        String line = lineReader.readLine(buffer);
        if (line != null) {
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new HeadersTooLargeException("HTTP headers exceed " + MAX_HEADER_BYTES + " bytes");
            }
        }
        return line;
    }
}
