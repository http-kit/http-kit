package org.httpkit.server;

public class HttpServerAtta extends ServerAtta {

    public HttpServerAtta(int maxBody, int maxLine) {
        decoder = new RequestDecoder(maxBody, maxLine);
    }

    public final RequestDecoder decoder;

    // close the connection after write?
    // greedy: if client support it( HTTP/1.1 without keep-alive: close,
    // HTTP/1.0 with keep-alive: keep-alive), only close the FD after client
    // close it
    boolean keepalive;

    public boolean isKeepAlive() {
        return keepalive;
    }
}
