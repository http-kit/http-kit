package me.shenfeng.http.client;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientAtta {

	RequestState state;
	Proxy proxy;
	InetSocketAddress addr;
	HttpClientDecoder decoder;
	ByteBuffer request;
	long lastActiveTime;

	SocketChannel ch;

	public ClientAtta(ByteBuffer request, InetSocketAddress server,
			IEventListener handler, Proxy proxy) {
		this.proxy = proxy;
		this.addr = server;
		decoder = new HttpClientDecoder(handler);
		this.request = request;
		if (proxy.type() == Type.SOCKS) {
			state = RequestState.SOCKS_CONNECTING;
		} else {
			state = RequestState.DIRECT_CONNECTING;
		}
	}
}
