package me.shenfeng.http.codec;

import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.LF;

import java.nio.ByteBuffer;

public abstract class HttpMessageDecoder {

    public static enum State {
        PROTOCOL_ERROR, ALL_READ, READ_INITIAL, READ_HEADER, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_FOOTER, READ_CHUNK_DELIMITER,
    }

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

    byte[] lineBuffer = new byte[64];
    int lineBufferCnt = 0;

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
                if (lineBufferCnt >= lineBuffer.length) {
                    byte[] tmp = new byte[lineBuffer.length * 2];
                    System.arraycopy(lineBuffer, 0, tmp, 0, lineBuffer.length);
                    lineBuffer = tmp;
                }
                lineBuffer[lineBufferCnt] = b;
                ++lineBufferCnt;
                if (lineBufferCnt >= MAX_LINE) {
                    throw new LineTooLargeException();
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

    IHttpMessage message;
    int readFixLengthRemaining = 0;
    State state;
    byte[] content;
    int readContent = 0;

    public HttpMessageDecoder() {
        state = State.READ_INITIAL;
    }

    abstract IHttpMessage createMessage(String[] s);

    void readFixedLength(ByteBuffer buffer) {
        int toRead = Math.min(buffer.remaining(), readFixLengthRemaining);
        buffer.get(content, readContent, toRead);
        readFixLengthRemaining -= toRead;
        readContent += toRead;
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR) {
            buffer.get(); // should be LF
        } else if (b == LF) {
        }
    }

    public State decode(ByteBuffer buffer) throws LineTooLargeException,
            ProtocolException {
        String line;
        while (buffer.hasRemaining() && state != State.ALL_READ) {
            switch (state) {
            case READ_INITIAL:
                line = readLine(buffer);
                if (line != null) {
                    String s[] = splitInitialLine(line);
                    if (s.length == 3) {
                        message = createMessage(s);
                        state = State.READ_HEADER;
                    } else {
                        throw new ProtocolException();
                    }
                }
                break;
            case READ_HEADER:
                state = readHeaders(buffer);
                break;
            case READ_CHUNK_SIZE:
                line = readLine(buffer);
                if (line != null) {
                    readFixLengthRemaining = getChunkSize(line);
                    if (readFixLengthRemaining == 0) {
                        state = State.READ_CHUNK_FOOTER;
                    } else {
                        if (content == null) {
                            content = new byte[readFixLengthRemaining];
                        } else if (content.length - readContent < readFixLengthRemaining) {
                            byte[] tmp = new byte[readFixLengthRemaining + readContent];
                            System.arraycopy(content, 0, tmp, 0, readContent);
                            content = tmp;
                        }
                        state = State.READ_CHUNKED_CONTENT;
                    }
                }
                break;
            case READ_FIXED_LENGTH_CONTENT:
                readFixedLength(buffer);
                if (readFixLengthRemaining == 0) {
                    state = State.ALL_READ;
                }
                break;
            case READ_CHUNKED_CONTENT:
                readFixedLength(buffer);
                if (readFixLengthRemaining == 0) {
                    state = State.READ_CHUNK_DELIMITER;
                }
                break;
            case READ_CHUNK_FOOTER:
                readEmptyLine(buffer);
                state = State.ALL_READ;
                break;
            case READ_CHUNK_DELIMITER:
                readEmptyLine(buffer);
                state = State.READ_CHUNK_SIZE;
                break;
            }
        }
        return state;
    }

    private int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

    public abstract IHttpMessage getMessage();

    boolean isContentAlwaysEmpty() {
        return false;
    }

    abstract boolean isDecodingRequest();

    private State readHeaders(ByteBuffer buffer) throws LineTooLargeException {
        String line = readLine(buffer);
        while (line != null && !line.isEmpty()) {
            splitAndAddHeader(line);
            line = readLine(buffer);
        }
        String te = message.getHeader("transfer-encoding");
        if ("chunked".equals(te)) {
            return State.READ_CHUNK_SIZE;
        } else {
            String cl = message.getHeader("content-length");
            if (cl != null) {
                try {
                    readFixLengthRemaining = Integer.parseInt(cl);
                    content = new byte[readFixLengthRemaining];
                    return State.READ_FIXED_LENGTH_CONTENT;
                } catch (NumberFormatException e) {
                    return State.PROTOCOL_ERROR;
                }
            }
            return State.ALL_READ;
        }
    };

    public void reset() {
        state = State.READ_INITIAL;
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
        message.setHeader(key, value);
    }

    private String[] splitInitialLine(String sb) {
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

        return new String[] { sb.substring(aStart, aEnd), sb.substring(bStart, bEnd),
                cStart < cEnd ? sb.substring(cStart, cEnd) : "" };
    }

}
