package org.httpkit;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.httpkit.HttpUtils.CR;
import static org.httpkit.HttpUtils.LF;

public class LineReader {
    // 1k buffer, increase as necessary;
    byte[] lineBuffer = new byte[1024];
    int lineBufferIdx = 0;
    private final int maxLine;
    private boolean readCR = false;

    public LineReader(int maxLine) {
        this.maxLine = maxLine;
    }

    public String readLine(ByteBuffer buffer) throws LineTooLargeException, ProtocolException {
        byte b;
        boolean more = true;
        while (buffer.hasRemaining() && more) {
            b = buffer.get();

            if (readCR && b != LF) {
                throw new ProtocolException("Expected LF after CR, but found " + b);
            }

            if (b == CR) {
                readCR = true;
            } else if (b == LF) {
                more = false;
            } else {
                if (lineBufferIdx == maxLine - 2) {
                    throw new LineTooLargeException("exceed max line " + maxLine);
                }
                if (lineBufferIdx == lineBuffer.length) {
                    lineBuffer = Arrays.copyOf(lineBuffer, lineBuffer.length * 2);
                }
                lineBuffer[lineBufferIdx] = b;
                ++lineBufferIdx;
            }
        }
        String line = null;
        if (!more) {
            line = new String(lineBuffer, 0, lineBufferIdx);
            reset();
        }
        return line;
    }

    public final void reset() {
        this.lineBufferIdx = 0;
        this.readCR = false;
    }
}
