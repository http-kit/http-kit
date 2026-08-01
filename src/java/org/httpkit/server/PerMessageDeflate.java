package org.httpkit.server;

import org.httpkit.DynamicBytes;

import java.util.HashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The "permessage-deflate" WebSocket extension, RFC 7692.
 *
 * <p>One instance per connection, and internally synchronized.
 *
 * <p>An earlier version claimed synchronization was unnecessary because
 * outbound frames are serialised by {@code AsyncChannel.send} and inbound
 * frames are decoded on the single IO event thread owning the connection's
 * {@link WsAtta}. Both are true and neither covers SHUTDOWN: {@code onClose}
 * and {@code serverClose} run on whatever thread closed the channel, and
 * {@code HttpServer.stop} closes keys from the stopping thread while the
 * selector may still be mid-decode. That put {@code Inflater.end()} in a race
 * with {@code Inflater.inflate()} over native memory, which is not a
 * misbehaving-parse problem but a JVM-integrity one.
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
    private boolean ended;

    private static final byte[] EMPTY = new byte[0];

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
            // Limit -1: split() drops TRAILING empty strings by default, so
            // "permessage-deflate;;" arrived here as a clean single-element
            // array and the empty-parameter check below never saw it.
            String[] parts = candidate.trim().split(";", -1);
            if (parts.length == 0 || !NAME.equalsIgnoreCase(parts[0].trim())) {
                continue;
            }

            boolean serverNoCtx = false, clientNoCtx = false, acceptable = true;
            // RFC 7692 7.1: "the negotiation offer contains multiple extension
            // parameters with the same name" is grounds to DECLINE, so names
            // are tracked rather than last-one-wins.
            Set<String> seen = new HashSet<String>();

            for (int i = 1; i < parts.length && acceptable; i++) {
                String p = parts[i].trim();
                // An empty parameter (`permessage-deflate;;`) is not valid
                // grammar; it used to be skipped.
                if (p.isEmpty()) { acceptable = false; break; }
                int eq = p.indexOf('=');
                String key = (eq < 0 ? p : p.substring(0, eq)).trim();
                String val = eq < 0 ? null : unquote(p.substring(eq + 1).trim());

                if (!seen.add(key.toLowerCase())) { acceptable = false; break; }

                if ("server_no_context_takeover".equalsIgnoreCase(key)) {
                    // Takes no value. `server_no_context_takeover=x` is invalid.
                    if (val != null) { acceptable = false; break; }
                    serverNoCtx = true;
                } else if ("client_no_context_takeover".equalsIgnoreCase(key)) {
                    if (val != null) { acceptable = false; break; }
                    clientNoCtx = true;
                } else if ("client_max_window_bits".equalsIgnoreCase(key)) {
                    // Constrains the CLIENT's deflater. Our Inflater copes with
                    // any legal window, so an offer is acceptable either as a
                    // bare hint or with a value -- but a malformed value is not.
                    if (val != null && !isWindowBits(val)) { acceptable = false; break; }
                } else if ("server_max_window_bits".equalsIgnoreCase(key)) {
                    // DECLINE. java.util.zip does not expose zlib's windowBits,
                    // so the server cannot honour a smaller window.
                    //
                    // This used to accept the offer and simply omit the
                    // parameter from the response. That is not a valid
                    // acceptance: RFC 7692 7.1.2.1 says "a server declines an
                    // extension negotiation offer with this parameter if the
                    // server doesn't support it", and accepting requires
                    // echoing the parameter with the same or a smaller value.
                    // A client that offered server_max_window_bits=10 may size
                    // its inflate window at 1 KiB while we emit 32 KiB-distance
                    // references -- silent corruption, not a failed handshake.
                    acceptable = false;
                    break;
                } else {
                    // An unknown parameter must make the offer unacceptable
                    // rather than be ignored -- ignoring it would mean agreeing
                    // to terms we did not implement.
                    acceptable = false;
                    break;
                }
            }
            if (acceptable) {
                return new PerMessageDeflate(serverNoCtx, clientNoCtx, maxSize);
            }
        }
        return null;
    }

    /** RFC 7692: 1*DIGIT, a decimal integer 8..15 without leading zeroes.
     *  Integer.parseInt is too lax on its own -- it accepts "+8" and "08". */
    private static boolean isWindowBits(String s) {
        if (s.isEmpty() || s.length() > 2) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        if (s.length() > 1 && s.charAt(0) == '0') return false;   // no leading zeroes
        int n = Integer.parseInt(s);
        return n >= 8 && n <= 15;
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
    public synchronized byte[] compress(byte[] data, int length) {
        if (ended) return EMPTY;
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
    public synchronized byte[] decompress(byte[] data) throws WebSocketException {
        // The connection is closing underneath us; fail the frame rather than
        // touch a released Inflater.
        if (ended) throw new WebSocketException(1001, "Connection closing");
        inflater.setInput(data);
        // Bounded, and not by data.length*4: that overflows to a negative
        // capacity on a large :max-ws, and lets a peer force a 16 MiB
        // allocation with a 4 MiB frame whose output is tiny. DynamicBytes
        // grows as needed, so a modest start costs a copy at worst.
        DynamicBytes out = new DynamicBytes(
                Math.max(64, Math.min(data.length * 2L, maxSize) > Integer.MAX_VALUE
                        ? 65536 : (int) Math.min(data.length * 2L, 65536)));
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

    /** Release the native zlib state. Called when the connection closes, from
     *  whichever thread closed it -- hence synchronized, and idempotent: both
     *  onClose and serverClose can reach it. */
    public synchronized void end() {
        if (ended) return;
        ended = true;
        deflater.end();
        inflater.end();
    }
}
