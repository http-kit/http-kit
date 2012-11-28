package me.shenfeng.http.server;

public class HttpServerAtta extends ServerAtta {
    public HttpServerAtta(int maxBody, int maxLine) {
        decoder = new ReqeustDecoder(maxBody, maxLine);
    }

    public final ReqeustDecoder decoder;

    public boolean isKeepAlive() {
        return decoder.request != null && decoder.request.isKeepAlive();
    }

    public void reset() {
        decoder.reset();
    }
}
