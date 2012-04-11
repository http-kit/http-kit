package me.shenfeng.http;

import java.nio.charset.Charset;

public class DynamicBytes {
	private byte[] data;
	private int idx = 0;

	public DynamicBytes(int size) {
		data = new byte[size];
	}

	private void expandIfNessarry(int more) {
		if (idx + more >= data.length) {
			int after = (int) ((idx + more) * 1.33);
			byte[] tmp = new byte[after];
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
		return write(str, HttpUtils.ASCII);
	}

	public DynamicBytes write(String str, Charset c) {
		byte[] bs = str.getBytes(c);
		return write(bs, 0, bs.length);
	}

}
