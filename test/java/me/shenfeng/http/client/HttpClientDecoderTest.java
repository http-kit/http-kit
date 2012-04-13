package me.shenfeng.http.client;

import static me.shenfeng.http.HttpUtils.TRANSFER_ENCODING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import junit.framework.Assert;
import me.shenfeng.http.codec.HttpStatus;
import me.shenfeng.http.codec.HttpVersion;
import me.shenfeng.http.codec.LineTooLargeException;
import me.shenfeng.http.codec.ProtocolException;
import me.shenfeng.http.codec.Utils;

import org.junit.Test;

public class HttpClientDecoderTest {

    boolean onCompleteCallded = false;

    @Test
    public void testDecodeChunkedResponse() throws IOException,
            LineTooLargeException, ProtocolException {
        HttpClientDecoder decoder = new HttpClientDecoder(
                new IRespListener() {
                    public void onThrowable(Throwable t) {
                        throw new RuntimeException(t);
                    }

                    public int onInitialLineReceived(HttpVersion version,
                            HttpStatus status) {
                        Assert.assertEquals(HttpVersion.HTTP_1_1, version);
                        Assert.assertEquals(status, HttpStatus.OK);
                        return 0;
                    }

                    public int onHeadersReceived(Map<String, String> headers) {
                        Assert.assertNotNull(headers.get(TRANSFER_ENCODING));
                        return 0;
                    }

                    public void onCompleted() {
                        onCompleteCallded = true;
                    }

                    public int onBodyReceived(byte[] buf, int length) {
                        Assert.assertEquals(2869, length);
                        return 0;
                    }
                });

        ByteBuffer buffer = ByteBuffer.wrap(Utils
                .readAll("beta_shield_chunked"));
        ClientDecoderState s = decoder.decode(buffer);
        Assert.assertEquals("state should be ALL_READ", s,
                ClientDecoderState.ALL_READ);
        Assert.assertTrue(onCompleteCallded);
    }
}
