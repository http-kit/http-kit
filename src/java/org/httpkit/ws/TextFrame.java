package org.httpkit.ws;

import org.httpkit.HttpUtils;

public class TextFrame extends WSFrame {

    private final String msg;

    public TextFrame(byte[] data) {
        super(data);
        this.msg = HttpUtils.newString(data, data.length, HttpUtils.UTF_8);
    }

    public String getText() {
        return msg;
    }
}
