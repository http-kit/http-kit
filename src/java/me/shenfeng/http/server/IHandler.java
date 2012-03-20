package me.shenfeng.http.server;

import me.shenfeng.http.codec.IHttpRequest;

public interface IHandler {
    void handle(IHttpRequest request, IParamedRunnable callback);
}
