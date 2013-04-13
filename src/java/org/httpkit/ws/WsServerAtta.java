package org.httpkit.ws;

import org.httpkit.server.AsyncChannel;
import org.httpkit.server.ServerAtta;

public class WsServerAtta extends ServerAtta {

    final public WSDecoder decoder;

    public WsServerAtta(AsyncChannel channel) {
        this.decoder = new WSDecoder();
        this.channel = channel;
    }

    public boolean isKeepAlive() {
        return true; // always keep-alived, wait other close it
    }
}
