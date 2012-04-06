package me.shenfeng.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import me.shenfeng.http.codec.DynamicBytes;
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
	public static final int ABORT = -1;

	public static final int TIMEOUT_CHECK_INTEVAL = 3000;

	public static final String USER_AGENT = "User-Agent";

	public static final String ACCEPT = "Accept";

	public static final String ACCEPT_ENCODING = "Accept-Encoding";

	public static final String HOST = "Host";

	public static final String CONTENT_LENGTH = "Content-Length";

	public static final byte[] BAD_REQUEST;

	static {
		byte[] body = "bad request".getBytes(ASCII);
		Map<String, String> headers = new TreeMap<String, String>();
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

	public static DynamicBytes encodeResponseHeader(int status,
			Map<String, String> headers) {
		DynamicBytes bytes = new DynamicBytes(196);
		byte[] bs = HttpStatus.valueOf(status).getResponseIntialLineBytes();
		bytes.write(bs, 0, bs.length);
		Iterator<Entry<String, String>> ite = headers.entrySet().iterator();
		while (ite.hasNext()) {
			Entry<String, String> e = ite.next();
			bytes.write(e.getKey());
			bytes.write(COLON);
			bytes.write(SP);
			bytes.write(e.getValue());
			bytes.write(CR);
			bytes.write(LF);
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
		FileInputStream fs = new FileInputStream(f);
		int offset = 0;
		while (offset < length) {
			offset += fs.read(bytes, offset, length - offset);
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
		return bytes;
	}

	public static String[] splitInitialLine(String sb) {
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

		return new String[] { sb.substring(aStart, aEnd),
				sb.substring(bStart, bEnd),
				cStart < cEnd ? sb.substring(cStart, cEnd) : "" };
	}

}
