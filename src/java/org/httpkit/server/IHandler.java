package org.httpkit.server;

public interface IHandler {
    void handle(HttpRequest request, RespCallback callback);

    void handle(AsyncChannel channel, Frame frame);

    public void clientClose(AsyncChannel channel, int status);

    public void clientClose(AsyncChannel channel, int status, String reason);

    // close any resource with this handler
    void close(int timeoutMs);
}
