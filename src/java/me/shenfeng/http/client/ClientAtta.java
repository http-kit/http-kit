package me.shenfeng.http.client;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientAtta {

	public static enum ClientState {
		DIRECT_CONNECTING, SOCKS_CONNECTING, SOCKS_CONN_SENT, SOCKS_CONNECTED
	}

	ClientState state;
	Proxy proxy;
	InetSocketAddress addr;
	HttpClientDecoder decoder;
	ByteBuffer request;
	IEventListener handler;
	long lastActiveTime;

	SocketChannel ch;

	public ClientAtta(Proxy proxy, InetSocketAddress server,
			IEventListener handler, ByteBuffer request) {
		this.proxy = proxy;
		this.addr = server;
		decoder = new HttpClientDecoder(handler, false);
		this.request = request;
		this.handler = handler;
		if (proxy.type() == Type.SOCKS) {
			state = ClientState.SOCKS_CONNECTING;
		} else {
			state = ClientState.DIRECT_CONNECTING;
		}
	}
}
