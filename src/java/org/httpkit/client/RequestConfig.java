package org.httpkit.client;

import org.httpkit.HttpMethod;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.security.NoSuchAlgorithmException;

public class RequestConfig {
    public static final SSLContext DEFAUTL_CONTEXT;

    static {
        try {
            DEFAUTL_CONTEXT = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new Error("Failed to initialize SSLContext", e);
        }
    }

    final int timeout;
    final int keepAlive;
    final SSLEngine engine;
    final HttpMethod method;

    public static String DEFAULT_USER_AGENT = "http-kit/2.0";

    public RequestConfig(HttpMethod method, int timeoutMs, int keepAliveMs, SSLEngine engine) {
        if (engine == null) {
            engine = DEFAUTL_CONTEXT.createSSLEngine();
        }
        engine.setUseClientMode(true);
        this.timeout = timeoutMs;
        this.keepAlive = keepAliveMs;
        this.engine = engine;
        this.method = method;
    }

    public RequestConfig() { // for easy test only
        this(HttpMethod.GET, 40000, -1, null);
    }
}
