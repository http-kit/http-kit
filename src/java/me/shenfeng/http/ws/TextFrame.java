package me.shenfeng.http.ws;

import me.shenfeng.http.HttpUtils;

public class TextFrame extends WSFrame {

    private final String msg;

    public TextFrame(boolean finalFrame, byte[] data, WsCon con) {
        super(finalFrame, data, con);
        this.msg = new String(data, HttpUtils.UTF_8);
    }

    public String getText() {
        return msg;
    }

}
