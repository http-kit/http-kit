package me.shenfeng.http.server;

public class HttpServerAtta extends ServerAtta {
    public HttpServerAtta(int maxBody) {
        decoder = new ReqeustDecoder(maxBody);
    }

    public final ReqeustDecoder decoder;

    public boolean isKeepAlive() {
        return decoder.request.isKeepAlive();
    }

    public void reset() {
        decoder.reset();
    }
}
