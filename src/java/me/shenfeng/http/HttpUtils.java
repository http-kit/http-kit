package me.shenfeng.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import clojure.lang.ISeq;
import clojure.lang.Seqable;

import me.shenfeng.http.codec.HttpStatus;

public class HttpUtils {

	public static final Charset ASCII = Charset.forName("US-ASCII");
	public static final Charset UTF_8 = Charset.forName("utf8");
	// Colon ':'
	public static final byte COLON = 58;

	public static final byte CR = 13;

	public static final byte LF = 10;

	public static final int MAX_LINE = 2048;

	public static final int BUFFER_SIZE = 1024 * 64;

	public static final int SELECT_TIMEOUT = 3000;

	// stop processing the response/response
	public static final int ABORT_PROCESSING = -1;

	public static final int TIMEOUT_CHECK_INTEVAL = 3000;

	public static final String USER_AGENT = "User-Agent";

	public static final String ACCEPT = "Accept";

	public static final String ACCEPT_ENCODING = "Accept-Encoding";

	public static final String TRANSFER_ENCODING = "Transfer-Encoding";

	public static final String CHUNKED = "chunked";

	public static final String HOST = "Host";

	public static final String CONTENT_LENGTH = "Content-Length";

	public static final byte[] BAD_REQUEST;

	static {
		byte[] body = "bad request".getBytes(ASCII);
		Map<String, Object> headers = new TreeMap<String, Object>();
		headers.put(CONTENT_LENGTH, body.length + "");
		DynamicBytes db = encodeResponseHeader(400, headers);
		db.write(body, 0, body.length);
		BAD_REQUEST = new byte[db.getCount()];
		System.arraycopy(db.get(), 0, BAD_REQUEST, 0, db.getCount());
	}

	// space ' '
	public static final byte SP = 32;

	public static void closeQuiety(SocketChannel c) {
		try {
			if (c != null) {
				c.close();
			}
		} catch (Exception ignore) {
		}
	}

	public static ByteBuffer encodeGetRequest(String path,
			Map<String, String> headers) {
		DynamicBytes bytes = new DynamicBytes(64 + headers.size() * 48);

		bytes.write("GET").write(SP).write(path).write(SP);
		bytes.write("HTTP/1.1").write(CR).write(LF);
		Iterator<Entry<String, String>> ite = headers.entrySet().iterator();
		while (ite.hasNext()) {
			Entry<String, String> e = ite.next();
			bytes.write(e.getKey()).write(COLON).write(SP).write(e.getValue());
			bytes.write(CR).write(LF);
		}

		bytes.write(CR).write(LF);
		ByteBuffer request = ByteBuffer.wrap(bytes.get(), 0, bytes.getCount());
		return request;
	}

	public static DynamicBytes encodeResponseHeader(int status,
			Map<String, Object> headers) {
		DynamicBytes bytes = new DynamicBytes(196);
		byte[] bs = HttpStatus.valueOf(status).getResponseIntialLineBytes();
		bytes.write(bs, 0, bs.length);
		Iterator<Entry<String, Object>> ite = headers.entrySet().iterator();
		while (ite.hasNext()) {
			Entry<String, Object> e = ite.next();
			String k = e.getKey();
			Object v = e.getValue();
			if (v instanceof String) {
				bytes.write(k);
				bytes.write(COLON);
				bytes.write(SP);
				bytes.write((String) v);
				bytes.write(CR);
				bytes.write(LF);
				// ring spec says it could be a seq
			} else if (v instanceof Seqable) {
				ISeq seq = ((Seqable) v).seq();
				while (seq != null) {
					bytes.write(k);
					bytes.write(COLON);
					bytes.write(SP);
					bytes.write(seq.first().toString());
					bytes.write(CR);
					bytes.write(LF);
					seq = seq.next();
				}
			}
		}

		bytes.write(CR);
		bytes.write(LF);
		return bytes;
	}

	public static int findEndOfString(String sb) {
		int result;
		for (result = sb.length(); result > 0; result--) {
			if (!Character.isWhitespace(sb.charAt(result - 1))) {
				break;
			}
		}
		return result;
	}

	public static int findNonWhitespace(String sb, int offset) {
		int result;
		for (result = offset; result < sb.length(); result++) {
			if (!Character.isWhitespace(sb.charAt(result))) {
				break;
			}
		}
		return result;
	}

	public static int findWhitespace(String sb, int offset) {
		int result;
		for (result = offset; result < sb.length(); result++) {
			if (Character.isWhitespace(sb.charAt(result))) {
				break;
			}
		}
		return result;
	}

	public static int getChunkSize(String hex) {
		hex = hex.trim();
		for (int i = 0; i < hex.length(); i++) {
			char c = hex.charAt(i);
			if (c == ';' || Character.isWhitespace(c)
					|| Character.isISOControl(c)) {
				hex = hex.substring(0, i);
				break;
			}
		}

		return Integer.parseInt(hex, 16);
	}

	public static String getPath(URI uri) {
		String path = uri.getPath();
		String query = uri.getRawQuery();
		if ("".equals(path))
			path = "/";
		if (query == null)
			return path;
		else
			return path + "?" + query;
	}

	public static InetSocketAddress getServerAddr(URI uri)
			throws UnknownHostException {
		int port = uri.getPort();
		if (port == -1) {
			if ("https".equals(uri.getScheme()))
				port = 443;
			else
				port = 80;
		}
		InetAddress host = InetAddress.getByName(uri.getHost());
		return new InetSocketAddress(host, port);

	}

	public static byte[] readAll(File f, int length) throws IOException {
		byte[] bytes = new byte[length];
		FileInputStream fs = null;
		try {
			fs = new FileInputStream(f);
			int offset = 0;
			while (offset < length) {
				offset += fs.read(bytes, offset, length - offset);
			}
		} finally {
			if (fs != null) {
				try {
					fs.close();
				} catch (Exception ignore) {
				}
			}
		}
		return bytes;
	}

	public static DynamicBytes readAll(InputStream is) throws IOException {
		DynamicBytes bytes = new DynamicBytes(1024);
		byte[] buffer = new byte[4096];
		int read = 0;
		while ((read = is.read(buffer)) != -1) {
			bytes.write(buffer, 0, read);
		}
		is.close();
		return bytes;
	}
}
