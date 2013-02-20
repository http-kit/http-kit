package org.httpkit.ws;

import java.nio.ByteBuffer;

import org.httpkit.HttpUtils;

public class WSEncoder {
    public static ByteBuffer encode(byte opcode, byte[] data) {
        byte b0 = 0;
        b0 |= 1 << 7; // FIN
        b0 |= opcode;
        ByteBuffer buffer = ByteBuffer.allocate(data.length + 10); // max
        buffer.put(b0);

        if (data.length <= 125) {
            buffer.put((byte) (data.length));
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

    // clear text
    public static ByteBuffer encode(String text) {
        byte[] data = text.getBytes(HttpUtils.UTF_8_CH);
        return encode(WSDecoder.OPCODE_TEXT, data);
    }
}
