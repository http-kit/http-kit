package org.httpkit.server;

public class HttpAtta extends ServerAtta {

    public HttpAtta(int maxBody, int maxLine, ProxyProtocolOption proxyProtocolOption) {
        decoder = new HttpDecoder(maxBody, maxLine, proxyProtocolOption);
    }

    public final HttpDecoder decoder;
}
