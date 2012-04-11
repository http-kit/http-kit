package me.shenfeng.http.client;

public enum RequestState {
	DIRECT_CONNECTING, SOCKS_CONNECTING, SOCKS_CONN_SENT, SOCKS_CONNECTED, FINISHED
}