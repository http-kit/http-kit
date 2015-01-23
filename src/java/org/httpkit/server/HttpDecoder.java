package org.httpkit.server;

import org.httpkit.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.HttpVersion.HTTP_1_0;
import static org.httpkit.HttpVersion.HTTP_1_1;

public class HttpDecoder {

    public enum DecodingState {INITIALIZED, ONGOING, FINISHED}

    public class DecodingResult {
        public DecodingState state;
        public HttpRequest request;

        public DecodingResult(DecodingState state, HttpRequest request) {
            this.state = state;
            this.request = request;
        }
    }

    public enum State {
        ALL_READ, READ_INITIAL, READ_HEADER, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_FOOTER, READ_CHUNK_DELIMITER,
    }

    private State state = State.READ_INITIAL;
    private int readRemaining = -1; // bytes need read
    private int readCount = 0; // already read bytes count

    HttpRequest request; // package visible
    private Map<String, Object> headers = new TreeMap<String, Object>();
    CallbackPipedOutputStream content;

    private final int maxBody;
    private final LineReader lineReader;

    public HttpDecoder(int maxBody, int maxLine) {
        this.maxBody = maxBody;
        this.lineReader = new LineReader(maxLine);
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

    public DecodingResult decode(ByteBuffer buffer) throws LineTooLargeException,
            ProtocolException, RequestTooLargeException, IOException {
        String line;
        while (buffer.hasRemaining()) {
            switch (state) {
                case ALL_READ:
                    finish();
                    return new DecodingResult(DecodingState.FINISHED, null);
                case READ_INITIAL:
                    line = lineReader.readLine(buffer);
                    if (line != null) {
                        createRequest(line);
                        state = State.READ_HEADER;
                        content = new CallbackPipedOutputStream();
                    }
                    break;
                case READ_HEADER:
                    readHeaders(buffer);
                    if (state != state.READ_HEADER) {
                        if (state != State.ALL_READ) {
                            int pipeSize = state == state.READ_CHUNK_SIZE ? maxBody : readRemaining;
                            request.setBody(new CallbackPipedInputStream(content, pipeSize), readRemaining);
                        }
                        return new DecodingResult(DecodingState.INITIALIZED, request);
                    }
                case READ_CHUNK_SIZE:
                    line = lineReader.readLine(buffer);
                    if (line != null) {
                        readRemaining = getChunkSize(line);
                        if (readRemaining == 0) {
                            state = State.READ_CHUNK_FOOTER;
                        } else {
                            throwIfBodyIsTooLarge();
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
        return new DecodingResult(state == State.ALL_READ ? DecodingState.FINISHED : DecodingState.ONGOING, null);
    }

    private void finish() throws IOException {
        state = State.ALL_READ;
        content.close();
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR && buffer.hasRemaining()) {
            buffer.get(); // should be LF
        }
    }

    void readFixedLength(ByteBuffer buffer) throws IOException {
        int toRead = Math.min(buffer.remaining(), readRemaining);
        byte[] tmp = new byte[toRead];
        buffer.get(tmp);
        content.write(tmp);
        readRemaining -= toRead;
        readCount += toRead;
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException,
            RequestTooLargeException, ProtocolException {
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

    public void reset() throws IOException {
        state = State.READ_INITIAL;
        headers = new TreeMap<String, Object>();
        readCount = 0;
        if (content != null) {
            content.close();
        }
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
