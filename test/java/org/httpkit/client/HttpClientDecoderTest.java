package org.httpkit.client;

import junit.framework.Assert;
import org.httpkit.*;
import org.httpkit.codec.Utils;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.httpkit.HttpUtils.TRANSFER_ENCODING;

public class HttpClientDecoderTest {

    @Test(expected = HeadersTooLargeException.class)
    public void boundsAggregateResponseHeaders() throws Exception {
        Decoder decoder = new Decoder(new NoopListener(), HttpMethod.GET);
        StringBuilder response = new StringBuilder("HTTP/1.1 200 OK\r\n");
        String value = new String(new char[1000]).replace('\0', 'a');
        for (int i = 0; i < 70; i++) {
            response.append("X-").append(i).append(": ").append(value).append("\r\n");
        }
        response.append("\r\n");
        decoder.decode(ByteBuffer.wrap(response.toString().getBytes("ISO-8859-1")));
    }

    @Test(expected = ProtocolException.class)
    public void rejectsForbiddenResponseTrailers() throws Exception {
        Decoder decoder = new Decoder(new NoopListener(), HttpMethod.GET);
        decoder.decode(ByteBuffer.wrap((
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "0\r\nContent-Length: 1\r\n\r\n").getBytes("ISO-8859-1")));
    }

    @Test
    public void testDecodeChunkedResponse() throws IOException, LineTooLargeException,
            ProtocolException, AbortException {
        Decoder decoder = new Decoder(new IRespListener() {
            public void onThrowable(Throwable t) {
                throw new RuntimeException(t);
            }

            public void onInitialLineReceived(HttpVersion version, HttpStatus status)
                    throws AbortException {
                Assert.assertEquals(HttpVersion.HTTP_1_1, version);
                Assert.assertEquals(status, HttpStatus.OK);
            }

            public void onHeadersReceived(Map<String, Object> headers) throws AbortException {
                Assert.assertNotNull(headers.get(TRANSFER_ENCODING));
            }

            public void onCompleted() {
            }

            public void onBodyReceived(byte[] buf, int length) throws AbortException {
                Assert.assertEquals(2869, length);
            }
        }, HttpMethod.GET);

        ByteBuffer buffer = ByteBuffer.wrap(Utils.readAll("beta_shield_chunked"));
        State s = decoder.decode(buffer);
        Assert.assertEquals("state should be ALL_READ", s, State.ALL_READ);
    }

    @Test
    public void testDecodeChunkSplits() throws IOException, LineTooLargeException, ProtocolException, AbortException {
        Decoder decoder = new Decoder(new IRespListener() {
            @Override
            public void onThrowable(Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public void onInitialLineReceived(HttpVersion version, HttpStatus status) {
            }

            @Override
            public void onHeadersReceived(Map<String, Object> headers) {
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onBodyReceived(byte[] buf, int length) {
            }
        }, HttpMethod.POST);

        List<byte[]> chunks = Utils.readAll(
            "java/chunk_split_1", "java/chunk_split_2", "java/chunk_split_3");
        int i = 0;
        while (i < chunks.size() - 1) {
            State state = decoder.decode(ByteBuffer.wrap(chunks.get(i)));
            Assert.assertNotSame(State.ALL_READ, state);
            i++;
        }
        State state = decoder.decode(ByteBuffer.wrap(chunks.get(i)));
        Assert.assertEquals(State.ALL_READ, state);
    }

    private static class NoopListener implements IRespListener {
        public void onThrowable(Throwable t) {
        }

        public void onInitialLineReceived(HttpVersion version, HttpStatus status) {
        }

        public void onHeadersReceived(Map<String, Object> headers) {
        }

        public void onCompleted() {
        }

        public void onBodyReceived(byte[] buf, int length) {
        }
    }
}
