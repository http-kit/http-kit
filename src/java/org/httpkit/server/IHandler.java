package org.httpkit.server;

import org.httpkit.ws.WSFrame;
import org.httpkit.ws.WsCon;

public interface IHandler {
    void handle(HttpRequest request, ResponseCallback callback);

    void handle(final WsCon con, final WSFrame frame);

    // close any resource with this handler
    void close();
}
