package me.shenfeng.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import me.shenfeng.http.codec.DefaultHttpRequest;
import me.shenfeng.http.codec.HttpMethod;
import me.shenfeng.http.codec.HttpVersion;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DefaultHttpRequestTest {

    private byte[] readAll(String resource) throws IOException {
        InputStream is = DefaultHttpRequestTest.class.getClassLoader()
                .getResourceAsStream(resource);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b = 0;
        while ((b = is.read()) != -1) {
            bos.write(b);
        }
        is.close();
        return bos.toByteArray();
    }

    private ByteBuffer googleReq;
    DefaultHttpRequest decoder;
    private ByteBuffer meiPost;

    @Before
    public void setup() throws IOException {
        googleReq = ByteBuffer.wrap(readAll("google.get"));
        meiPost = ByteBuffer.wrap(readAll("mei.post"));
        decoder = new DefaultHttpRequest();
    }

    @Test
    public void testGoogleGetDecode() throws Exception {
        decoder.decode(googleReq);
        Assert.assertEquals(decoder.getProtocolVersion(),
                HttpVersion.HTTP_1_1);
        Assert.assertEquals(decoder.getUri(), "/");
        Assert.assertEquals(decoder.getContentLength(), 0);
        Assert.assertEquals(decoder.getMethod(), HttpMethod.GET);
        Assert.assertEquals(decoder.getHeaders().size(), 8);
    }

    @Test
    public void testMeiweiPostDecode() throws Exception {
        decoder.decode(meiPost);
        Assert.assertEquals(decoder.getProtocolVersion(),
                HttpVersion.HTTP_1_1);
        Assert.assertEquals(decoder.getUri(), "/login");
        Assert.assertEquals(decoder.getContentLength(), 46);
        Assert.assertEquals(decoder.getContent().length, 46);
        Assert.assertEquals(decoder.getMethod(), HttpMethod.POST);
        Assert.assertEquals(decoder.getHeaders().size(), 13);
    }
}
