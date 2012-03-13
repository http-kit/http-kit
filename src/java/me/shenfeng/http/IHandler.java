package me.shenfeng.http;

import me.shenfeng.http.codec.IHttpRequest;

public interface IHandler {
    public void handle(IHttpRequest request, IParamedRunnable callback);
}
