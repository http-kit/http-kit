package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.TRANSFER_ENCODING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import junit.framework.Assert;
import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.LineTooLargeException;
import me.shenfeng.http.ProtocolException;
import me.shenfeng.http.codec.Utils;

import org.junit.Test;

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

            public void onHeadersReceived(Map<String, String> headers) throws AbortException {
                Assert.assertNotNull(headers.get(TRANSFER_ENCODING));
            }

            public void onCompleted() {
                onCompleteCallded = true;
            }

            public void onBodyReceived(byte[] buf, int length) throws AbortException {
                Assert.assertEquals(2869, length);
            }
        });

        ByteBuffer buffer = ByteBuffer.wrap(Utils.readAll("beta_shield_chunked"));
        State s = decoder.decode(buffer);
        Assert.assertEquals("state should be ALL_READ", s, State.ALL_READ);
        Assert.assertTrue(onCompleteCallded);
    }
}
