package me.shenfeng.http.server;

import me.shenfeng.http.ws.WSFrame;
import me.shenfeng.http.ws.WsCon;

public interface IHandler {
    void handle(HttpRequest request, ResponseCallback callback);

    void handle(final WsCon con, final WSFrame frame);

    // close any resource with this handler
    void close();
}
