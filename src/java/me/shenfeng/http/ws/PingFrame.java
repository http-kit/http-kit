package me.shenfeng.http.ws;

public class PingFrame extends WSFrame {
    public PingFrame(boolean finalFrame, byte[] data, WsCon con) {
        super(finalFrame, data, con);
    }
}
