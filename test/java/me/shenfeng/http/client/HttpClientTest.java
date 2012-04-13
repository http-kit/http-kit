package me.shenfeng.http.client;

import java.io.IOException;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

class TextHandler implements ITextHandler {

    private CountDownLatch cd;

    public TextHandler(CountDownLatch cd) {
        this.cd = cd;
    }

    public void onSuccess(int status, Map<String, String> headers, String body) {
        System.out.println("status: " + status + "; body length: "
                + body.length());
        cd.countDown();

    }

    public void onThrowable(Throwable t) {
        t.printStackTrace();
        cd.countDown();
    }
}

public class HttpClientTest {

    public HttpClientTest() throws IOException {
        client = new HttpClient(new HttpClientConfig());
    }

    HttpClient client;
    Map<String, String> emptyHeader;

    @Before
    public void setup() throws IOException {
        emptyHeader = new TreeMap<String, String>();
    }

    @Test
    public void testGetpythonServer() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get("http://wiki.nginx.org/Main", emptyHeader, Proxy.NO_PROXY,
                new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(40, TimeUnit.SECONDS));
    }

    @Test
    public void testGetGzipped() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get("http://en.wikipedia.org/wiki/HTTP", emptyHeader,
                Proxy.NO_PROXY, new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(1000, TimeUnit.SECONDS));
    }

    @Test
    public void testDecodeSinaGzipped() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get("http://www.sina.com.cn/", emptyHeader, Proxy.NO_PROXY,
                new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(4, TimeUnit.SECONDS));
    }

    // { echo -ne "HTTP/1.0 200 OK\r\n\r\n"; cat project.clj; } | nc -l -p 8089
    @Test
    public void testDecodeNoLength() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get("http://127.0.0.1:8089", emptyHeader, Proxy.NO_PROXY,
                new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(4, TimeUnit.SECONDS));
    }

    // {cat project.clj; } | nc -l -p 8089
    @Test
    public void testProtocolException() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get("http://127.0.0.1:8089", emptyHeader, Proxy.NO_PROXY,
                new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(400, TimeUnit.SECONDS));
    }
}
