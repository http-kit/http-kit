package me.shenfeng.http.ws;

import java.nio.ByteBuffer;

public class CloseFrame extends WSFrame {

    public final static short CLOSE_NORMAL = 1000;
    // indicates that an endpoint is "going away", such as a server going down
    // or a browser having navigated away from a page.
    public final static short CLOSE_AWAY = 1001;
    public final static short CLOSE_PROTOCOL_ERROR = 1002;
    public final static short CLOSE_NOT_IMPL = 1003;
    // This is a generic status code that can be returned when there is no other
    // more suitable status code (e.g., 1003 or 1009)
    public final static short CLOSE_VIOLATES_POLICY = 1008;
    public final static short CLOSE_MESG_BIG = 1009;
    public final static short CLOSE_SEVER_ERROR = 1011;

    private static byte[] bytes(short code) {
        return ByteBuffer.allocate(2).putShort(code).array();
    }

    public static final CloseFrame NORMAL = new CloseFrame(true, bytes(CLOSE_NORMAL));
    public static final CloseFrame AWAY = new CloseFrame(true, bytes(CLOSE_AWAY));
    public static final CloseFrame MESG_BIG = new CloseFrame(true, bytes(CLOSE_MESG_BIG));
    public static final CloseFrame SERVER_ERROR = new CloseFrame(true, bytes(CLOSE_MESG_BIG));


    public CloseFrame(boolean finalFrame, byte[] data) {
        super(finalFrame, data);
    }

    public int getStatus() {
        if (data.length >= 2) {
            return ByteBuffer.wrap(data, 0, 2).getShort();
        }
        return CLOSE_NORMAL;
    }
}
