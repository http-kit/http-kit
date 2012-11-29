package me.shenfeng.http.ws;

import java.nio.ByteBuffer;

import me.shenfeng.http.ProtocolException;

public class WSDecoder {

    public static final byte OPCODE_CONT = 0x0;
    public static final byte OPCODE_TEXT = 0x1;
    public static final byte OPCODE_BINARY = 0x2;
    public static final byte OPCODE_CLOSE = 0x8;
    public static final byte OPCODE_PING = 0x9;
    public static final byte OPCODE_PONG = 0xA;

    public enum State {
        FRAME_START, MASKING_KEY, PAYLOAD, CORRUPT
    }

    private State state = State.FRAME_START;
    private byte[] content;
    private int payloadLength;
    private int payloadRead;
    private int maskingKey;
    private boolean finalFlag;
    private int opcode;

    public WSFrame decode(ByteBuffer buffer) throws ProtocolException {
        while (buffer.hasRemaining()) {
            switch (state) {
            case FRAME_START:
                byte b = buffer.get(); // FIN, RSV, OPCODE
                finalFlag = (b & 0x80) != 0;
                opcode = b & 0x0F;
                b = buffer.get(); // MASK, PAYLOAD LEN 1
                boolean masked = (b & 0x80) != 0;
                payloadLength = b & 0x7F;

                if (payloadLength == 126) {
                    payloadLength = buffer.getShort(); // TODO unsigned
                } else if (payloadLength == 127) {
                    payloadLength = (int) buffer.getLong(); // long is large
                }

                // TODO max length
                content = new byte[payloadLength];
                if (!masked) {
                    throw new ProtocolException("unmasked client to server frame");
                }
                state = State.MASKING_KEY;
                break;
            case MASKING_KEY:
                maskingKey = buffer.getInt();
                state = State.PAYLOAD;
                break;
            case PAYLOAD:
                int toRead = Math.min(buffer.remaining(), payloadLength - payloadRead);
                buffer.get(content, payloadRead, toRead);
                payloadRead += toRead;
                if (payloadRead == payloadLength) {
                    byte[] mask = ByteBuffer.allocate(4).putInt(maskingKey).array();
                    for (int i = 0; i < content.length; i++) {
                        content[i] = (byte) (content[i] ^ mask[i % 4]);
                    }
                    if (opcode == OPCODE_TEXT) {
                        return new TextFrame(finalFlag, content);
                    } else if (opcode == OPCODE_PING) {
                        return new PingFrame(finalFlag, content);
                    } else if (opcode == OPCODE_CLOSE) {
                        return new CloseFrame(finalFlag, content);
                    } else {
                        throw new ProtocolException("not impl now for opcode: " + opcode);
                    }
                }
                break;
            }
        }
        return null;
    }

    public void reset() {
        state = State.FRAME_START;
        payloadRead = 0;
    }
}
