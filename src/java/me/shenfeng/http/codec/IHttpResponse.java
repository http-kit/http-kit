package me.shenfeng.http.codec;

public interface IHttpResponse extends IHttpMessage {
    HttpResponseStatus getStatus();
}
