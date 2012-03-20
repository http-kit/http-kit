package me.shenfeng.http.codec;

import java.util.Map;

public interface IHttpMessage {

    String getHeader(String name);

    Map<String, String> getHeaders();

    void setHeader(String name, String value);

    HttpVersion getProtocolVersion();

    byte[] getContent();

    void setContent(byte[] content);

    long getContentLength();

    HttpVersion getVersion();

    boolean isKeepAlive();
}
