package org.httpkit.server;

public class HttpAtta extends ServerAtta {

    public HttpAtta(int maxBody, int maxLine) {
        decoder = new HttpDecoder(maxBody, maxLine);
    }

    public final HttpDecoder decoder;

    // close the connection after write?
    // greedy: if client support it( HTTP/1.1 without keep-alive: close,
    // HTTP/1.0 with keep-alive: keep-alive), only close the FD after client
    // close it
    boolean keepalive;

    public boolean isKeepAlive() {
        return keepalive;
    }
}
