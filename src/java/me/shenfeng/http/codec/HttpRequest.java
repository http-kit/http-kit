package me.shenfeng.http.codec;

public interface HttpRequest extends HttpMessage {

    HttpMethod getMethod();

    String getUri();
}
