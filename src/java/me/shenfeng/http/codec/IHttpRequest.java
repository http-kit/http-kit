package me.shenfeng.http.codec;

public interface IHttpRequest extends IHttpMessage {

    HttpMethod getMethod();

    String getUri();
}
