package me.shenfeng.http.server;

import me.shenfeng.http.ws.WSFrame;

public interface IHandler {
    void handle(HttpRequest request, ResponseCallback callback);

    void handle(WSFrame frame);
    // close any resource with this handler
    void close();
}
