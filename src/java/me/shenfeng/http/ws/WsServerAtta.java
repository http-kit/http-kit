package me.shenfeng.http.ws;

import me.shenfeng.http.server.ServerAtta;

public class WsServerAtta extends ServerAtta {

    final public WSDecoder decoder;
    // may write from another thread: app close the connection
    public volatile boolean closeOnfinish = false;
    final public WsCon con;

    public WsServerAtta(WsCon con) {
        this.decoder = new WSDecoder();
        this.con = con;
    }

    public boolean isKeepAlive() {
        return !closeOnfinish;
    }

    public void reset() {
        decoder.reset();
    }
}
