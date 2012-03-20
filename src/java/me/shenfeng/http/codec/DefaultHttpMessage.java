package me.shenfeng.http.codec;

import java.util.Map;
import java.util.TreeMap;

public abstract class DefaultHttpMessage implements IHttpMessage {

    protected Map<String, String> headers = new TreeMap<String, String>();
    protected HttpVersion version;
    protected byte[] content;

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    public HttpVersion getProtocolVersion() {
        return version;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public byte[] getContent() {
        return content;
    }

    public long getContentLength() {
        return 0;
    }

    public HttpVersion getVersion() {
        return version;
    }

    public boolean isKeepAlive() {
        return version == HttpVersion.HTTP_1_1;
    }

}
