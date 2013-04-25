package org.httpkit.ws;

import org.httpkit.ProtocolException;

import java.nio.ByteBuffer;
import java.util.Arrays;

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
    private int idx = 0;

    private int payloadLength;
    private int payloadRead;
    private int maskingKey;
    private boolean finalFlag;
    private int opcode = -1;

    public WSFrame decode(ByteBuffer buffer) throws ProtocolException {
        while (buffer.hasRemaining()) {
            switch (state) {
                case FRAME_START:
                    byte b = buffer.get(); // FIN, RSV, OPCODE
                    finalFlag = (b & 0x80) != 0;

                    int tmpOp = b & 0x0F;
                    if (opcode != -1 && tmpOp != opcode) {
                        // TODO ping frame in fragmented text frame
                        throw new ProtocolException("opcode mismatch: pre: " + opcode + ", now: "
                                + tmpOp);
                    }
                    opcode = tmpOp;
                    b = buffer.get(); // MASK, PAYLOAD LEN 1
                    boolean masked = (b & 0x80) != 0;
                    payloadLength = b & 0x7F;

                    if (payloadLength == 126) {
                        payloadLength = buffer.getShort() & 0xFFFF;
                        if (payloadLength < 126) {
                            throw new ProtocolException(
                                    "invalid data frame length (not using minimal length encoding)");
                        }
                    } else if (payloadLength == 127) {
                        long length = buffer.getLong();
                        // if negative, that too big, drop it.
                        if (length < 65536) {
                            throw new ProtocolException(
                                    "invalid data frame length. max payload length 4M");
                        }
                        abortIfTooLarge(length);
                        payloadLength = (int) length;
                    }

                    if (content == null) {
                        content = new byte[payloadLength];
                    } else if (payloadLength > 0) {
                        abortIfTooLarge(content.length + payloadLength);
                    /*
                     * TODO if an attacker sent many fragmented frames, only one
                     * byte of data per frame, server end up reallocate many
                     * times. may not be a problem
                     */
                        // resize
                        content = Arrays.copyOf(content, content.length + payloadLength);
                    }

                    if (!masked) {
                        throw new ProtocolException("unmasked client to server frame");
                    }
                    state = State.MASKING_KEY;
                    break;
                case MASKING_KEY:
                    maskingKey = buffer.getInt();
                    state = State.PAYLOAD;
                    // No break. since payloadLength can be 0
                case PAYLOAD:
                    int read = Math.min(buffer.remaining(), payloadLength - payloadRead);
                    if (read > 0) {
                        buffer.get(content, idx, read);

                        byte[] mask = ByteBuffer.allocate(4).putInt(maskingKey).array();
                        for (int i = 0; i < read; i++) {
                            content[i + idx] = (byte) (content[i + idx] ^ mask[i % 4]);
                        }

                        payloadRead += read;
                        idx += read;
                    }

                    // all read
                    if (payloadRead == payloadLength) {
                        if (finalFlag) {
                            switch (opcode) {
                                case OPCODE_TEXT:
                                    return new TextFrame(content);
                                case OPCODE_BINARY:
                                    return new BinaryFrame(content);
                                case OPCODE_PING:
                                    return new PingFrame(content);
                                case OPCODE_CLOSE:
                                    return new CloseFrame(content);
                                default:
                                    throw new ProtocolException("not impl for opcode: " + opcode);
                            }
                        } else {
                            state = State.FRAME_START;
                            payloadRead = 0;
                        }
                    }
                    break;
            }
        }
        return null; // wait for more bytes
    }

    public void abortIfTooLarge(long length) throws ProtocolException {
        // TODO configurable
        if (length > 4194304) { // 4M, drop if message is too big
            throw new ProtocolException("Max payload length 4m, get: " + length);
        }

    }

    public void reset() {
        state = State.FRAME_START;
        payloadRead = 0;
        idx = 0;
        opcode = -1;
        content = null;
    }
}
