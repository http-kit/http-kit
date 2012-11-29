package me.shenfeng.http.ws;

import me.shenfeng.http.server.ServerAtta;

public class WsServerAtta extends ServerAtta {

    public WSDecoder decoder;
    boolean closeOnfinish = false;

    public WsServerAtta(WsCon con) {
        this.decoder = new WSDecoder(con);
    }

    public boolean isKeepAlive() {
        return !closeOnfinish;
    }

    public void reset() {
        decoder.reset();
    }
}
