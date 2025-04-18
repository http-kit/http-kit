package org.httpkit.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

class ResponseCompression {

    protected enum Type {
        NONE,
        GZIP,
        DEFLATE,
        DEFLATE_NO_WRAP
    }

    /**
     * Detects the compression type based on Content-Encoding header
     * and optionally peeking at the first few bytes for DEFLATE detection.
     *
     * @param encoding The Content-Encoding header value (can be null)
     * @param firstBytes Optional first 2 bytes for DEFLATE detection.
     * (can be null, empty, or more than 2)
     * @return The detected Type
     */
    protected static Type detect(String encoding, byte[] firstBytes) {
        if (encoding == null || encoding.trim().isEmpty()) {
            return Type.NONE;
        }

        encoding = encoding.toLowerCase().trim();

        // Handle GZIP types
        if ("gzip".equals(encoding) || "x-gzip".equals(encoding)) {
            return Type.GZIP;
        }

        // Handle DEFLATE types - need to examine the firstBytes to distinguish
        if ("deflate".equals(encoding) || "x-deflate".equals(encoding)) {
            if (firstBytes == null || firstBytes.length < 2) {
                // Not enough data to determine, default to DEFLATE_NO_WRAP
                // which is the more common case for HTTP
                return Type.DEFLATE_NO_WRAP;
            }

            return detectDeflateType(firstBytes);
        }

        // Unknown encoding, treat as not compressed
        return Type.NONE;
    }

    /**
     * Examines the first two bytes to determine DEFLATE type.
     * Based on RFC 1950 (zlib) and RFC 1951 (DEFLATE).
     *
     * See http://stackoverflow.com/questions/3932117/handling-http-contentencoding-deflate
     *
     * @param firstBytes The first body bytes (must have at least 2 bytes)
     * @return The specific DEFLATE compression type
     */
    private static Type detectDeflateType(byte[] firstBytes) {
        final int i1 = firstBytes[0] & 0xFF;  // First byte as unsigned
        final int i2 = firstBytes[1] & 0xFF;  // Second byte as unsigned

        // Check for zlib header (RFC 1950)
        // CM (Compression Method) bits 0-3
        // CINFO (Compression Info) bits 4-7
        final int compressionMethod = i1 & 0x0F;
        final int compressionInfo = (i1 >> 4) & 0x0F;

        // Check if it's a valid zlib header
        // CM must be 8 (DEFLATE), CINFO must be <= 7 (window size <= 32K)
        // FCHECK bits must form a multiple of 31
        if (compressionMethod == Deflater.DEFLATED && compressionInfo <= 7 &&
            ((i1 << 8) | i2) % 31 == 0) {
            return Type.DEFLATE;  // zlib wrapped (with header)
        }

        // Otherwise, it's raw DEFLATE (no wrap)
        return Type.DEFLATE_NO_WRAP;
    }

    /**
     * Creates an appropriate InputStream for the given compression type.
     * This is a helper method that can be used when you have the complete body.
     *
     * @param baseStream The base InputStream (e.g., ByteArrayInputStream)
     * @param type The compression type
     * @return A decompressing InputStream, or the base stream if NONE
     */
    protected static InputStream createDecompressingStream(
            InputStream baseStream,
            Type type) throws IOException {

        switch (type) {
            case GZIP:
                return new GZIPInputStream(baseStream);

            case DEFLATE:
                // zlib format (with header)
                return new InflaterInputStream(baseStream);

            case DEFLATE_NO_WRAP:
                // Raw DEFLATE format
                return new InflaterInputStream(
                    baseStream,
                    new Inflater(true));

            case NONE:
            default:
                return baseStream;
        }
    }
}
