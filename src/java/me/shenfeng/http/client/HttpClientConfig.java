package me.shenfeng.http.client;

public class HttpClientConfig {
    int timeOutMs = 40000;
    String userAgent = "ajh/1.0";

    public HttpClientConfig() {
    }

    /*
     * timeoutMS: read or connect timeout in ms
     */
    public HttpClientConfig(int timeOutMs, String userAgent) {
        this.timeOutMs = timeOutMs;
        this.userAgent = userAgent;
    }

    @Override
    public String toString() {
        return "{timeOutMs=" + timeOutMs + ", userAgent=" + userAgent + '}';
    }
}
