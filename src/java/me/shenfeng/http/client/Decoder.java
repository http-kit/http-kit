package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.BUFFER_SIZE;
import static me.shenfeng.http.HttpUtils.CHUNKED;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.LF;
import static me.shenfeng.http.HttpUtils.MAX_LINE;
import static me.shenfeng.http.HttpUtils.TRANSFER_ENCODING;
import static me.shenfeng.http.HttpUtils.findEndOfString;
import static me.shenfeng.http.HttpUtils.findNonWhitespace;
import static me.shenfeng.http.HttpUtils.findWhitespace;
import static me.shenfeng.http.HttpUtils.getChunkSize;
import static me.shenfeng.http.HttpVersion.HTTP_1_0;
import static me.shenfeng.http.HttpVersion.HTTP_1_1;
import static me.shenfeng.http.client.DState.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.LineTooLargeException;
import me.shenfeng.http.ProtocolException;
import me.shenfeng.http.client.IRespListener.State;

enum DState {
    ALL_READ, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_HEADER, READ_INITIAL, ABORTED, READ_VARIABLE_LENGTH_CONTENT
}

public class Decoder {

    private final Map<String, String> headers = new TreeMap<String, String>();
    // package visible
    final IRespListener listener;
    final byte[] lineBuffer = new byte[MAX_LINE];
    int lineBufferCnt = 0;
    int readRemaining = 0;
    DState st = READ_INITIAL;

    public Decoder(IRespListener listener) {
        this.listener = listener;
    }

    private void parseInitialLine(String sb) throws ProtocolException {
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
            int status = Integer.parseInt(sb.substring(bStart, bEnd));
            HttpStatus s = HttpStatus.valueOf(status);

            HttpVersion version = HTTP_1_1;
            if ("HTTP/1.0".equals(sb.substring(aStart, cEnd))) {
                version = HTTP_1_0;
            }

            if (listener.onInitialLineReceived(version, s) != State.ABORT) {
                st = READ_HEADER;
            } else {
                st = ABORTED;
            }

        } else {
            throw new ProtocolException("not http prototol");
        }
    }

    public DState decode(ByteBuffer buffer) throws LineTooLargeException, ProtocolException {
        String line;
        int toRead;
        // fine, JVM is very fast for short lived var
        byte[] bodyBuffer = new byte[BUFFER_SIZE];
        while (buffer.hasRemaining() && st != ALL_READ && st != ABORTED) {
            switch (st) {
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
                        st = READ_CHUNK_FOOTER;
                    } else {
                        st = READ_CHUNKED_CONTENT;
                    }
                }
                break;
            case READ_FIXED_LENGTH_CONTENT:
                toRead = Math.min(buffer.remaining(), readRemaining);
                buffer.get(bodyBuffer, 0, toRead);
                if (listener.onBodyReceived(bodyBuffer, toRead) == State.ABORT) {
                    st = ABORTED;
                } else {
                    readRemaining -= toRead;
                    if (readRemaining == 0) {
                        st = ALL_READ;
                    }
                }
                break;
            case READ_CHUNKED_CONTENT:
                toRead = Math.min(buffer.remaining(), readRemaining);
                buffer.get(bodyBuffer, 0, toRead);
                if (listener.onBodyReceived(bodyBuffer, toRead) == State.ABORT) {
                    st = ABORTED;
                } else {
                    readRemaining -= toRead;
                    if (readRemaining == 0) {
                        st = READ_CHUNK_DELIMITER;
                    }
                }
                break;
            case READ_CHUNK_FOOTER:
                readEmptyLine(buffer);
                st = ALL_READ;
                break;
            case READ_CHUNK_DELIMITER:
                readEmptyLine(buffer);
                st = READ_CHUNK_SIZE;
                break;
            case READ_VARIABLE_LENGTH_CONTENT:
                toRead = buffer.remaining();
                buffer.get(bodyBuffer, 0, toRead);
                if (listener.onBodyReceived(bodyBuffer, toRead) == State.ABORT) {
                    st = ABORTED;
                }
                break;
            }
        }
        return st;
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR) {
            buffer.get(); // should be LF
        } else if (b == LF) {
        }
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException {
        String line = readLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = readLine(buffer);
        }
        if (line == null)
            return; // data is not received enough. for next run
        if (listener.onHeadersReceived(headers) != State.ABORT) {
            String te = headers.get(TRANSFER_ENCODING);
            if (CHUNKED.equals(te)) {
                st = READ_CHUNK_SIZE;
            } else {
                String cl = headers.get(CONTENT_LENGTH);
                if (cl != null) {
                    readRemaining = Integer.parseInt(cl);
                    if (readRemaining == 0) {
                        st = ALL_READ;
                    } else {
                        st = READ_FIXED_LENGTH_CONTENT;
                    }
                } else {
                    st = READ_VARIABLE_LENGTH_CONTENT;
                }
            }
        } else {
            st = ABORTED;
        }
    }

    String readLine(ByteBuffer buffer) throws LineTooLargeException {
        byte b;
        boolean more = true;
        while (buffer.hasRemaining() && more) {
            b = buffer.get();
            if (b == CR) {
                if (buffer.get() == LF)
                    more = false;
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
        st = DState.READ_INITIAL;
    }
}
