package me.shenfeng.http.ws;

public abstract class WSFrame {
    public final byte[] data;

    public WSFrame(byte data[]) {
        this.data = data;
    }
}
