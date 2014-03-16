package org.httpkit.client;

import org.httpkit.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.HttpVersion.HTTP_1_1;
import static org.httpkit.client.State.*;

enum State {
    ALL_READ, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, READ_CHUNK_SIZE,
    READ_CHUNKED_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_HEADER, READ_INITIAL,
    READ_VARIABLE_LENGTH_CONTENT
}

public class Decoder {

    private final Map<String, Object> headers = new TreeMap<String, Object>();
    // package visible
    final IRespListener listener;
    private final LineReader lineReader;
    int readRemaining = 0;
    State state = READ_INITIAL;
    private final HttpMethod method;

    private boolean emptyBodyExpected = false;

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
            try {
                int status = Integer.parseInt(sb.substring(bStart, bEnd));
                // status is not 1xx, 204 or 304, then the body is unbounded.
                // RFC2616, section 4.4
                emptyBodyExpected = status / 100 == 1 || status == 204 || status == 304;
                HttpStatus s = HttpStatus.valueOf(status);

                HttpVersion version = HTTP_1_1;
                if ("HTTP/1.0".equals(sb.substring(aStart, aEnd))) {
                    version = HTTP_1_0;
                }

                listener.onInitialLineReceived(version, s);
                state = READ_HEADER;
            } catch (NumberFormatException e) {
                throw new ProtocolException("not http protocol? " + sb);
            }
        } else {
            throw new ProtocolException("not http protocol? " + sb);
        }
    }

    public State decode(ByteBuffer buffer) throws LineTooLargeException, ProtocolException,
            AbortException {
        String line;
        while (buffer.hasRemaining() && state != State.ALL_READ) {
            switch (state) {
                case READ_INITIAL:
                    if ((line = lineReader.readLine(buffer)) != null) {
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
                        if (readRemaining == 0) {
                            state = READ_CHUNK_FOOTER;
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
                case READ_CHUNK_FOOTER:
                    readEmptyLine(buffer);
                    state = ALL_READ;
                    break;
                case READ_CHUNK_DELIMITER:
                    readEmptyLine(buffer);
                    state = READ_CHUNK_SIZE;
                    break;
                case READ_VARIABLE_LENGTH_CONTENT:
                    readBody(buffer, null);
                    break;
            }
        }
        return state;
    }

    private void readBody(ByteBuffer buffer, State nextState) throws AbortException {
        int toRead = Math.min(buffer.remaining(), readRemaining);
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

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR && buffer.hasRemaining()) {
            buffer.get(); // should be LF
        }
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException, AbortException {
        String line = lineReader.readLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = lineReader.readLine(buffer);
        }
        if (line == null)
            return; // data is not received enough. for next run
        listener.onHeadersReceived(headers);
        if (method == HttpMethod.HEAD) {
            state = ALL_READ;
            return;
        }

        String te = HttpUtils.getStringValue(headers, TRANSFER_ENCODING);
        if (CHUNKED.equals(te)) {
            state = READ_CHUNK_SIZE;
        } else {
            String cl = HttpUtils.getStringValue(headers, CONTENT_LENGTH);
            if (cl != null) {
                readRemaining = Integer.parseInt(cl);
                if (readRemaining == 0) {
                    state = ALL_READ;
                } else {
                    state = READ_FIXED_LENGTH_CONTENT;
                }
            } else if (emptyBodyExpected) {
                state = ALL_READ;
            } else {
                state = READ_VARIABLE_LENGTH_CONTENT;
                // for readBody min
                readRemaining = Integer.MAX_VALUE;
            }
        }
    }
}
