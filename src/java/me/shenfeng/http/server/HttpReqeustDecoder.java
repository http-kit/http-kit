package me.shenfeng.http.server;

import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.LF;
import static me.shenfeng.http.HttpUtils.findEndOfString;
import static me.shenfeng.http.HttpUtils.findNonWhitespace;
import static me.shenfeng.http.HttpUtils.findWhitespace;
import static me.shenfeng.http.HttpUtils.getChunkSize;
import static me.shenfeng.http.codec.HttpVersion.HTTP_1_0;
import static me.shenfeng.http.codec.HttpVersion.HTTP_1_1;
import static me.shenfeng.http.server.HttpReqeustDecoder.State.ALL_READ;
import static me.shenfeng.http.server.HttpReqeustDecoder.State.READ_INITIAL;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.codec.HttpMethod;
import me.shenfeng.http.codec.HttpVersion;
import me.shenfeng.http.codec.LineTooLargeException;
import me.shenfeng.http.codec.ProtocolException;

public class HttpReqeustDecoder {

	public static enum State {
		PROTOCOL_ERROR, ALL_READ, READ_INITIAL, READ_HEADER, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_FOOTER, READ_CHUNK_DELIMITER,
	}

	static final int MAX_LINE = 2048;

	byte[] lineBuffer = new byte[MAX_LINE];
	int lineBufferCnt = 0;

	HttpRequest request;
	private Map<String, String> headers = new TreeMap<String, String>();
	int readRemaining = 0;
	State state;
	byte[] content;
	int readContent = 0;
	private int maxBody;

	public HttpReqeustDecoder(int maxBody) {
		this.maxBody = maxBody;
		state = READ_INITIAL;
	}

	private void createRequest(String sb) throws ProtocolException {
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

			HttpMethod method = HttpMethod.GET;
			String m = sb.substring(aStart, aEnd).toUpperCase();
			if (m.equals("GET")) {
				method = HttpMethod.GET;
			} else if (m.equals("POST")) {
				method = HttpMethod.POST;
			} else if (m.equals("PUT")) {
				method = HttpMethod.PUT;
			} else if (m.equals("DELETE")) {
				method = HttpMethod.DELETE;
			}
			HttpVersion version = HTTP_1_1;
			if ("HTTP/1.0".equals(sb.substring(cStart, cEnd))) {
				version = HTTP_1_0;
			}
			request = new HttpRequest(method, sb.substring(bStart, bEnd),
					version);
		} else {
			throw new ProtocolException();
		}
	}

	public State decode(ByteBuffer buffer) throws LineTooLargeException,
			ProtocolException, RequestTooLargeException {
		String line;
		while (buffer.hasRemaining() && state != ALL_READ) {
			switch (state) {
			case READ_INITIAL:
				line = readLine(buffer);
				if (line != null) {
					createRequest(line);
					state = State.READ_HEADER;
				}
				break;
			case READ_HEADER:
				state = readHeaders(buffer);
				break;
			case READ_CHUNK_SIZE:
				line = readLine(buffer);
				if (line != null) {
					readRemaining = getChunkSize(line);
					if (readRemaining == 0) {
						state = State.READ_CHUNK_FOOTER;
					} else {
						throwIfBodyIsTooLarge(readRemaining);
						if (content == null) {
							content = new byte[readRemaining];
						} else if (content.length - readContent < readRemaining) {
							byte[] tmp = new byte[readRemaining + readContent];
							System.arraycopy(content, 0, tmp, 0, readContent);
							content = tmp;
						}
						state = State.READ_CHUNKED_CONTENT;
					}
				}
				break;
			case READ_FIXED_LENGTH_CONTENT:
				readFixedLength(buffer);
				if (readRemaining == 0) {
					finish();
				}
				break;
			case READ_CHUNKED_CONTENT:
				readFixedLength(buffer);
				if (readRemaining == 0) {
					state = State.READ_CHUNK_DELIMITER;
				}
				break;
			case READ_CHUNK_FOOTER:
				readEmptyLine(buffer);
				finish();
				break;
			case READ_CHUNK_DELIMITER:
				readEmptyLine(buffer);
				state = State.READ_CHUNK_SIZE;
				break;
			}
		}
		return state;
	}

	private void finish() {
		state = ALL_READ;
		request.setBody(content, readContent);
	}

	void readEmptyLine(ByteBuffer buffer) {
		byte b = buffer.get();
		if (b == CR) {
			buffer.get(); // should be LF
		} else if (b == LF) {
		}
	}

	void readFixedLength(ByteBuffer buffer) {
		int toRead = Math.min(buffer.remaining(), readRemaining);
		buffer.get(content, readContent, toRead);
		readRemaining -= toRead;
		readContent += toRead;
	}

	private State readHeaders(ByteBuffer buffer) throws LineTooLargeException,
			RequestTooLargeException {
		String line = readLine(buffer);
		while (line != null && !line.isEmpty()) {
			splitAndAddHeader(line);
			line = readLine(buffer);
		}
		request.setHeaders(headers);

		String te = headers.get("transfer-encoding");
		if ("chunked".equals(te)) {
			return State.READ_CHUNK_SIZE;
		} else {
			String cl = headers.get("content-length");
			if (cl != null) {
				try {
					readRemaining = Integer.parseInt(cl);
					throwIfBodyIsTooLarge(readRemaining);
					content = new byte[readRemaining];
					return State.READ_FIXED_LENGTH_CONTENT;
				} catch (NumberFormatException e) {
					return State.PROTOCOL_ERROR;
				}
			}
			return State.ALL_READ;
		}
	}

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
				if (lineBufferCnt == MAX_LINE - 2) {
					throw new LineTooLargeException();
				}
				lineBuffer[lineBufferCnt] = b;
				++lineBufferCnt;
			}
		}
		String line = null;
		if (!more) {
			line = new String(lineBuffer, 0, lineBufferCnt);
			lineBufferCnt = 0;
		}
		return line;
	};

	public void reset() {
		state = State.READ_INITIAL;
	}

	void splitAndAddHeader(String sb) {
		final int length = sb.length();
		int nameStart;
		int nameEnd;
		int colonEnd;
		int valueStart;
		int valueEnd;

		nameStart = findNonWhitespace(sb, 0);
		for (nameEnd = nameStart; nameEnd < length; nameEnd++) {
			char ch = sb.charAt(nameEnd);
			if (ch == ':' || Character.isWhitespace(ch)) {
				break;
			}
		}

		for (colonEnd = nameEnd; colonEnd < length; colonEnd++) {
			if (sb.charAt(colonEnd) == ':') {
				colonEnd++;
				break;
			}
		}

		valueStart = findNonWhitespace(sb, colonEnd);
		valueEnd = findEndOfString(sb);

		// TODO make sure does not break anything
		// lowercase for easier processing
		String key = sb.substring(nameStart, nameEnd).toLowerCase();
		String value = sb.substring(valueStart, valueEnd);
		headers.put(key, value);
	}

	private void throwIfBodyIsTooLarge(int body)
			throws RequestTooLargeException {
		if (body > maxBody) {
			throw new RequestTooLargeException("rquest body " + body
					+ "; max request body " + maxBody);
		}
	}
}
