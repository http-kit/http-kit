package org.httpkit.server;

public class HttpAtta extends ServerAtta {

    public HttpAtta(int maxBody, int maxLine, ProxyProtocolOption proxyProtocolOption, boolean legacyUnsafeRemoteAddr) {
        decoder = new HttpDecoder(maxBody, maxLine, proxyProtocolOption, legacyUnsafeRemoteAddr);
    }

    public final HttpDecoder decoder;
}