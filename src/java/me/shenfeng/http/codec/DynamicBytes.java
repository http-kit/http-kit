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

	public byte[] get() {
		return data;
	}

	public int getCount() {
		return idx;
	}

	public DynamicBytes write(byte b) {
		expandIfNessarry(1);
		data[idx++] = b;
		return this;
	}

	public DynamicBytes write(byte[] d, int offset, int length) {
		expandIfNessarry(length);
		System.arraycopy(d, offset, data, idx, length);
		idx += length;
		return this;
	}

	public DynamicBytes write(String str) {
		byte[] bs = str.getBytes(HttpUtils.ASCII);
		return write(bs, 0, bs.length);
	}
}
