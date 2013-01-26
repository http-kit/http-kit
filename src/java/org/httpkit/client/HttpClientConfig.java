package org.httpkit.client;

public class HttpClientConfig {
    final int timeOutMs;
    final String userAgent;
    final int keepalive;

    /**
     * 
     * @param timeOutMs
     *            default read or connect timeout in milliseconds
     * @param userAgent
     *            default user agent
     * @param keepalive
     *            keep-alive time, milliseconds
     */
    public HttpClientConfig(int timeOutMs, String userAgent, int keepalive) {
        this.timeOutMs = timeOutMs;
        this.userAgent = userAgent;
        this.keepalive = keepalive;
    }

    @Override
    public String toString() {
        return "default config: {timeout=" + timeOutMs + "ms, useragent=" + userAgent
                + ", keepalive=" + keepalive + "ms}";
    }
}
