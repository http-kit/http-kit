package me.shenfeng.http.codec;

import java.util.TreeMap;

public class DefaultHttpRequest extends DefaultHttpMessage implements IHttpRequest {

    private HttpMethod method;
    private String uri;

    public DefaultHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this.version = version;
        this.method = method;
        this.uri = uri;
        headers = new TreeMap<String, String>();
    }

    public HttpMethod getMethod() {
        return method;
    }

    public String getUri() {
        return uri;
    }
}
