package me.shenfeng.http.server;

import java.nio.ByteBuffer;

import me.shenfeng.http.codec.HttpReqeustDecoder;
import me.shenfeng.http.codec.IHttpResponse;

public class SelectAtta {
    private int aTime = (int) System.currentTimeMillis();
    public final HttpReqeustDecoder decoder = new HttpReqeustDecoder();
    volatile IHttpResponse resp;
    volatile ByteBuffer respHeader;
    volatile ByteBuffer respBody;

    public void touch(int time) {
        aTime = time;
    }

    public int getAtime() {
        return aTime;
    }
}
