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

    boolean onCompleteCallded = false;

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
                onCompleteCallded = true;
            }

            public void onBodyReceived(byte[] buf, int length) throws AbortException {
                Assert.assertEquals(2869, length);
            }
        }, HttpMethod.GET);

        ByteBuffer buffer = ByteBuffer.wrap(Utils.readAll("beta_shield_chunked"));
        State s = decoder.decode(buffer);
        Assert.assertEquals("state should be ALL_READ", s, State.ALL_READ);
        Assert.assertTrue(onCompleteCallded);
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

        List<byte[]> chunks = Utils.readAll("chunk_split_1", "chunk_split_2", "chunk_split_3");
        int i = 0;
        while (i < chunks.size() - 1) {
            State state = decoder.decode(ByteBuffer.wrap(chunks.get(i)));
            Assert.assertNotSame(State.ALL_READ, state);
            i++;
        }
        State state = decoder.decode(ByteBuffer.wrap(chunks.get(i)));
        Assert.assertEquals(State.ALL_READ, state);
    }
}
