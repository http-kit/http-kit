package org.httpkit.server;

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
        FRAME_START, READ_LENGTH, READ_2_LENGTH, READ_8_LENGTH, MASKING_KEY, PAYLOAD, CORRUPT
    }

    private final int maxSize;

    private State state = State.FRAME_START;
    private byte[] content;
    private int idx = 0;

    private int payloadLength;
    private int payloadRead;
    private int maskingKey;
    private boolean finalFlag;
    private int opcode = -1;
    private int framePayloadIndex; // masking per frame

    // 8 bytes are enough
    // protect against long/short/int are not fully received
    private ByteBuffer tmpBuffer = ByteBuffer.allocate(8);

    public WSDecoder(int maxSize) {
        this.maxSize = maxSize;
    }

    private boolean isAvailable(ByteBuffer src, int length) {
        while (tmpBuffer.position() < length) {
            if (src.hasRemaining()) {
                tmpBuffer.put(src.get());
            } else {
                return false;
            }
        }
        tmpBuffer.flip(); // for read
        return true;
    }

    public Frame decode(ByteBuffer buffer) throws ProtocolException {
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
                    state = State.READ_LENGTH;
                    break;
                case READ_LENGTH:
                    b = buffer.get(); // MASK, PAYLOAD LEN 1
                    boolean masked = (b & 0x80) != 0;
                    if (!masked) {
                        throw new ProtocolException("unmasked client to server frame");
                    }
                    payloadLength = b & 0x7F;
                    if (payloadLength == 126) {
                        state = State.READ_2_LENGTH;
                    } else if (payloadLength == 127) {
                        state = State.READ_8_LENGTH;
                    } else {
                        state = State.MASKING_KEY;
                    }
                    break;
                case READ_2_LENGTH:
                    if (isAvailable(buffer, 2)) {
                        payloadLength = tmpBuffer.getShort() & 0xFFFF;
                        tmpBuffer.clear();
                        if (payloadLength < 126) {
                            throw new ProtocolException(
                                    "invalid data frame length (not using minimal length encoding)");
                        }
                        state = State.MASKING_KEY;
                    }
                    break;
                case READ_8_LENGTH:
                    if (isAvailable(buffer, 8)) {
                        long length = tmpBuffer.getLong();
                        tmpBuffer.clear();
                        // if negative, that too big, drop it.
                        if (length < 65536) {
                            throw new ProtocolException("invalid data frame length. max payload length 4M");
                        }
                        abortIfTooLarge(length);
                        payloadLength = (int) length;
                        state = State.MASKING_KEY;
                    }
                    break; // wait for more data from TCP
                case MASKING_KEY:
                    if (isAvailable(buffer, 4)) {
                        maskingKey = tmpBuffer.getInt();
                        tmpBuffer.clear();
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
                        framePayloadIndex = 0; // reset
                        state = State.PAYLOAD;
                        // No break. since payloadLength can be 0
                    } else {
                        break; // wait for more data from TCP
                    }
                case PAYLOAD:
                    int read = Math.min(buffer.remaining(), payloadLength - payloadRead);
                    if (read > 0) {
                        buffer.get(content, idx, read);

                        byte[] mask = ByteBuffer.allocate(4).putInt(maskingKey).array();
                        for (int i = 0; i < read; i++) {
                            content[i + idx] = (byte) (content[i + idx] ^ mask[(framePayloadIndex + i) % 4]);
                        }

                        payloadRead += read;
                        idx += read;
                    }
                    framePayloadIndex += read;

                    // all read (this frame)
                    if (payloadRead == payloadLength) {
                        if (finalFlag) {
                            switch (opcode) {
                                case OPCODE_TEXT:
                                    return new Frame.TextFrame(content);
                                case OPCODE_BINARY:
                                    return new Frame.BinaryFrame(content);
                                case OPCODE_PING:
                                    return new Frame.PingFrame(content);
                                case OPCODE_PONG:
                                    return new Frame.PongFrame(content);
                                case OPCODE_CLOSE:
                                    return new Frame.CloseFrame(content);
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
        if (length > maxSize) { // drop if message is too big
            throw new ProtocolException("Max payload length 4m, get: " + length);
        }
    }

    public void reset() {
        state = State.FRAME_START;
        payloadRead = 0;
        idx = 0;
        opcode = -1;
        content = null;
        framePayloadIndex = 0;
    }
}
