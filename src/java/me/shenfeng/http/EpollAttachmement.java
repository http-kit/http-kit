package me.shenfeng.http;

import java.nio.ByteBuffer;

import me.shenfeng.http.codec.DefaultHttpRequest;

public class EpollAttachmement {
    private int aTime = (int) System.currentTimeMillis();
    public final DefaultHttpRequest request = new DefaultHttpRequest();
    volatile ByteBuffer response; // maybe another thread

    public void touch(int time) {
        aTime = time;
    }

    public int getAtime() {
        return aTime;
    }
}
