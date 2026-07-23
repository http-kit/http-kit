package org.httpkit.server;

public class WsAtta extends ServerAtta {

    final public WSDecoder decoder;
    int closeStatus;
    String closeReason = "";

    public WsAtta(AsyncChannel channel, int maxSize) {
        this.decoder = new WSDecoder(maxSize);
        this.channel = channel;
    }
}
