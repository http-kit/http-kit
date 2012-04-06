package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.BUFFER_SIZE;
import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.LF;
import static me.shenfeng.http.HttpUtils.MAX_LINE;
import static me.shenfeng.http.HttpUtils.findEndOfString;
import static me.shenfeng.http.HttpUtils.findNonWhitespace;
import static me.shenfeng.http.HttpUtils.getChunkSize;
import static me.shenfeng.http.client.IEventListener.ABORT;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.codec.LineTooLargeException;

public class HttpClientDecoder {

	public static enum State {
		ALL_READ, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_HEADER, READ_INITIAL, ABORTED, READ_VARIABLE_LENGTH_CONTENT
	}

	private Map<String, String> headers = new TreeMap<String, String>();
	private IEventListener listener;
	// single threaded, shared ok
	private static byte[] content = new byte[BUFFER_SIZE];
	byte[] lineBuffer = new byte[MAX_LINE];
	int lineBufferCnt = 0;
	int readRemaining = 0;
	State state = State.READ_INITIAL;
	private boolean decodingRequest;

	public HttpClientDecoder(IEventListener handler, boolean decodingRequest) {
		this.listener = handler;
		this.decodingRequest = decodingRequest;
	}

	private void complete() {
		state = State.ALL_READ;
		listener.onCompleted();
	}

	public State decode(ByteBuffer buffer) throws LineTooLargeException {
		String line;
		int toRead;
		while (buffer.hasRemaining() && state != State.ALL_READ) {
			switch (state) {
			case READ_INITIAL:
				line = readLine(buffer);
				if (line != null) {
					if (listener.onInitialLineReceived(line) != ABORT) {
						state = State.READ_HEADER;
					} else {
						state = State.ABORTED;
					}
				}
				break;
			case READ_HEADER:
				readHeaders(buffer);
				break;
			case READ_CHUNK_SIZE:
				line = readLine(buffer);
				if (line != null) {
					readRemaining = getChunkSize(line);
					if (readRemaining == 0) {
						state = State.READ_CHUNK_FOOTER;
					} else {
						state = State.READ_CHUNKED_CONTENT;
					}
				}
				break;
			case READ_FIXED_LENGTH_CONTENT:
				toRead = Math.min(buffer.remaining(), readRemaining);
				buffer.get(content, 0, toRead);
				if (listener.onBodyReceived(content, toRead) == ABORT) {
					state = State.ABORTED;
				} else {
					readRemaining -= toRead;
					if (readRemaining == 0) {
						listener.onCompleted();
						state = State.ALL_READ;
					}
				}
				break;
			case READ_CHUNKED_CONTENT:
				toRead = Math.min(buffer.remaining(), readRemaining);
				buffer.get(content, 0, toRead);
				if (listener.onBodyReceived(content, toRead) == ABORT) {
					state = State.ABORTED;
				} else {
					readRemaining -= toRead;
					if (readRemaining == 0) {
						state = State.READ_CHUNK_DELIMITER;
					}
				}
				break;
			case READ_CHUNK_FOOTER:
				readEmptyLine(buffer);
				listener.onCompleted();
				state = State.ALL_READ;
				break;
			case READ_CHUNK_DELIMITER:
				readEmptyLine(buffer);
				state = State.READ_CHUNK_SIZE;
				break;
			}
		}
		return state;
	}

	public IEventListener getListener() {
		return listener;
	}

	void readEmptyLine(ByteBuffer buffer) {
		byte b = buffer.get();
		if (b == CR) {
			buffer.get(); // should be LF
		} else if (b == LF) {
		}
	}

	private void readHeaders(ByteBuffer buffer) throws LineTooLargeException {
		String line = readLine(buffer);
		while (line != null && !line.isEmpty()) {
			splitAndAddHeader(line);
			line = readLine(buffer);
		}
		if (listener.onHeadersReceived(headers) != ABORT) {
			String te = headers.get("Transfer-Encoding");
			if ("chunked".equals(te)) {
				state = State.READ_CHUNK_SIZE;
			} else {
				String cl = headers.get("Content-Length");
				if (cl != null) {
					readRemaining = Integer.parseInt(cl);
					if (readRemaining == 0) {
						complete();
					} else {
						state = State.READ_FIXED_LENGTH_CONTENT;
					}
				} else if (decodingRequest) {
					complete();
				} else {
					state = State.READ_VARIABLE_LENGTH_CONTENT;
				}
			}
		} else {
			state = State.ABORTED;
		}
	};

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
				lineBuffer[lineBufferCnt] = b;
				++lineBufferCnt;
				if (lineBufferCnt >= MAX_LINE) {
					throw new LineTooLargeException();
				}
			}
		}
		String line = null;
		if (!more) {
			line = new String(lineBuffer, 0, lineBufferCnt);
			lineBufferCnt = 0;
		}
		return line;
	}

	public void reset() {
		headers.clear();
		state = State.READ_INITIAL;
	}

	void splitAndAddHeader(String line) {
		final int length = line.length();
		int nameStart;
		int nameEnd;
		int colonEnd;
		int valueStart;
		int valueEnd;

		nameStart = findNonWhitespace(line, 0);
		for (nameEnd = nameStart; nameEnd < length; nameEnd++) {
			char ch = line.charAt(nameEnd);
			if (ch == ':' || Character.isWhitespace(ch)) {
				break;
			}
		}

		for (colonEnd = nameEnd; colonEnd < length; colonEnd++) {
			if (line.charAt(colonEnd) == ':') {
				colonEnd++;
				break;
			}
		}

		valueStart = findNonWhitespace(line, colonEnd);
		valueEnd = findEndOfString(line);

		String key = line.substring(nameStart, nameEnd);
		String value = line.substring(valueStart, valueEnd);
		headers.put(key, value);
	}
}
