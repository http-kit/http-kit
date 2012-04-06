package me.shenfeng.http.server;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.BAD_REQUEST;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.SELECT_TIMEOUT;
import static me.shenfeng.http.HttpUtils.UTF_8;
import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.encodeResponseHeader;
import static me.shenfeng.http.HttpUtils.readAll;
import static me.shenfeng.http.server.HttpReqeustDecoder.State.ALL_READ;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.codec.DynamicBytes;
import me.shenfeng.http.codec.LineTooLargeException;
import me.shenfeng.http.codec.ProtocolException;
import me.shenfeng.http.server.HttpReqeustDecoder.State;

public class HttpServer {
	private static void doWrite(SelectionKey key) throws IOException {
		ServerAtta atta = (ServerAtta) key.attachment();
		SocketChannel ch = (SocketChannel) key.channel();
		HttpReqeustDecoder decoder = atta.decoder;
		ByteBuffer header = atta.respHeader;
		ByteBuffer body = atta.respBody;

		if (body == null) {
			ch.write(header);
			if (!header.hasRemaining()) {
				if (decoder.request.isKeepAlive()) {
					decoder.reset();
					key.interestOps(OP_READ);
				} else {
					ch.close();
				}
			}
		} else {
			if (header.hasRemaining()) {
				ch.write(new ByteBuffer[] { header, body });
			} else {
				ch.write(body);
			}
			if (!body.hasRemaining()) {
				if (decoder.request.isKeepAlive()) {
					decoder.reset();
					key.interestOps(OP_READ);
				} else {
					ch.close();
				}
			}
		}
	}

	private IHandler handler;
	private int port;
	private String ip;
	private Selector selector;

	ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();

	// shared, single thread
	private ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

	public HttpServer(String ip, int port, IHandler handler) {
		this.handler = handler;
		this.ip = ip;
		this.port = port;
	}

	void accept(SelectionKey key, Selector selector) throws IOException {
		ServerSocketChannel ch = (ServerSocketChannel) key.channel();
		SocketChannel s;
		while ((s = ch.accept()) != null) {
			s.configureBlocking(false);
			s.register(selector, OP_READ, new ServerAtta());
		}
	}

	void bind(String ip, int port) throws IOException {
		selector = Selector.open();
		ServerSocketChannel serverChannel = ServerSocketChannel.open();
		serverChannel.configureBlocking(false);
		InetSocketAddress addr = new InetSocketAddress(ip, port);
		serverChannel.socket().bind(addr);
		serverChannel.register(selector, OP_ACCEPT);
		System.out.println("start server " + ip + "@" + port);
	}

	// maybe in another thread
	private void buildResponse(SelectionKey key, int status,
			Map<String, String> headers, Object body) {
		ServerAtta atta = (ServerAtta) key.attachment();
		if (body == null) {
			atta.respBody = null;
			headers.put(CONTENT_LENGTH, "0");
		} else {
			try {
				if (body instanceof String) {
					byte[] b = ((String) body).getBytes(UTF_8);
					atta.respBody = ByteBuffer.wrap(b);
					headers.put(CONTENT_LENGTH, b.length + "");
				} else if (body instanceof InputStream) {
					DynamicBytes b = readAll((InputStream) body);
					atta.respBody = ByteBuffer.wrap(b.get(), 0, b.getCount());
					headers.put(CONTENT_LENGTH, b.getCount() + "");
				} else if (body instanceof File) {
					File f = (File) body;
					long length = f.length();
					headers.put(CONTENT_LENGTH, length + "");
					if (length < 128 * 1024) { // 128k
						byte[] b = readAll(f, (int) length);
						atta.respBody = ByteBuffer.wrap(b);
					} else {
						RandomAccessFile raf = new RandomAccessFile(f, "r");
						atta.respBody = raf.getChannel().map(READ_ONLY, 0,
								length);
					}
				}
			} catch (IOException e) {
				byte[] b = e.getMessage().getBytes(ASCII);
				status = 500;
				headers.clear();
				headers.put(CONTENT_LENGTH, b.length + "");
				atta.respBody = ByteBuffer.wrap(b);
			}
		}
		DynamicBytes bytes = encodeResponseHeader(status, headers);
		atta.respHeader = ByteBuffer.wrap(bytes.get(), 0, bytes.getCount());
		pendings.offer(key);
		selector.wakeup();
	}

	private void doRead(final SelectionKey key) throws IOException {
		final ServerAtta atta = (ServerAtta) key.attachment();
		SocketChannel ch = (SocketChannel) key.channel();
		try {
			while (true) {
				buffer.clear(); // clear for read
				int read = ch.read(buffer);
				if (read == -1) {
					// remote entity shut the socket down cleanly.
					closeQuiety(ch);
					break;
				} else if (read > 0) {
					buffer.flip(); // flip for read
					HttpReqeustDecoder decoder = atta.decoder;
					State s = decoder.decode(buffer);
					if (s == ALL_READ) {
						handler.handle(decoder.request,
								new IResponseCallback() {
									public void run(int status,
											Map<String, String> headers,
											Object body) {
										buildResponse(key, status, headers,
												body);
									}
								});
						break;
					}
				}
			}
		} catch (IOException e) {
			closeQuiety(ch); // the remote forcibly closed the connection
		} catch (ProtocolException e) {
			closeQuiety(ch);
		} catch (LineTooLargeException e) {
			atta.respBody = null;
			atta.respHeader = ByteBuffer.wrap(BAD_REQUEST);
			key.interestOps(OP_WRITE);
		} catch (RequestTooLargeException e) {
			e.printStackTrace();
		}
	}

	public void start() throws IOException {
		bind(ip, port);
		SelectionKey key;
		while (true) {
			while ((key = pendings.poll()) != null) {
				if (key.isValid()) {
					key.interestOps(SelectionKey.OP_WRITE);
				}
			}
			int select = selector.select(SELECT_TIMEOUT);
			if (select <= 0) {
				continue;
			}
			Set<SelectionKey> selectedKeys = selector.selectedKeys();
			Iterator<SelectionKey> ite = selectedKeys.iterator();
			while (ite.hasNext()) {
				key = ite.next();
				if (key.isValid()) {
					if (key.isAcceptable()) {
						accept(key, selector);
					} else if (key.isReadable()) {
						doRead(key);
					} else if (key.isWritable()) {
						doWrite(key);
					}
				}
			}
			selectedKeys.clear();
		}
	}

	public void stop() throws IOException {
		if (selector != null) {
			selector.close();
		}
	}
}
