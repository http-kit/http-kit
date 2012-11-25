package me.shenfeng.http.ws;

import java.nio.ByteBuffer;

import me.shenfeng.http.HttpUtils;

public class WSEncoder {

    // clear text
    public static final ByteBuffer encode(String text) {
        byte b0 = 0;
        b0 |= 1 << 7; // FIN
        b0 |= WSDecoder.OPCODE_TEXT;

        byte[] data = text.getBytes(HttpUtils.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(data.length + 10); // max
        buffer.put(b0);
        
        if (data.length <= 125) {
            buffer.put((byte)(data.length));
        } else if (data.length <= 0xFFFF) {
            buffer.put((byte) 126);
            buffer.putShort((short) data.length);
        } else {
            buffer.put((byte) 127);
            buffer.putLong(data.length);
        }
        buffer.put(data);
        buffer.flip();
        return buffer;
    }
}
