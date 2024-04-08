package org.httpkit.client;

import org.httpkit.HttpMethod;

import java.util.Map;

public class RequestConfig {
    public static String DEFAULT_USER_AGENT = "http-kit/2.0";

    final int connTimeout;
    final int idleTimeout;
    final int keepAlive;
    final Object body;
    final Map<String, Object> headers;
    final HttpMethod method;
    final String proxy_url;
    final boolean tunnel;
    final boolean autoCompression;

    public RequestConfig(HttpMethod method, Map<String, Object> headers, Object body,
                         int connTimeoutMs, int idleTimeoutMs, int keepAliveMs,
                         String proxy_url, boolean tunnel, boolean autoCompression) {
        this.connTimeout = connTimeoutMs;
        this.idleTimeout = idleTimeoutMs;
        this.keepAlive = keepAliveMs;
        this.headers = headers;
        this.body = body;
        this.method = method;
        this.proxy_url = proxy_url;
        this.tunnel = tunnel;
        this.autoCompression = autoCompression;
    }

    // needed for instrumentation
    public void setHeader(String name, Object value) {
        headers.put(name, value);
    }

    public HttpMethod getMethod() {
        return method;
    }

    public RequestConfig() { // for easy test only
        this(HttpMethod.GET, null, null, 40000, 40000, -1, null, false, false);
    }
}
