package org.httpkit.client;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.HttpVersion.HTTP_1_1;
import static org.httpkit.client.State.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import org.httpkit.HttpMethod;
import org.httpkit.HttpStatus;
import org.httpkit.HttpUtils;
import org.httpkit.HttpVersion;
import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;

enum State {
    ALL_READ, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_HEADER, READ_INITIAL, READ_VARIABLE_LENGTH_CONTENT
}

public class Decoder {

    private final Map<String, String> headers = new TreeMap<String, String>();
    // package visible
    final IRespListener listener;
    final byte[] lineBuffer = new byte[MAX_LINE];
    int lineBufferCnt = 0;
    int readRemaining = 0;
    State state = READ_INITIAL;
    private final HttpMethod method;

    public Decoder(IRespListener listener, HttpMethod method) {
        this.listener = listener;
        this.method = method;
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
        cEnd = findEndOfString(sb);

        if (cStart < cEnd) {
            try {
                int status = Integer.parseInt(sb.substring(bStart, bEnd));
                HttpStatus s = HttpStatus.valueOf(status);

                HttpVersion version = HTTP_1_1;
                if ("HTTP/1.0".equals(sb.substring(aStart, cEnd))) {
                    version = HTTP_1_0;
                }

                listener.onInitialLineReceived(version, s);
                state = READ_HEADER;
            } catch (NumberFormatException e) {
                throw new ProtocolException("not http prototol? " + sb);
            }
        } else {
            throw new ProtocolException("not http prototol? " + sb);
        }
    }

    public State decode(ByteBuffer buffer) throws LineTooLargeException, ProtocolException,
            AbortException {
        String line;
        int toRead;
        // fine, JVM is very fast for short lived var
        byte[] bodyBuffer = new byte[BUFFER_SIZE];
        while (buffer.hasRemaining() && state != ALL_READ) {
            switch (state) {
            case READ_INITIAL:
                line = readLine(buffer);
                if (line != null) {
                    parseInitialLine(line);
                }
                break;
            case READ_HEADER:
                readHeaders(buffer);
                break;
            case READ_CHUNK_SIZE:
                line = readLine(buffer);
                if (line != null) {
                    readRemaining = getChunkSize(line);
                    if (readRemaining == 0) {
                        state = READ_CHUNK_FOOTER;
                    } else {
                        state = READ_CHUNKED_CONTENT;
                    }
                }

                break;
            case READ_FIXED_LENGTH_CONTENT:
                toRead = Math.min(buffer.remaining(), readRemaining);
                buffer.get(bodyBuffer, 0, toRead);
                listener.onBodyReceived(bodyBuffer, toRead);
                readRemaining -= toRead;
                if (readRemaining == 0) {
                    state = ALL_READ;
                }
                break;
            case READ_CHUNKED_CONTENT:
                toRead = Math.min(buffer.remaining(), readRemaining);
                buffer.get(bodyBuffer, 0, toRead);
                listener.onBodyReceived(bodyBuffer, toRead);
                readRemaining -= toRead;
                if (readRemaining == 0) {
                    state = READ_CHUNK_DELIMITER;
                }
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
                toRead = buffer.remaining();
                buffer.get(bodyBuffer, 0, toRead);
                listener.onBodyReceived(bodyBuffer, toRead);
                break;
            }
        }
        return state;
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR && buffer.hasRemaining()) {
            buffer.get(); // should be LF
        }
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException, AbortException {
        String line = readLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = readLine(buffer);
        }
        if (line == null)
            return; // data is not received enough. for next run
        listener.onHeadersReceived(headers);
        if (method == HttpMethod.HEAD) {
            state = ALL_READ;
            return;
        }

        String te = headers.get(TRANSFER_ENCODING);
        if (CHUNKED.equals(te)) {
            state = READ_CHUNK_SIZE;
        } else {
            String cl = headers.get(CONTENT_LENGTH);
            if (cl != null) {
                readRemaining = Integer.parseInt(cl);
                if (readRemaining == 0) {
                    state = ALL_READ;
                } else {
                    state = READ_FIXED_LENGTH_CONTENT;
                }
            } else {
                state = READ_VARIABLE_LENGTH_CONTENT;
            }
        }

    }

    String readLine(ByteBuffer buffer) throws LineTooLargeException {
        byte b;
        boolean more = true;
        while (buffer.hasRemaining() && more) {
            b = buffer.get();
            if (b == CR) {
                if (buffer.hasRemaining() && buffer.get() == LF) {
                    more = false;
                }
            } else if (b == LF) {
                more = false;
            } else {
                lineBuffer[lineBufferCnt] = b;
                ++lineBufferCnt;
                if (lineBufferCnt >= MAX_LINE) {
                    throw new LineTooLargeException("exceed max line " + MAX_LINE);
                }
            }
        }
        String line = null;
        if (!more) {
            line = new String(lineBuffer, 0, lineBufferCnt);
            lineBufferCnt = 0;
        }
        return line;
    }

    public void reset() {
        headers.clear();
        state = State.READ_INITIAL;
    }
}
