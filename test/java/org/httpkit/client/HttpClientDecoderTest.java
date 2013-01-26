package org.httpkit.client;

import static org.httpkit.HttpUtils.TRANSFER_ENCODING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import junit.framework.Assert;

import org.httpkit.HttpMethod;
import org.httpkit.HttpStatus;
import org.httpkit.HttpVersion;
import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;
import org.httpkit.client.AbortException;
import org.httpkit.client.Decoder;
import org.httpkit.client.IRespListener;
import org.httpkit.client.State;
import org.httpkit.codec.Utils;
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
        }, HttpMethod.GET);

        ByteBuffer buffer = ByteBuffer.wrap(Utils.readAll("beta_shield_chunked"));
        State s = decoder.decode(buffer);
        Assert.assertEquals("state should be ALL_READ", s, State.ALL_READ);
        Assert.assertTrue(onCompleteCallded);
    }
}
