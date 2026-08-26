package org.httpkit.server;

import org.httpkit.DynamicBytes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The "permessage-deflate" WebSocket extension, RFC 7692.
 *
 * <p>One instance per connection, and internally synchronized: {@code end()}
 * releases native zlib state and runs on whichever thread closed the channel,
 * while the selector thread may still be mid-decode.
 *
 * <h3>What is implemented</h3>
 *
 * Context takeover in both directions, so message N is compressed against
 * messages 1..N-1. {@code client_no_context_takeover} and
 * {@code server_no_context_takeover} are honoured when the client asks.
 *
 * <h3>What is not</h3>
 *
 * A <em>smaller</em> {@code server_max_window_bits}: {@code java.util.zip} does
 * not expose zlib's windowBits, so the server cannot honour a window under
 * 32 KiB and must not claim to (section 7.1.2.1 -- accepting while omitting the
 * parameter would let a client size its inflate window at 1 KiB against our
 * 32 KiB-distance references, which corrupts silently). {@code =15} is
 * satisfiable exactly and is accepted and echoed.
 * {@code client_max_window_bits} is accepted at any value, since it constrains
 * the client's deflater and our {@link Inflater} handles any legal window.
 *
 * <p>A {@code Sec-WebSocket-Extensions} value that cannot be parsed yields no
 * extension and an otherwise normal handshake. RFC 6455 section 9.1 says to
 * fail the connection; downgrading is the deliberate choice here, since it
 * fails closed and refusing the WebSocket outright over a header quirk is
 * worse for a server whose clients are mostly browsers.
 *
 * <h3>Bounds</h3>
 *
 * {@link WSDecoder} bounds the bytes RECEIVED, which says nothing about the
 * size after inflation, so decompression is bounded separately here (1009).
 * The number of DEFLATE streams per message is bounded too (1008); see
 * {@link #inflate}.
 */
public class PerMessageDeflate {

    /** RFC 7692 7.2.1: DEFLATE emits this tail on a SYNC_FLUSH; it is removed
     *  on compress and appended again before inflating. */
    private static final byte[] TAIL = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

    public static final String NAME = "permessage-deflate";

    /** How many DEFLATE streams in one message may inflate to NOTHING before we
     *  stop working on it. A resource policy, not a conformance rule -- see
     *  {@link #inflate}. */
    private static final int MAX_EMPTY_STREAMS = 64;

    /** Absolute ceiling on DEFLATE streams in one message. Far above any
     *  plausible sender -- 65536 streams need at least 128 KiB of payload --
     *  and low enough to keep this path below the amplification a plain
     *  compression bomb already gets. See {@link #inflate}. */
    private static final int MAX_STREAMS = 65536;

    /** Indices into the per-message budget array threaded through
     *  {@link #inflate}, which is called once for the payload and once for the
     *  appended tail; the ceilings apply across both. */
    private static final int STREAMS = 0, EMPTY_STREAMS = 1;

    private final Deflater deflater;
    private final Inflater inflater;
    private final boolean serverNoContextTakeover;
    private final boolean clientNoContextTakeover;
    /** Whether the accepted offer asked us to echo server_max_window_bits=15. */
    private final boolean serverMaxWindowBits;
    private final int maxSize;
    /** Messages shorter than this are sent uncompressed. See shouldCompress. */
    private final int threshold;
    private boolean ended;

    /** RFC 7692 7.2.3.6: an empty uncompressed DEFLATE block, i.e. a
     *  well-formed compressed payload carrying no content. */
    private static final byte[] EMPTY_BLOCK = {0x00};

    private PerMessageDeflate(boolean serverNoContextTakeover,
                              boolean clientNoContextTakeover,
                              boolean serverMaxWindowBits,
                              int maxSize,
                              int threshold) {
        // nowrap = raw DEFLATE, i.e. no zlib header or checksum. RFC 7692 is
        // defined over raw deflate blocks.
        this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        this.inflater = new Inflater(true);
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.serverMaxWindowBits = serverMaxWindowBits;
        this.maxSize = maxSize;
        this.threshold = threshold;
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
        return negotiate(offer, maxSize, 0);
    }

    public static PerMessageDeflate negotiate(String offer, int maxSize, int threshold) {
        if (offer == null) return null;

        // The header is a comma-separated list of offers, each of which the
        // server may accept or skip. Take the first one we can satisfy.
        List<List<String>> candidates = splitOffers(offer);
        if (candidates == null) return null;   // not well-formed at all

        for (List<String> parts : candidates) {
            if (parts.isEmpty() || !NAME.equalsIgnoreCase(parts.get(0).trim())) {
                continue;
            }

            boolean serverNoCtx = false, clientNoCtx = false, acceptable = true;
            boolean serverMaxWindowBits = false;
            // RFC 7692 7.1: "the negotiation offer contains multiple extension
            // parameters with the same name" is grounds to DECLINE, so names
            // are tracked rather than last-one-wins.
            Set<String> seen = new HashSet<String>();

            for (int i = 1; i < parts.size() && acceptable; i++) {
                String p = parts.get(i).trim();
                // An empty parameter (`permessage-deflate;;`) is not
                // valid grammar.
                if (p.isEmpty()) { acceptable = false; break; }
                int eq = p.indexOf('=');
                String key = (eq < 0 ? p : p.substring(0, eq)).trim();
                String val = eq < 0 ? null : unquote(p.substring(eq + 1).trim());

                // Locale.ROOT, not the JVM default: Turkish folds 'I' to a
                // dotless lowercase i, so the default locale would make
                // "CLIENT_MAX_WINDOW_BITS" and "client_max_window_bits" fold
                // to different strings and let a duplicate through.
                if (!seen.add(key.toLowerCase(Locale.ROOT))) { acceptable = false; break; }

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
                    // Constrains OUR deflater, whose window java.util.zip
                    // fixes at 15. RFC 7692 7.1.2.1: an acceptance must echo
                    // the parameter "with the same or smaller value as the
                    // offer", so only =15 is satisfiable. The parameter has no
                    // valueless form, so a bare one is an invalid value.
                    if (!"15".equals(val)) { acceptable = false; break; }
                    serverMaxWindowBits = true;
                } else {
                    // An unknown parameter must make the offer unacceptable
                    // rather than be ignored -- ignoring it would mean agreeing
                    // to terms we did not implement.
                    acceptable = false;
                    break;
                }
            }
            if (acceptable) {
                return new PerMessageDeflate(serverNoCtx, clientNoCtx,
                                             serverMaxWindowBits, maxSize,
                                             threshold);
            }
        }
        return null;
    }

    /**
     * Split a {@code Sec-WebSocket-Extensions} value into offers, and each
     * offer into its extension name plus parameters, cutting only at
     * delimiters that are NOT inside a quoted string.
     *
     * <p>RFC 7692 5.2 permits a quoted parameter value and RFC 7230's
     * quoted-string may contain commas and semicolons, so cutting the raw
     * string first would let a client smuggle a whole offer through the middle
     * of an invalid value:
     *
     * <pre>permessage-deflate; client_max_window_bits="10, permessage-deflate,11"</pre>
     *
     * is ONE offer carrying one invalid value, which section 7 requires us to
     * decline -- not two offers, the second of which looks acceptable.
     *
     * @return null if the value is not well-formed (an unterminated quoted
     *         string, or a trailing backslash), in which case nothing in it can
     *         be trusted and the whole header is declined.
     */
    private static List<List<String>> splitOffers(String header) {
        List<List<String>> offers = new ArrayList<List<String>>();
        List<String> current = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    // quoted-pair: the next octet stands for itself, quote
                    // marks and delimiters included.
                    if (++i == header.length()) return null;
                    token.append(header.charAt(i));
                } else {
                    if (c == '"') quoted = false;
                    token.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                token.append(c);
            } else if (c == ',' || c == '\n' || c == '\r') {
                // RFC 6455 9.1 lets a client split the offer list across
                // repeated Sec-WebSocket-Extensions headers, which is exactly
                // equivalent to one comma-separated value. http-kit joins
                // duplicate headers with '\n' internally and normalises that to
                // ',' before the Ring map, so this only matters for callers
                // handing us a raw header value -- but it costs nothing and
                // means the parser is correct either way.
                current.add(token.toString());
                token.setLength(0);
                offers.add(current);
                current = new ArrayList<String>();
            } else if (c == ';') {
                current.add(token.toString());
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        if (quoted) return null;   // unterminated quoted-string

        current.add(token.toString());
        offers.add(current);
        return offers;
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
        // Echoed only when the offer asked for it: RFC 7692 7.1.2.1 requires
        // the same or a smaller value, and 15 is the only one we can honour.
        if (serverMaxWindowBits) sb.append("; server_max_window_bits=15");
        return sb.toString();
    }

    /**
     * Whether a message of this length should go out compressed.
     *
     * <p>RFC 7692 6.1 lets an endpoint send any individual message
     * uncompressed, with RSV1 clear. The decision has to be made HERE, before
     * {@link #compress}, and cannot be made afterwards by looking at whether
     * the output came out larger: feeding bytes to the deflater advances the
     * LZ77 window that the peer's inflater mirrors, so discarding the result
     * and sending the original would leave the two windows disagreeing for
     * every later message under context takeover.
     *
     * <p>Hence a length threshold rather than a "did it help?" test. Size is a
     * weak proxy for compressibility -- with context takeover, short repetitive
     * messages compress best, not worst -- which is why the default threshold
     * is 0 and everything is compressed.
     */
    public boolean shouldCompress(int length) {
        return length >= threshold;
    }

    /**
     * Compress one message. RFC 7692 7.2.1: deflate with SYNC_FLUSH, then drop
     * the 4-octet {@code 00 00 FF FF} tail that the flush appends.
     */
    public synchronized byte[] compress(byte[] data, int length) {
        // Whatever we return is framed with RSV1 set, and zero bytes is the
        // malformed payload decompress() rejects -- so on a shutdown race emit
        // RFC 7692 7.2.3.6's empty block rather than corrupting the peer.
        if (ended) return EMPTY_BLOCK;
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
            return EMPTY_BLOCK;
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
        // RFC 7692 7.2.3.6: a compressed message with no content still
        // carries one empty DEFLATE block, the octet 0x00. Zero octets is
        // malformed, and not harmlessly: appending the 4-octet tail to nothing
        // yields 00 00 FF FF, whose leading 0x00 the inflater reads as a
        // stored-block header and whose next two octets it reads as
        // LEN = 0xFF00. It then waits for octets that never come, so the empty
        // message is delivered and the NEXT one fails. Reject it here.
        if (data.length == 0) {
            throw new WebSocketException(1002,
                    "Empty permessage-deflate payload: a compressed message must "
                    + "contain at least an empty DEFLATE block (0x00)");
        }
        // Bounded, and not by data.length*4: that overflows to a negative
        // capacity on a large :max-ws, and lets a peer force a 16 MiB
        // allocation with a 4 MiB frame whose output is tiny. DynamicBytes
        // grows as needed, so a modest start costs a copy at worst.
        DynamicBytes out = new DynamicBytes(
                Math.max(64, (int) Math.min(data.length * 2L, 65536)));
        byte[] buf = new byte[4096];
        try {
            // Only append the tail if the payload did not already end the
            // DEFLATE stream itself -- see inflate(). Appending 00 00 FF FF
            // after a finished stream would start a stored block whose length
            // never arrives, which is the very corruption the empty-payload
            // check above exists to prevent.
            int[] budget = new int[2];   // ceilings span both calls
            if (!inflate(data, out, buf, budget)) {
                inflate(TAIL, out, buf, budget);
            }
        } catch (DataFormatException e) {
            throw new WebSocketException(1002, "Invalid permessage-deflate payload: "
                    + e.getMessage());
        }
        if (clientNoContextTakeover) inflater.reset();
        byte[] result = new byte[out.length()];
        System.arraycopy(out.get(), 0, result, 0, out.length());
        return result;
    }

    /**
     * Inflate all of {@code src}, restarting whenever the sender ends a DEFLATE
     * stream.
     *
     * <p>RFC 7692 7.2.3.4 lets a sender flush with a {@code BFINAL=1} block
     * rather than an empty uncompressed one, and 7.2.1 says "the next DEFLATE
     * block follows the padded data if any", so blocks may follow a finished
     * stream both within a message and across messages. A single
     * {@link Inflater} cannot carry on by itself: once it reports
     * {@code finished()} it returns 0 forever, which would silently truncate
     * the message and deliver every later message on the connection empty.
     * Resetting puts it back at the start of a fresh stream, which is exactly
     * where such a sender is; dropping the LZ77 history with it is correct,
     * since a BFINAL block ends the sender's stream too.
     *
     * <p>A payload ending exactly on a finished stream omits the trailing
     * empty-block header that 7.2.1 step 3 guarantees, and so is malformed --
     * 7.2.3.4's "Hello" is eight octets, not seven. It is accepted anyway. The
     * line drawn throughout this class is to reject what DESYNCHRONISES the
     * stream and tolerate what is merely sloppy: a zero-octet payload leaves
     * the inflater mid stored-block and breaks every later message, whereas
     * this leaves it cleanly reset.
     *
     * @return true if a stream ended exactly at the end of {@code src}, leaving
     *         nothing that the 4-octet tail could complete.
     */
    private boolean inflate(byte[] src, DynamicBytes out, byte[] buf, int[] budget)
            throws DataFormatException, WebSocketException {
        int offset = 0;
        while (true) {
            int producedBefore = out.length();
            inflater.setInput(src, offset, src.length - offset);
            int n;
            while (!inflater.finished() && !inflater.needsInput()
                    && (n = inflater.inflate(buf)) > 0) {
                // Bounded here and not only in WSDecoder: the decoder limits
                // the bytes RECEIVED, which says nothing about the size after
                // inflation. Without this, permessage-deflate would turn a
                // small frame into an unbounded allocation. The cast keeps the
                // sum from wrapping negative when maxSize is near 2 GiB.
                if ((long) out.length() + n > maxSize) {
                    throw new WebSocketException(1009,
                            "Max payload length " + maxSize + " exceeded after decompression");
                }
                out.append(buf, n);
            }
            if (!inflater.finished()) return false;

            // Bound the work: the size guard above counts OUTPUT, and a
            // stream restart produces none, so it cannot see this. The cost is
            // the native Inflater.reset() per stream, which is the same
            // whether or not the stream produced anything -- hence both
            // ceilings, since two octets are enough for a complete BFINAL
            // stream and a peer can give each one a byte of output to dodge a
            // bound that counted only empty ones. MAX_EMPTY_STREAMS kills the
            // cheapest shape early; MAX_STREAMS is what bounds the work.
            //
            // Neither is a conformance rule: 7.2.1 puts no limit on blocks per
            // message, and a message carrying thousands of data-bearing
            // streams is accepted. Hence 1008 (policy) rather than 1002.
            if (++budget[STREAMS] > MAX_STREAMS) {
                throw new WebSocketException(1008,
                        "Too many DEFLATE streams in one message (>"
                        + MAX_STREAMS + ")");
            }
            if (out.length() == producedBefore
                    && ++budget[EMPTY_STREAMS] > MAX_EMPTY_STREAMS) {
                throw new WebSocketException(1008,
                        "Too many empty DEFLATE streams in one message (>"
                        + MAX_EMPTY_STREAMS + ")");
            }
            int remaining = inflater.getRemaining();
            offset = src.length - remaining;
            inflater.reset();
            if (remaining == 0) return true;
            // A finished stream consumed at least its own block header, so
            // offset strictly advances and this cannot spin.
        }
    }

    /** Release the native zlib state. Called when the connection closes, from
     *  whichever thread closed it -- hence synchronized, and idempotent:
     *  {@code onClose} and {@code HttpServer.closeKey} can both reach it, and so
     *  can {@code AsyncChannel.setPerMessageDeflate} when a handshake finishes
     *  after its connection is already gone. NOT {@code serverClose}, which
     *  deliberately leaves the codec alive for the closing handshake. */
    public synchronized void end() {
        if (ended) return;
        ended = true;
        deflater.end();
        inflater.end();
    }
}
