package me.shenfeng.http.server;

import java.nio.ByteBuffer;

public class ServerAtta {
    public final ReqeustDecoder decoder = new ReqeustDecoder(2048);

    // not strictly header, if one buffer can fit, choose this buffer first
    volatile ByteBuffer respHeader;
    volatile ByteBuffer respBody;
}
