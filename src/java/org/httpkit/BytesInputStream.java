package org.httpkit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * No synchronization, better toString
 */
public class BytesInputStream extends InputStream {
    private final byte[] buf;
    private final int count;
    private int mark = 0;

    private int pos;

    public BytesInputStream(byte[] data, int length) {
        this.buf = data;
        this.count = length;
        this.pos = 0;
    }

    /**
     * get the underlying bytes, copied
     *
     * @return
     */
    public byte[] bytes() {
        return Arrays.copyOf(buf, count);
    }

    public int read() throws IOException {
        return (pos < count) ? (buf[pos++] & 0xff) : -1;
    }

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (pos >= count) {
            return -1;
        }

        int avail = count - pos;
        if (len > avail) {
            len = avail;
        }

        System.arraycopy(buf, pos, b, off, len);
        pos += len;
        return len;
    }

    public String toString() {
        return "BytesInputStream[len=" + count + "]";
    }

    public long skip(long n) throws IOException {
        long k = count - pos;
        if (n < k) {
            k = n < 0 ? 0 : n;
        }
        pos += k;
        return k;
    }

    public int available() {
        return count - pos;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) {
        mark = pos;
    }

    public void reset() {
        pos = mark;
    }
}
