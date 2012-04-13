package me.shenfeng.http.server;

public interface IHandler {
    void handle(HttpRequest request, IResponseCallback callback);

    // close any resource with this handler
    void close();
}
