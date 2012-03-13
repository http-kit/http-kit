package me.shenfeng.http.codec;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

public interface IHttpMessage {

    State decode(ByteBuffer buffer);

    String getHeader(String name);

    Map<String, String> getHeaders();

    Set<String> getHeaderNames();

    HttpVersion getProtocolVersion();

    byte[] getContent();

    long getContentLength();

    boolean isKeepAlive();
}
