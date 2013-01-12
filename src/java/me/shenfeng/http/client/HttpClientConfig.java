package me.shenfeng.http.client;

public class HttpClientConfig {
    final int timeOutMs;
    final String userAgent;

    /**
     * Create a default HttpClientConfig, (read || connect) timeout 40s
     */
    public HttpClientConfig() {
        this(40000, "http-kit/1.3");
    }

    /**
     * 
     * @param timeOutMs
     *            default read or connect timeout in milliseconds
     * @param userAgent
     *            default user agent
     */
    public HttpClientConfig(int timeOutMs, String userAgent) {
        this.timeOutMs = timeOutMs;
        this.userAgent = userAgent;
    }

    @Override
    public String toString() {
        return "default config: {timeout=" + timeOutMs + "ms, useragent=" + userAgent + '}';
    }
}
