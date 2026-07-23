package org.httpkit.server;

import org.httpkit.DynamicBytes;
import org.httpkit.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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
    private DynamicBytes content; // Content accumulated across fragmented data frames
    private byte[] frameContent;

    private int payloadLength;
    private int payloadRead;
    private int maskingKey;
    private boolean finalFlag;
    private int opcode = -1;
    private int fragmentedOpCode = -1;
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

                    if ((b & 0x70) != 0) {
                        throw new ProtocolException("unsupported websocket extension data");
                    }

                    opcode = b & 0x0F;
                    if (!isSupportedOpcode(opcode)) {
                        throw new ProtocolException("unsupported websocket opcode: " + opcode);
                    }
                    if (isControlFrame(opcode)) {
                        if (!finalFlag) {
                            throw new ProtocolException("fragmented websocket control frame");
                        }
                    } else if (opcode == OPCODE_CONT) {
                        if (fragmentedOpCode == -1) {
                            throw new ProtocolException("unexpected websocket continuation frame");
                        }
                    } else if (fragmentedOpCode != -1) {
                        throw new ProtocolException("new data frame while fragmented message is open");
                    }
                    state = State.READ_LENGTH;
                    break;
                case READ_LENGTH:
                    b = buffer.get(); // MASK, PAYLOAD LEN 1
                    boolean masked = (b & 0x80) != 0;
                    if (!masked) {
                        throw new ProtocolException("unmasked client to server frame");
                    }
                    payloadLength = b & 0x7F;
                    if (isControlFrame(opcode) && payloadLength > 125) {
                        throw new ProtocolException("websocket control frame payload is too large");
                    }
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
                            throw new ProtocolException("invalid data frame length. most significant bit is not zero or length fits in unsigned short.");
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
                        if (!isControlFrame(opcode)) {
                            long messageLength = payloadLength;
                            if (opcode == OPCODE_CONT) {
                                messageLength += content.length();
                            }
                            abortIfTooLarge(messageLength);
                        }
                        frameContent = new byte[payloadLength];
                        framePayloadIndex = 0; // reset
                        state = State.PAYLOAD;
                        // No break. since payloadLength can be 0
                    } else {
                        break; // wait for more data from TCP
                    }
                case PAYLOAD:
                    int read = Math.min(buffer.remaining(), payloadLength - payloadRead);
                    if (read > 0) {
                        buffer.get(frameContent, payloadRead, read);

                        byte[] mask = ByteBuffer.allocate(4).putInt(maskingKey).array();
                        for (int i = 0; i < read; i++) {
                            int frameIndex = payloadRead + i;
                            frameContent[frameIndex] = (byte) (frameContent[frameIndex]
                                    ^ mask[(framePayloadIndex + i) % 4]);
                        }

                        payloadRead += read;
                    }
                    framePayloadIndex += read;

                    // all read (this frame)
                    if (payloadRead == payloadLength) {
                        if (isControlFrame(opcode)) {
                            switch (opcode) {
                                case OPCODE_PING:
                                    return new Frame.PingFrame(frameContent);
                                case OPCODE_PONG:
                                    return new Frame.PongFrame(frameContent);
                                case OPCODE_CLOSE:
                                    validateClose(frameContent);
                                    return new Frame.CloseFrame(frameContent);
                                default:
                                    throw new AssertionError("unsupported control opcode: " + opcode);
                            }
                        }

                        if (opcode == OPCODE_CONT) {
                            appendFrameContent();
                            if (finalFlag) {
                                int completedOpcode = fragmentedOpCode;
                                byte[] completedContent = content.bytes();
                                fragmentedOpCode = -1;
                                content = null;
                                return dataFrame(completedOpcode, completedContent);
                            }
                        } else if (finalFlag) {
                            return dataFrame(opcode, frameContent);
                        } else {
                            fragmentedOpCode = opcode;
                            content = new DynamicBytes(frameContent.length);
                            content.append(frameContent, frameContent.length);
                        }
                        resetFrame();
                    }
                    break;
            }
        }
        return null; // wait for more bytes
    }

    private static boolean isSupportedOpcode(int opcode) {
        return opcode == OPCODE_CONT || opcode == OPCODE_TEXT || opcode == OPCODE_BINARY
                || opcode == OPCODE_CLOSE || opcode == OPCODE_PING || opcode == OPCODE_PONG;
    }

    private static boolean isControlFrame(int opcode) {
        return opcode >= OPCODE_CLOSE;
    }

    private void appendFrameContent() {
        content.append(frameContent, frameContent.length);
    }

    private Frame dataFrame(int opcode, byte[] data) throws ProtocolException {
        switch (opcode) {
            case OPCODE_TEXT:
                validateUtf8(data, 0, data.length);
                return new Frame.TextFrame(data);
            case OPCODE_BINARY:
                return new Frame.BinaryFrame(data);
            default:
                throw new ProtocolException("invalid fragmented message opcode: " + opcode);
        }
    }

    public void abortIfTooLarge(long length) throws ProtocolException {
        if (length > maxSize) { // drop if message is too big
            throw new WebSocketException(1009,
                    "Max payload length " + maxSize + ", got: " + length);
        }
    }

    private static void validateClose(byte[] data) throws WebSocketException {
        if (data.length == 1) {
            throw new WebSocketException(1002, "Invalid one-byte websocket close payload");
        }
        if (data.length >= 2) {
            int status = ByteBuffer.wrap(data, 0, 2).getShort() & 0xffff;
            if (!isValidCloseStatus(status)) {
                throw new WebSocketException(1002, "Invalid websocket close status: " + status);
            }
            validateUtf8(data, 2, data.length - 2);
        }
    }

    static boolean isValidCloseStatus(int status) {
        return status >= 1000 && status < 5000
                && status != 1004 && status != 1005
                && status != 1006 && status != 1015;
    }

    private static void validateUtf8(byte[] data, int offset, int length)
            throws WebSocketException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length));
        } catch (CharacterCodingException e) {
            throw new WebSocketException(1007, "Invalid UTF-8 websocket payload");
        }
    }

    public void reset() {
        resetFrame();
    }

    private void resetFrame() {
        state = State.FRAME_START;
        payloadLength = 0;
        payloadRead = 0;
        opcode = -1;
        frameContent = null;
        framePayloadIndex = 0;
    }
}
