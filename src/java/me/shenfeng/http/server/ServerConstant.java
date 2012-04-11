package me.shenfeng.http.server;

import static clojure.lang.Keyword.intern;
import clojure.lang.Keyword;

public class ServerConstant {

	public static final Keyword SERVER_PORT = intern("server-port");
	public static final Keyword SERVER_NAME = intern("server-name");
	public static final Keyword REMOTE_ADDR = intern("remote-addr");
	public static final Keyword URI = intern("uri");
	public static final Keyword QUERY_STRING = intern("query-string");
	public static final Keyword SCHEME = intern("scheme");
	public static final Keyword REQUEST_METHOD = intern("request-method");
	public static final Keyword HEADERS = intern("headers");
	public static final Keyword CONTENT_TYPE = intern("content-type");
	public static final Keyword CONTENT_LENGTH = intern("content-length");
	public static final Keyword CHARACTER_ENCODING = intern("character-encoding");
	public static final Keyword BODY = intern("body");
	public static final Keyword KEEP_ALIVE = intern("keep_alive");

	public static final Keyword M_GET = intern("get");
	public static final Keyword M_POST = intern("post");
	public static final Keyword M_DELETE = intern("delete");
	public static final Keyword M_PUT = intern("put");

	public static final Keyword HTTP = intern("http");

	public static final Keyword STATUS = intern("status");
}
