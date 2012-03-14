package me.shenfeng.http;

import me.shenfeng.http.codec.IHttpRequest;

interface IHandler {
    void handle(IHttpRequest request, IParamedRunnable callback);
}
