package me.shenfeng.http.server;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static me.shenfeng.http.server.ServerConstant.BODY;
import static me.shenfeng.http.server.ServerConstant.CHARACTER_ENCODING;
import static me.shenfeng.http.server.ServerConstant.CONTENT_LENGTH;
import static me.shenfeng.http.server.ServerConstant.CONTENT_TYPE;
import static me.shenfeng.http.server.ServerConstant.HEADERS;
import static me.shenfeng.http.server.ServerConstant.HTTP;
import static me.shenfeng.http.server.ServerConstant.KEEP_ALIVE;
import static me.shenfeng.http.server.ServerConstant.M_DELETE;
import static me.shenfeng.http.server.ServerConstant.M_GET;
import static me.shenfeng.http.server.ServerConstant.M_POST;
import static me.shenfeng.http.server.ServerConstant.M_PUT;
import static me.shenfeng.http.server.ServerConstant.QUERY_STRING;
import static me.shenfeng.http.server.ServerConstant.REMOTE_ADDR;
import static me.shenfeng.http.server.ServerConstant.REQUEST_METHOD;
import static me.shenfeng.http.server.ServerConstant.SCHEME;
import static me.shenfeng.http.server.ServerConstant.SERVER_NAME;
import static me.shenfeng.http.server.ServerConstant.SERVER_PORT;
import static me.shenfeng.http.server.ServerConstant.STATUS;
import static me.shenfeng.http.server.ServerConstant.URI;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class Handler implements IHandler {

	final ExecutorService execs;
	final IFn f;

	public Handler(int thread, IFn f) {
		execs = newFixedThreadPool(thread, new PrefixThreafFactory("worker-"));
		this.f = f;
	}

	public static IPersistentMap buildRequestMap(HttpRequest req) {

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(SERVER_PORT, req.getServerPort());
		m.put(SERVER_NAME, req.getServerName());
		m.put(REMOTE_ADDR, req.getRemoteAddr());
		m.put(URI, req.getUri());
		m.put(QUERY_STRING, req.getQueryString());
		m.put(SCHEME, HTTP); // only http is supported

		switch (req.getMethod()) {
		case DELETE:
			m.put(REQUEST_METHOD, M_DELETE);
			break;
		case GET:
			m.put(REQUEST_METHOD, M_GET);
			break;
		case POST:
			m.put(REQUEST_METHOD, M_POST);
			break;
		case PUT:
			m.put(REQUEST_METHOD, M_PUT);
			break;
		}

		m.put(HEADERS, PersistentArrayMap.create(req.getHeaders()));
		m.put(CONTENT_TYPE, req.getContentType());
		m.put(CONTENT_LENGTH, req.getContentLength());
		m.put(CHARACTER_ENCODING, req.getCharactorEncoding());
		m.put(BODY, req.getBody());
		m.put(KEEP_ALIVE, req.isKeepAlive());
		return PersistentArrayMap.create(m);
	}

	public void handle(final HttpRequest req, final IResponseCallback cb) {
		execs.submit(new Runnable() {
			@SuppressWarnings("rawtypes")
			public void run() {
				try {
					Map resp = (Map) f.invoke(buildRequestMap(req));
					int status = ((Long) resp.get(STATUS)).intValue();
					@SuppressWarnings("unchecked")
					Map<String, Object> headers = (Map) resp.get(HEADERS);
					Object body = resp.get(BODY);
					cb.run(status, headers, body);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	public void close() {
		execs.shutdownNow();
	}
}
