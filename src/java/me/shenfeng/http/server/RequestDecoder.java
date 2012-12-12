package me.shenfeng.http.server;

import static me.shenfeng.http.HttpUtils.CHUNKED;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.LF;
import static me.shenfeng.http.HttpUtils.TRANSFER_ENCODING;
import static me.shenfeng.http.HttpUtils.findEndOfString;
import static me.shenfeng.http.HttpUtils.findNonWhitespace;
import static me.shenfeng.http.HttpUtils.findWhitespace;
import static me.shenfeng.http.HttpUtils.getChunkSize;
import static me.shenfeng.http.HttpVersion.HTTP_1_0;
import static me.shenfeng.http.HttpVersion.HTTP_1_1;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.LineTooLargeException;
import me.shenfeng.http.ProtocolException;
import me.shenfeng.http.RequestTooLargeException;

public class RequestDecoder {

    public enum State {
        ALL_READ, READ_INITIAL, READ_HEADER, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_FOOTER, READ_CHUNK_DELIMITER,
    }

    HttpRequest request;
    int readRemaining = 0;
    byte[] content;
    int readCount = 0;
    private Map<String, String> headers = new TreeMap<String, String>();
    private final int maxBody;
    private State state = State.READ_INITIAL;

    int lineBufferIdx = 0;
    private final byte[] lineBuffer;

    public RequestDecoder(int maxBody, int maxLine) {
        this.maxBody = maxBody;
        this.lineBuffer = new byte[maxLine];
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
        cEnd = findEndOfString(sb);

        if (cStart < cEnd) {
            HttpMethod method = HttpMethod.GET;
            String m = sb.substring(aStart, aEnd).toUpperCase();
            if (m.equals("GET")) {
                method = HttpMethod.GET;
            } else if (m.equals("POST")) {
                method = HttpMethod.POST;
            } else if (m.equals("PUT")) {
                method = HttpMethod.PUT;
            } else if (m.equals("DELETE")) {
                method = HttpMethod.DELETE;
            }
            HttpVersion version = HTTP_1_1;
            if ("HTTP/1.0".equals(sb.substring(cStart, cEnd))) {
                version = HTTP_1_0;
            }
            request = new HttpRequest(method, sb.substring(bStart, bEnd), version);
        } else {
            throw new ProtocolException("not http?");
        }
    }

    public State decode(ByteBuffer buffer) throws LineTooLargeException, ProtocolException,
            RequestTooLargeException {
        String line;
        while (buffer.hasRemaining() && state != State.ALL_READ) {
            switch (state) {
            case READ_INITIAL:
                line = readLine(buffer);
                if (line != null) {
                    createRequest(line);
                    state = State.READ_HEADER;
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
                        state = State.READ_CHUNK_FOOTER;
                    } else {
                        throwIfBodyIsTooLarge();
                        if (content == null) {
                            content = new byte[readRemaining];
                        } else if (content.length - readCount < readRemaining) {
                            // expand
                            content = Arrays.copyOf(content, readRemaining + readCount);
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
        return state;
    }

    private void finish() {
        state = State.ALL_READ;
        request.setBody(content, readCount);
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR) {
            buffer.get(); // should be LF
        } else if (b == LF) {
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
        String line = readLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = readLine(buffer);
        }

        if (line == null) {
            return;
        }

        request.setHeaders(headers);

        String te = headers.get(TRANSFER_ENCODING);
        if (CHUNKED.equals(te)) {
            state = State.READ_CHUNK_SIZE;
        } else {
            String cl = headers.get(CONTENT_LENGTH);
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
                if (lineBufferIdx == lineBuffer.length - 2) {
                    throw new LineTooLargeException("line length exceed " + lineBuffer.length);
                }
                lineBuffer[lineBufferIdx] = b;
                ++lineBufferIdx;
            }
        }
        String line = null;
        if (!more) {
            line = new String(lineBuffer, 0, lineBufferIdx);
            lineBufferIdx = 0;
        }
        return line;
    }

    public void reset() {
        state = State.READ_INITIAL;
        headers = new TreeMap<String, String>();
        readCount = 0;
        content = null;
    }

    private void throwIfBodyIsTooLarge() throws RequestTooLargeException {
        if (readCount + readRemaining > maxBody) {
            throw new RequestTooLargeException("request body " + (readCount + readRemaining)
                    + "; max request body " + maxBody);
        }
    }
}
