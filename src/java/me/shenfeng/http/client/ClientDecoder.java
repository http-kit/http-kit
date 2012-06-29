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
import static me.shenfeng.http.client.ClientDecoderState.ABORTED;
import static me.shenfeng.http.client.ClientDecoderState.ALL_READ;
import static me.shenfeng.http.client.ClientDecoderState.READ_CHUNKED_CONTENT;
import static me.shenfeng.http.client.ClientDecoderState.READ_CHUNK_DELIMITER;
import static me.shenfeng.http.client.ClientDecoderState.READ_CHUNK_FOOTER;
import static me.shenfeng.http.client.ClientDecoderState.READ_CHUNK_SIZE;
import static me.shenfeng.http.client.ClientDecoderState.READ_FIXED_LENGTH_CONTENT;
import static me.shenfeng.http.client.ClientDecoderState.READ_HEADER;
import static me.shenfeng.http.client.ClientDecoderState.READ_INITIAL;
import static me.shenfeng.http.client.ClientDecoderState.READ_VARIABLE_LENGTH_CONTENT;
import static me.shenfeng.http.client.IRespListener.ABORT;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.LineTooLargeException;
import me.shenfeng.http.ProtocolException;

public class ClientDecoder {

    private Map<String, String> headers = new TreeMap<String, String>();

    // package visible
    IRespListener listener;
    // single threaded, shared ok
    private static byte[] bodyBuffer = new byte[BUFFER_SIZE];
    byte[] lineBuffer = new byte[MAX_LINE];
    int lineBufferCnt = 0;
    int readRemaining = 0;
    ClientDecoderState state = READ_INITIAL;

    public ClientDecoder(IRespListener listener) {
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

            if (listener.onInitialLineReceived(version, s) != ABORT) {
                state = READ_HEADER;
            } else {
                state = ABORTED;
            }

        } else {
            throw new ProtocolException("not http prototol");
        }
    }

    public ClientDecoderState decode(ByteBuffer buffer)
            throws LineTooLargeException, ProtocolException {
        String line;
        int toRead;
        while (buffer.hasRemaining() && state != ALL_READ && state != ABORTED) {
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
                if (listener.onBodyReceived(bodyBuffer, toRead) == ABORT) {
                    state = ABORTED;
                } else {
                    readRemaining -= toRead;
                    if (readRemaining == 0) {
                        state = ALL_READ;
                    }
                }
                break;
            case READ_CHUNKED_CONTENT:
                toRead = Math.min(buffer.remaining(), readRemaining);
                buffer.get(bodyBuffer, 0, toRead);
                if (listener.onBodyReceived(bodyBuffer, toRead) == ABORT) {
                    state = ABORTED;
                } else {
                    readRemaining -= toRead;
                    if (readRemaining == 0) {
                        state = READ_CHUNK_DELIMITER;
                    }
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
                if (listener.onBodyReceived(bodyBuffer, toRead) == ABORT) {
                    state = ABORTED;
                }
                break;
            }
        }
        return state;
    }

    public IRespListener getListener() {
        return listener;
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
        if (listener.onHeadersReceived(headers) != ABORT) {
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
        } else {
            state = ABORTED;
        }
    };

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
                    throw new LineTooLargeException("exceed max line "
                            + MAX_LINE);
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
        state = ClientDecoderState.READ_INITIAL;
    }
}
