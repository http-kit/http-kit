package org.httpkit.ws;

import org.httpkit.server.AsyncChannel;
import org.httpkit.server.ServerAtta;

public class WsServerAtta extends ServerAtta {

    final public WSDecoder decoder;
    // may write from another thread: app close the connection
    public volatile boolean closeOnfinish = false;

    public WsServerAtta(AsyncChannel channel) {
        this.decoder = new WSDecoder();
        this.asycChannel = channel;
    }

    public boolean isKeepAlive() {
        return !closeOnfinish;
    }
}
