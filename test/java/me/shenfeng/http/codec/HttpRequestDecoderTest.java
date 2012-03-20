package me.shenfeng.http.codec;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpRequestDecoderTest {

    private ByteBuffer googleReq;
    HttpReqeustDecoder decoder;
    private ByteBuffer meiPost;

    @Before
    public void setup() throws IOException {
        googleReq = ByteBuffer.wrap(Utils.readAll("google.get"));
        meiPost = ByteBuffer.wrap(Utils.readAll("mei.post"));
        decoder = new HttpReqeustDecoder();
    }

    @Test
    public void testGoogleGetDecode() throws Exception {
        decoder.decode(googleReq);
        IHttpRequest request = decoder.getMessage();
        Assert.assertEquals(request.getProtocolVersion(), HttpVersion.HTTP_1_1);
        Assert.assertEquals(request.getUri(), "/");
        Assert.assertEquals(request.getContentLength(), 0);
        Assert.assertEquals(request.getMethod(), HttpMethod.GET);
        Assert.assertEquals(request.getHeaders().size(), 8);
    }

    @Test
    public void testMeiweiPostDecode() throws Exception {
        decoder.decode(meiPost);
        IHttpRequest request = decoder.getMessage();
        Assert.assertEquals(request.getProtocolVersion(), HttpVersion.HTTP_1_1);
        Assert.assertEquals(request.getUri(), "/login");
        Assert.assertEquals(request.getContentLength(), 46);
        Assert.assertEquals(request.getContent().length, 46);
        Assert.assertEquals(request.getMethod(), HttpMethod.POST);
        Assert.assertEquals(request.getHeaders().size(), 13);
    }
}
