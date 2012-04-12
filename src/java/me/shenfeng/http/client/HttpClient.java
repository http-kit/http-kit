package me.shenfeng.http.client;

import static java.lang.System.currentTimeMillis;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static me.shenfeng.http.HttpUtils.ACCEPT;
import static me.shenfeng.http.HttpUtils.ACCEPT_ENCODING;
import static me.shenfeng.http.HttpUtils.BUFFER_SIZE;
import static me.shenfeng.http.HttpUtils.HOST;
import static me.shenfeng.http.HttpUtils.SELECT_TIMEOUT;
import static me.shenfeng.http.HttpUtils.TIMEOUT_CHECK_INTEVAL;
import static me.shenfeng.http.HttpUtils.USER_AGENT;
import static me.shenfeng.http.HttpUtils.closeQuiety;
import static me.shenfeng.http.HttpUtils.encodeGetRequest;
import static me.shenfeng.http.HttpUtils.getServerAddr;
import static me.shenfeng.http.client.ClientDecoderState.ABORTED;
import static me.shenfeng.http.client.ClientDecoderState.ALL_READ;
import static me.shenfeng.http.client.RequestState.DIRECT_CONNECTING;
import static me.shenfeng.http.client.RequestState.FINISHED;
import static me.shenfeng.http.client.RequestState.SOCKS_CONNECTING;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.codec.LineTooLargeException;

public final class HttpClient {

	private class SelectorLoopThread extends Thread {
		public void run() {
			setName("http-client");
			try {
				startLoop();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	// shared, single thread
	private ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

	private final HttpClientConfig config;

	private ConcurrentLinkedQueue<ClientAtta> pendings = new ConcurrentLinkedQueue<ClientAtta>();
	private long lastTimeoutCheckTime;

	private LinkedList<ClientAtta> clients = new LinkedList<ClientAtta>();

	private volatile boolean running = true;

	private Selector selector;

	public HttpClient(HttpClientConfig config) throws IOException {
		this.config = config;
		selector = Selector.open();
		lastTimeoutCheckTime = System.currentTimeMillis();
		SelectorLoopThread thread = new SelectorLoopThread();
		thread.setDaemon(true);
		thread.start();
	}

	private void clearTimeouted(long currentTime) {
		Iterator<ClientAtta> ite = clients.iterator();
		while (ite.hasNext()) {
			ClientAtta client = ite.next();
			RequestState s = client.state;
			if (s == DIRECT_CONNECTING || s == SOCKS_CONNECTING) {
				// connecting timeout
				if (config.connTimeOutMs + client.lastActiveTime < currentTime) {
					ite.remove();
					closeQuiety(client.ch);
					TimeoutException to = new TimeoutException("connect to "
							+ client.addr + " timeout after "
							+ config.connTimeOutMs + "ms");
					client.decoder.listener.onThrowable(to);
				}
			} else if (s == FINISHED) {
				ite.remove();
			} else if (config.readingTimeoutMs + client.lastActiveTime < currentTime) {
				// reading response timeout
				ite.remove();
				closeQuiety(client.ch);
				TimeoutException to = new TimeoutException("reading from "
						+ client.addr + " timeout after "
						+ config.readingTimeoutMs + "ms");
				client.decoder.listener.onThrowable(to);
			}
		}
	}

	private void doRead(SelectionKey key) {
		ClientAtta atta = (ClientAtta) key.attachment();
		HttpClientDecoder decoder = atta.decoder;
		SocketChannel ch = (SocketChannel) key.channel();
		try {
			while (true) {
				buffer.clear();
				int read = ch.read(buffer);
				if (read == -1) {
					// remote entity shut the socket down cleanly.
					closeQuiety(ch);
					break;
				} else if (read > 0) {
					buffer.flip();
					ClientDecoderState state = decoder.decode(buffer);
					if (state == ALL_READ || state == ABORTED) {
						closeQuiety(ch);
						break;
					}
				} else {
					break;
				}
			}
		} catch (IOException e) {
			// the remote forcibly closed the connection
			closeQuiety(ch);
		} catch (LineTooLargeException e) {
			closeQuiety(ch);
		} catch (Exception e) {
			e.printStackTrace();
			closeQuiety(ch);
		}
	}

	private void doWrite(SelectionKey key) throws IOException {
		ClientAtta atta = (ClientAtta) key.attachment();
		SocketChannel ch = (SocketChannel) key.channel();
		ByteBuffer request = atta.request;
		ch.write(request);
		if (!request.hasRemaining()) {
			key.interestOps(OP_READ);
		}
	}

	public void get(String url, Map<String, String> headers, Proxy proxy,
			IEventListener cb) throws URISyntaxException, UnknownHostException {
		URI uri = new URI(url);

		headers.put(HOST, uri.getHost());
		headers.put(ACCEPT, "*/*");
		if (headers.get(USER_AGENT) == null) // allow override
			headers.put(USER_AGENT, config.userAgent);
		headers.put(ACCEPT_ENCODING, "gzip, deflate");

		InetSocketAddress addr = getServerAddr(uri);

		// HTTP proxy is not supported now
		String path = HttpUtils.getPath(uri);

		ByteBuffer request = encodeGetRequest(path, headers);
		pendings.offer(new ClientAtta(request, addr, cb, proxy));
		selector.wakeup();
	}

	public void post(String uri, Map<String, String> headers, Proxy proxy,
			IEventListener cb) {

	}

	private void processPendings(long currentTime) throws IOException {
		ClientAtta job;
		while ((job = pendings.poll()) != null) {
			SocketChannel ch = SocketChannel.open();
			job.ch = ch; // save for use when timeout
			job.lastActiveTime = currentTime;
			ch.configureBlocking(false);
			ch.register(selector, OP_CONNECT, job);
			ch.connect(job.addr);
			clients.add(job);
		}
	}

	private void startLoop() throws IOException {
		SelectionKey key;
		SocketChannel ch;
		while (running) {
			long currentTime = currentTimeMillis();
			processPendings(currentTime);
			int select = selector.select(SELECT_TIMEOUT);
			if (select <= 0) {
				continue;
			}

			Set<SelectionKey> selectedKeys = selector.selectedKeys();
			Iterator<SelectionKey> ite = selectedKeys.iterator();
			while (ite.hasNext()) {
				key = ite.next();
				if (!key.isValid()) {
					continue;
				}
				if (key.isConnectable()) {
					ch = (SocketChannel) key.channel();
					if (ch.finishConnect()) {
						ClientAtta attr = (ClientAtta) key.attachment();
						attr.lastActiveTime = currentTime;
						key.interestOps(SelectionKey.OP_WRITE);
					}
				} else if (key.isWritable()) {
					doWrite(key);
				} else if (key.isReadable()) {
					doRead(key);
				}
			}
			if (currentTime - lastTimeoutCheckTime > TIMEOUT_CHECK_INTEVAL) {
				clearTimeouted(currentTime);
				lastTimeoutCheckTime = currentTime;
			}
			selectedKeys.clear();
		}
	}

	public void stop() throws IOException {
		running = false;
		if (selector != null) {
			selector.close();
		}
	}
}
