package me.shenfeng.http.ws;

public abstract class WSFrame {
    public final boolean finalFrame;
    public final byte[] data;

    public WSFrame(boolean finalFrame, byte data[]) {
        this.finalFrame = finalFrame;
        this.data = data;
    }
}
