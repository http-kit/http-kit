package me.shenfeng.http.codec;

import me.shenfeng.http.HttpUtils;

public class DynamicBytes {
    private byte[] data;
    private int idx = 0;

    public DynamicBytes(int size) {
        data = new byte[size];
    }

    private void expandIfNessarry(int more) {
        if (idx + more >= data.length) {
            byte[] tmp = new byte[data.length * 2];
            System.arraycopy(data, 0, tmp, 0, idx);
            data = tmp;
        }
    }

    public void write(String str) {
        write(str.getBytes(HttpUtils.ASCII));
    }

    public void write(byte[] d) {
        expandIfNessarry(d.length);
        System.arraycopy(d, 0, data, idx, d.length);
        idx += d.length;
    }

    public void write(byte b) {
        expandIfNessarry(1);
        data[idx++] = b;
    }

    public int getCount() {
        return idx;
    }

    public byte[] get() {
        return data;
    }
}
