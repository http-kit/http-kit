package org.httpkit.client;

import org.httpkit.HttpMethod;

import java.util.Map;

public class RequestConfig {
    public static String DEFAULT_USER_AGENT = "http-kit/2.0";

    final int timeout;
    final int keepAlive;
    final Object body;
    final Map<String, Object> headers;
    final HttpMethod method;
    final String proxy;

    public RequestConfig(HttpMethod method, Map<String, Object> headers, Object body,
                         int timeoutMs, int keepAliveMs, String proxy) {
        this.timeout = timeoutMs;
        this.keepAlive = keepAliveMs;
        this.headers = headers;
        this.body = body;
        this.method = method;
        this.proxy = proxy;
    }

    public RequestConfig() { // for easy test only
        this(HttpMethod.GET, null, null, 40000, -1, null);
    }
}
