package org.httpkit.ws;

import java.nio.ByteBuffer;

public class WSEncoder {
    public static ByteBuffer encode(byte opcode, byte[] data, int length) {
        byte b0 = 0;
        b0 |= 1 << 7; // FIN
        b0 |= opcode;
        ByteBuffer buffer = ByteBuffer.allocate(length + 10); // max
        buffer.put(b0);

        if (length <= 125) {
            buffer.put((byte) (length));
        } else if (length <= 0xFFFF) {
            buffer.put((byte) 126);
            buffer.putShort((short) length);
        } else {
            buffer.put((byte) 127);
            buffer.putLong(length);
        }
        buffer.put(data, 0, length);
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer encode(byte opcode, byte[] data) {
        return encode(opcode, data, data.length);
    }
}
