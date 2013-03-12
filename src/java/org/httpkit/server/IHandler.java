package org.httpkit.server;

import org.httpkit.ws.WSFrame;

public interface IHandler {
    void handle(HttpRequest request, ResponseCallback callback);

    void handle(AsyncChannel channel, WSFrame frame);

    public void clientClose(AsyncChannel channel, int status);

    // close any resource with this handler
    void close();
}
