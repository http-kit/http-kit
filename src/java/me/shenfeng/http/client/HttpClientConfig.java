package me.shenfeng.http.client;

public class HttpClientConfig {
	int readingTimeoutMs = 30000;
	int connTimeOutMs = 40000;
	String userAgent = "ajh/1.0";

	public HttpClientConfig() {
	}

	public HttpClientConfig(int requestTimeoutInMs, int connectionTimeOutInMs,
			String userAgent) {
		this.readingTimeoutMs = requestTimeoutInMs;
		this.connTimeOutMs = connectionTimeOutInMs;
		this.userAgent = userAgent;
	}
}
