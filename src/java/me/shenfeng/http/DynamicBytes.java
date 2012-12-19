package me.shenfeng.http;

import java.nio.charset.Charset;
import java.util.Arrays;

public class DynamicBytes {
    private byte[] data;
    private int idx = 0;

    public DynamicBytes(int size) {
        data = new byte[size];
    }

    private void expandIfNessarry(int more) {
        if (idx + more > data.length) {
            int after = (int) ((idx + more) * 1.33);
            // String msg = "expand memory, from " + data.length + " to " +
            // after + "; need " + more;
            // System.out.println(msg);
            data = Arrays.copyOf(data, after);
        }
    }

    public byte[] get() {
        return data;
    }

    public int length() {
        return idx;
    }

    public DynamicBytes append(byte b) {
        expandIfNessarry(1);
        data[idx++] = b;
        return this;
    }

    @Override
    public String toString() {
        return "DynamicBytes[len=" + idx + ", cap=[" + data.length + ']';
    }

    public DynamicBytes append(byte[] d, int offset, int length) {
        expandIfNessarry(length);
        System.arraycopy(d, offset, data, idx, length);
        idx += length;
        return this;
    }

    public DynamicBytes append(String str) {
        return append(str, HttpUtils.ASCII);
    }

    public DynamicBytes append(String str, Charset c) {
        byte[] bs = str.getBytes(c);
        return append(bs, 0, bs.length);
    }
}
