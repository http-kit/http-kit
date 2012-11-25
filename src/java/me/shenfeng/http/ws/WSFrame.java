package me.shenfeng.http.ws;

public abstract class WSFrame {
    public final boolean finalFrame;
    public final byte[] data;
    public final WsCon wsCon;

    public WSFrame(boolean finalFrame, byte data[], WsCon con) {
        this.finalFrame = finalFrame;
        this.data = data;
        this.wsCon = con;
    }
}
