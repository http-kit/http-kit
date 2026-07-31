package org.httpkit.server;

import org.httpkit.DynamicBytes;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The "permessage-deflate" WebSocket extension, RFC 7692.
 *
 * <p>One instance per connection. Not thread-safe, and does not need to be:
 * outbound frames are serialised by {@code AsyncChannel.send}, which is
 * {@code synchronized}, and inbound frames are decoded on the single IO event
 * thread that owns the connection's {@link WsAtta}.
 *
 * <h3>What is implemented</h3>
 *
 * Context takeover in both directions (the default, and the whole point of the
 * extension: message N is compressed against messages 1..N-1, which is what
 * makes a stream of many small similar messages compress at all). The
 * {@code client_no_context_takeover} and {@code server_no_context_takeover}
 * parameters are honoured when the client asks for them.
 *
 * <h3>What is not</h3>
 *
 * {@code server_max_window_bits} is declined. {@code java.util.zip} does not
 * expose zlib's windowBits, so the server cannot honour a smaller window and
 * must not claim to. Per RFC 7692 section 7.1.2.2 an offer whose
 * {@code server_max_window_bits} we cannot satisfy is simply not accepted with
 * that parameter -- we respond without it. {@code client_max_window_bits} IS
 * accepted, because it constrains the client's deflater and our
 * {@link Inflater} handles any window up to the 32 KiB maximum.
 *
 * <h3>Message size</h3>
 *
 * {@link WSDecoder} bounds the length of the bytes actually received. That
 * bound does not survive inflation: a small compressed frame can expand
 * enormously, so decompression is bounded separately here and aborts with the
 * same 1009 status the decoder uses.
 */
public class PerMessageDeflate {

    /** RFC 7692 7.2.1: DEFLATE emits this tail on a SYNC_FLUSH; it is removed
     *  on compress and appended again before inflating. */
    private static final byte[] TAIL = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

    public static final String NAME = "permessage-deflate";

    private final Deflater deflater;
    private final Inflater inflater;
    private final boolean serverNoContextTakeover;
    private final boolean clientNoContextTakeover;
    private final int maxSize;

    private PerMessageDeflate(boolean serverNoContextTakeover,
                              boolean clientNoContextTakeover,
                              int maxSize) {
        // nowrap = raw DEFLATE, i.e. no zlib header or checksum. RFC 7692 is
        // defined over raw deflate blocks.
        this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        this.inflater = new Inflater(true);
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.maxSize = maxSize;
    }

    /**
     * Negotiate against a client's {@code Sec-WebSocket-Extensions} offer.
     *
     * @return null when permessage-deflate was not offered, or was offered only
     *         with parameters this implementation cannot satisfy. A null result
     *         means "no extension"; the connection then behaves exactly as it
     *         did before this class existed.
     */
    public static PerMessageDeflate negotiate(String offer, int maxSize) {
        if (offer == null) return null;

        // The header is a comma-separated list of offers, each of which the
        // server may accept or skip. Take the first one we can satisfy.
        for (String candidate : offer.split(",")) {
            String[] parts = candidate.trim().split(";");
            if (parts.length == 0 || !NAME.equalsIgnoreCase(parts[0].trim())) {
                continue;
            }

            boolean serverNoCtx = false, clientNoCtx = false, acceptable = true;
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                String key = (eq < 0 ? p : p.substring(0, eq)).trim();
                String val = eq < 0 ? null : unquote(p.substring(eq + 1).trim());

                if ("server_no_context_takeover".equalsIgnoreCase(key)) {
                    serverNoCtx = true;
                } else if ("client_no_context_takeover".equalsIgnoreCase(key)) {
                    clientNoCtx = true;
                } else if ("client_max_window_bits".equalsIgnoreCase(key)) {
                    // Accepted but not echoed: it constrains the CLIENT's
                    // deflater, and our inflater copes with any legal window.
                    if (val != null && !isWindowBits(val)) acceptable = false;
                } else if ("server_max_window_bits".equalsIgnoreCase(key)) {
                    // Cannot be honoured (see class docs). If the client merely
                    // offered it we decline the parameter by not echoing it,
                    // which per RFC 7692 means a 15-bit window. A value we do
                    // not understand makes the whole offer unacceptable.
                    if (val != null && !isWindowBits(val)) acceptable = false;
                } else {
                    // An unknown parameter must make the offer unacceptable
                    // rather than be ignored -- ignoring it would mean agreeing
                    // to terms we did not implement.
                    acceptable = false;
                }
            }
            if (acceptable) {
                return new PerMessageDeflate(serverNoCtx, clientNoCtx, maxSize);
            }
        }
        return null;
    }

    private static boolean isWindowBits(String s) {
        try {
            int n = Integer.parseInt(s);
            return n >= 8 && n <= 15;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** The value to send back in {@code Sec-WebSocket-Extensions}. */
    public String responseHeader() {
        StringBuilder sb = new StringBuilder(NAME);
        if (serverNoContextTakeover) sb.append("; server_no_context_takeover");
        if (clientNoContextTakeover) sb.append("; client_no_context_takeover");
        return sb.toString();
    }

    /**
     * Compress one message. RFC 7692 7.2.1: deflate with SYNC_FLUSH, then drop
     * the 4-octet {@code 00 00 FF FF} tail that the flush appends.
     */
    public byte[] compress(byte[] data, int length) {
        deflater.setInput(data, 0, length);
        DynamicBytes out = new DynamicBytes(Math.max(64, length));
        byte[] buf = new byte[4096];
        int n;
        // SYNC_FLUSH rather than finish(): finishing would end the deflate
        // stream and discard the history that context takeover exists to keep.
        while ((n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH)) > 0) {
            out.append(buf, n);
            if (n < buf.length) break;
        }
        if (serverNoContextTakeover) deflater.reset();

        int len = out.length();
        // Drop the tail the SYNC_FLUSH appended.
        if (len >= TAIL.length && endsWithTail(out.get(), len)) {
            len -= TAIL.length;
        }
        // RFC 7692 7.2.3.6: when the compressor produces nothing -- an empty
        // message, or one whose content was already flushed -- the payload
        // must be a single empty uncompressed DEFLATE block, 0x00, NOT zero
        // bytes. "If the compression library being used doesn't generate any
        // data when its buffer is empty, an empty uncompressed DEFLATE block
        // can be built and used for this purpose as follows: 0x00".
        //
        // Zero bytes only misbehaves once there is compression history for it
        // to corrupt: an empty message round-trips fine on a fresh connection
        // and desynchronises the stream mid-conversation. Found by testing
        // against an independent client implementation, not by reading.
        if (len == 0) {
            return new byte[]{0x00};
        }
        byte[] result = new byte[len];
        System.arraycopy(out.get(), 0, result, 0, len);
        return result;
    }

    private static boolean endsWithTail(byte[] bs, int len) {
        for (int i = 0; i < TAIL.length; i++) {
            if (bs[len - TAIL.length + i] != TAIL[i]) return false;
        }
        return true;
    }

    /**
     * Decompress one message. RFC 7692 7.2.2: append the tail the sender
     * removed, then inflate.
     */
    public byte[] decompress(byte[] data) throws WebSocketException {
        inflater.setInput(data);
        DynamicBytes out = new DynamicBytes(Math.max(64, data.length * 4));
        byte[] buf = new byte[4096];
        try {
            inflate(out, buf);
            inflater.setInput(TAIL);
            inflate(out, buf);
        } catch (DataFormatException e) {
            throw new WebSocketException(1002, "Invalid permessage-deflate payload: "
                    + e.getMessage());
        }
        if (clientNoContextTakeover) inflater.reset();
        byte[] result = new byte[out.length()];
        System.arraycopy(out.get(), 0, result, 0, out.length());
        return result;
    }

    private void inflate(DynamicBytes out, byte[] buf)
            throws DataFormatException, WebSocketException {
        int n;
        while (!inflater.needsInput() && (n = inflater.inflate(buf)) > 0) {
            // Bounded here and not only in WSDecoder: the decoder limits the
            // bytes RECEIVED, which says nothing about the size after
            // inflation. Without this, permessage-deflate would turn a small
            // frame into an unbounded allocation.
            if (out.length() + n > maxSize) {
                throw new WebSocketException(1009,
                        "Max payload length " + maxSize + " exceeded after decompression");
            }
            out.append(buf, n);
        }
    }

    /** Release the native zlib state. Called when the connection closes. */
    public void end() {
        deflater.end();
        inflater.end();
    }
}
