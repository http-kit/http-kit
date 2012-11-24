package me.shenfeng.http.server;

import java.nio.ByteBuffer;

public class ServerAtta {

    public ServerAtta(int maxBody) {
        decoder = new ReqeustDecoder(maxBody);
    }

    public final ReqeustDecoder decoder;

    // not strictly header, if one buffer can fit, choose this buffer first
    volatile ByteBuffer respHeader;
    volatile ByteBuffer respBody;
}
