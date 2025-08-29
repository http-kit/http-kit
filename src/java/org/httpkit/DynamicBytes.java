package org.httpkit;

import java.nio.charset.Charset;
import java.util.Arrays;
import org.httpkit.ContentTooLargeException;

public class DynamicBytes {
    private byte[] data;
    private int idx = 0;

    // Ref. https://github.com/openjdk/jdk/blob/d5ce66698d2f15c5f8316110a6118a10baa4013d/src/java.base/share/classes/jdk/internal/util/ArraysSupport.java#L842-L854
    private static final int SOFT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    public DynamicBytes(int size) {
        data = new byte[size];
    }

    private void expandIfNeeded(int more) {
        long need = (long) idx + more;

        if (need > SOFT_MAX_ARRAY_LENGTH) {
            throw new ContentTooLargeException("Cannot expand DynamicBytes array: requested size (" + need + ") exceeds Java limits");
        }

        if (data.length < need) {
            long newLength = data.length + (data.length >> 1); // 150% of old

            if (newLength < need) {
                newLength = need + (need >> 2); // 125% of need
            }

            if (newLength > SOFT_MAX_ARRAY_LENGTH) {
                newLength = SOFT_MAX_ARRAY_LENGTH;
            }

            data = Arrays.copyOf(data, (int) newLength);
        }
    }

    public byte[] get() {
        return data;
    }

    public int length() {
        return idx;
    }

    public DynamicBytes append(byte b) {
        expandIfNeeded(1);
        data[idx++] = b;
        return this;
    }

    public void append(byte b1, byte b2) {
        expandIfNeeded(2);
        data[idx++] = b1;
        data[idx++] = b2;
    }

    @Override
    public String toString() {
        return "DynamicBytes[len=" + idx + ", cap=" + data.length + ']';
    }

    public DynamicBytes append(byte[] d, int length) {
        expandIfNeeded(length);
        System.arraycopy(d, 0, data, idx, length);
        idx += length;
        return this;
    }

    public DynamicBytes append(String str) {
        // ISO-8859-1. much faster than String.getBytes("ISO-8859-1")
        // less copy. 620ms vs 190ms
        int length = str.length();
        expandIfNeeded(length);
        for (int i = 0; i < length; i++) {
            char c = str.charAt(i);
            if (c < 128) {
                data[idx++] = (byte) c;
            } else {
                data[idx++] = (byte) '?'; // JDK uses ? to represent non ASCII
            }
        }
        return this;
    }

    public DynamicBytes append(String str, Charset c) {
        byte[] bs = str.getBytes(c);
        return append(bs, bs.length);
    }

    /**
     * Get copy of the underlying bytes
     *
     * @return
     */
    public byte[] bytes() {
        return Arrays.copyOf(data, idx);
    }
}
