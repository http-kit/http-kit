package org.httpkit.server;

public class WsAtta extends ServerAtta {

    final public WSDecoder decoder;

    public WsAtta(AsyncChannel channel) {
        this.decoder = new WSDecoder();
        this.channel = channel;
    }
}
