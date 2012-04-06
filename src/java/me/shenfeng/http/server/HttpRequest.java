package me.shenfeng.http.server;

import static me.shenfeng.http.codec.HttpVersion.HTTP_1_1;

import java.io.InputStream;
import java.util.Map;

import me.shenfeng.http.codec.HttpMethod;
import me.shenfeng.http.codec.HttpVersion;

import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;

public class HttpRequest {
	private int serverPort;
	private String host;
	private String remoteAddr;
	private String queryString;
	private String uri;
	private HttpMethod method;
	private Map<String, String> headers;
	private HttpVersion version;
	private int contentLength = 0;
	private byte[] body;

	public HttpRequest(HttpMethod method, String url, HttpVersion version) {
		this.method = method;
		this.version = version;
		int idx = url.indexOf('?');
		if (idx > 0) {
			uri = url.substring(0, idx);
			queryString = uri.substring(idx);
		} else {
			uri = url;
			queryString = null;
		}
	}

	public InputStream getBody() {
		if (body != null) {
			return new ByteInputStream(body, 0, contentLength);
		}
		return null;
	}

	public String getCharactorEncoding() {
		return "utf8";
	}

	public int getContentLength() {
		return contentLength;
	}

	public String getContentType() {
		return null;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public String getHost() {
		return host;
	}

	public HttpMethod getMethod() {
		return method;
	}

	public String getQueryString() {
		return queryString;
	}

	public String getRemoteAddr() {
		return remoteAddr;
	}

	public String getScheme() {
		return "http";
	}

	public int getServerPort() {
		return serverPort;
	}

	public String getUri() {
		return uri;
	}

	public boolean isKeepAlive() {
		// header keys are all lowercased
		String con = headers.get("connection");
		if (con != null) {
			con = con.toLowerCase();
		}

		return (version == HTTP_1_1 && !"close".equals(con))
				|| "keep-alive".equals(con);

	}

	public void setBody(byte[] body, int count) {
		this.body = body;
		this.contentLength = count;
	}

	public void setHeaders(Map<String, String> headers) {
		String h = headers.get("host");
		if (h != null) {
			int idx = h.indexOf(':');
			if (idx != -1) {
				this.host = h.substring(0, idx);
				serverPort = Integer.valueOf(h.substring(idx + 1));
			} else {
				this.host = h;
			}
		}
		this.headers = headers;
	}
}
