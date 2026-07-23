package org.httpkit.server;

import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;
import org.httpkit.RequestTooLargeException;
import org.httpkit.HeadersTooLargeException;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HttpDecoderTest {

    private HttpDecoder decoder(ProxyProtocolOption proxyProtocolOption) {
        return new HttpDecoder(1024, 1024, proxyProtocolOption, false);
    }

    private HttpRequest decode(HttpDecoder decoder, String request)
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        return decoder.decode(ByteBuffer.wrap(request.getBytes()));
    }

    @Test
    public void acceptsCaseInsensitiveChunkedEncoding()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException, IOException {
        HttpRequest request = decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: Chunked\r\n\r\n1\r\na\r\n0\r\n\r\n");

        assertEquals('a', request.getBody().read());
        assertEquals(1, request.contentLength);
    }

    @Test(expected = ProtocolException.class)
    public void rejectsNegativeContentLength()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: -1\r\n\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsExplicitlySignedContentLength()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: +1\r\n\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsNegativeChunkSize()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsExplicitlySignedChunkSize()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n+1\r\n");
    }

    @Test
    public void acceptsChunkExtensions()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException, IOException {
        HttpRequest request = decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1;foo=bar\r\na\r\n0\r\n\r\n");

        assertEquals('a', request.getBody().read());
    }

    @Test(expected = ProtocolException.class)
    public void rejectsUnsupportedTransferEncoding()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: gzip\r\n\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsAmbiguousRequestFraming()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\n");
    }

    @Test
    public void readsSplitChunkDelimitersAndTrailers()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException, IOException {
        HttpDecoder decoder = decoder(ProxyProtocolOption.DISABLED);

        assertNull(decode(decoder,
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r"));
        assertNull(decode(decoder, "\n0\r\nX-Trailer: yes\r\n\r"));
        HttpRequest request = decode(decoder, "\n");

        assertEquals('a', request.getBody().read());
        assertEquals("yes", request.headers.get("x-trailer"));
    }

    @Test(expected = ProtocolException.class)
    public void rejectsInvalidChunkDelimiter()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1\r\naX\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsUnsupportedHttpVersion()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED), "GET / HTTP/2.0\r\n\r\n");
    }

    @Test
    public void treatsNullProxyOptionAsDisabled()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        HttpRequest request = decode(decoder(null), "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals("/", request.uri);
    }

    @Test
    public void trustsProxyAddressOverRequestHeader()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        HttpRequest request = decode(decoder(ProxyProtocolOption.ENABLED),
            "PROXY TCP4 203.0.113.7 192.0.2.1 12345 443\r\n" +
            "GET / HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 198.51.100.9\r\n\r\n");

        assertEquals("203.0.113.7", request.getRemoteAddr());
        assertEquals("203.0.113.7", request.headers.get("x-forwarded-for"));
        assertEquals("https", request.headers.get("x-forwarded-proto"));
        assertEquals("443", request.headers.get("x-forwarded-port"));
    }

    @Test
    public void optionalProxyDoesNotInventForwardedHeaders()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        HttpRequest request = decode(decoder(ProxyProtocolOption.OPTIONAL),
            "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertFalse(request.headers.containsKey("x-forwarded-for"));
        assertFalse(request.headers.containsKey("x-forwarded-proto"));
        assertFalse(request.headers.containsKey("x-forwarded-port"));
    }

    @Test(expected = ProtocolException.class)
    public void rejectsHostPortAboveTcpRange()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "GET / HTTP/1.1\r\nHost: localhost:65536\r\n\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsSignedHostPort()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "GET / HTTP/1.1\r\nHost: localhost:+80\r\n\r\n");
    }

    @Test(expected = RequestTooLargeException.class)
    public void checksChunkedBodySizeWithoutIntegerOverflow()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "1\r\na\r\n7fffffff\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsBareLineFeeds()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "GET / HTTP/1.1\nHost: localhost\n\n");
    }

    @Test(expected = ProtocolException.class)
    public void requiresHttp11Host()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED), "GET / HTTP/1.1\r\n\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsMalformedHeaderLines()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "GET / HTTP/1.1\r\nHost: localhost\r\nBroken\r\n\r\n");
    }

    @Test(expected = ProtocolException.class)
    public void rejectsDuplicateHost()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "GET / HTTP/1.1\r\nHost: first\r\nHost: second\r\n\r\n");
    }

    @Test
    public void parsesConnectionAndUpgradeTokens()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        HttpRequest request = decode(decoder(ProxyProtocolOption.DISABLED),
            "GET / HTTP/1.1\r\nHost: localhost\r\n" +
            "Connection: keep-alive, close\r\nUpgrade: other, websocket\r\n\r\n");

        assertFalse(request.isKeepAlive);
        assertTrue(request.isWebSocket);
    }

    @Test(expected = ProtocolException.class)
    public void rejectsForbiddenTrailers()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        decode(decoder(ProxyProtocolOption.DISABLED),
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "0\r\nContent-Length: 1\r\n\r\n");
    }

    @Test(expected = HeadersTooLargeException.class)
    public void boundsAggregateHeaderBytes()
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        StringBuilder request = new StringBuilder("GET / HTTP/1.1\r\nHost: localhost\r\n");
        String value = new String(new char[1000]).replace('\0', 'a');
        for (int i = 0; i < 70; i++) {
            request.append("X-").append(i).append(": ").append(value).append("\r\n");
        }
        request.append("\r\n");
        decode(decoder(ProxyProtocolOption.DISABLED), request.toString());
    }

    @Test(expected = ProtocolException.class)
    public void enforcesWebSocketLimitOnFirstFrame() throws ProtocolException {
        WSDecoder decoder = new WSDecoder(100);
        byte[] frame = new byte[2 + 2 + 4 + 126];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) 0xfe;
        frame[2] = 0;
        frame[3] = 126;
        decoder.decode(ByteBuffer.wrap(frame));
    }

    @Test(expected = ProtocolException.class)
    public void enforcesWebSocketLimitAcrossFragments() throws ProtocolException {
        WSDecoder decoder = new WSDecoder(4);
        byte[] first = new byte[] {0x01, (byte) 0x83, 0, 0, 0, 0, 'a', 'b', 'c'};
        byte[] second = new byte[] {(byte) 0x80, (byte) 0x82, 0, 0, 0, 0, 'd', 'e'};

        assertNull(decoder.decode(ByteBuffer.wrap(first)));
        decoder.decode(ByteBuffer.wrap(second));
    }

    @Test
    public void rejectsInvalidWebSocketText() throws ProtocolException {
        WSDecoder decoder = new WSDecoder(100);
        byte[] frame = new byte[] {(byte) 0x81, (byte) 0x82, 0, 0, 0, 0,
                (byte) 0xc3, 0x28};
        try {
            decoder.decode(ByteBuffer.wrap(frame));
        } catch (WebSocketException e) {
            assertEquals(1007, e.getCloseStatus());
            return;
        }
        throw new AssertionError("Expected invalid UTF-8 to be rejected");
    }

    @Test
    public void rejectsOneByteWebSocketClose() throws ProtocolException {
        WSDecoder decoder = new WSDecoder(100);
        byte[] frame = new byte[] {(byte) 0x88, (byte) 0x81, 0, 0, 0, 0, 0};
        try {
            decoder.decode(ByteBuffer.wrap(frame));
        } catch (WebSocketException e) {
            assertEquals(1002, e.getCloseStatus());
            return;
        }
        throw new AssertionError("Expected invalid close payload to be rejected");
    }
}
