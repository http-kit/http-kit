package org.httpkit.server;

public interface IHandler {
    void handle(HttpRequest request, RespCallback callback);

    void handle(AsyncChannel channel, Frame frame);

    public void clientClose(AsyncChannel channel, int status);

    // close any resource with this handler
    void close(int timeoutMs);
}
