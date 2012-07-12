package me.shenfeng.http.client;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;

class TextHandler implements ITextHandler {

    private CountDownLatch cd;

    public TextHandler(CountDownLatch cd) {
        this.cd = cd;
    }

    public void onSuccess(int status, Map<String, String> headers, String body) {
        try {
            IOUtils.write(body, new FileOutputStream("/tmp/file"));
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        }
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
        String userAgent = "Mozilla/5.0 (compatible; Rssminer/1.0; +http://rssminer.net)";
        HttpClientConfig cfg = new HttpClientConfig(45000, userAgent);

        client = new HttpClient(cfg);
    }

    HttpClient client;
    Map<String, String> emptyHeader;
    Proxy socksProxy = new Proxy(Type.SOCKS, new InetSocketAddress(
            "127.0.0.1", 3128));

    @Before
    public void setup() throws IOException {
        emptyHeader = new TreeMap<String, String>();
    }

    @Test
    public void testGetpythonServer() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get(new URI("http://wiki.nginx.org/Main"), emptyHeader,
                Proxy.NO_PROXY, new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(4000, TimeUnit.SECONDS));
    }

    @Test
    public void testSocksProxy() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get(new URI("http://feeds2.feedburner.com/dwahlin"),
                emptyHeader, socksProxy, new TextRespListener(
                        new TextHandler(cd)));
        Assert.assertTrue(cd.await(4000, TimeUnit.SECONDS));
    }

    @Test
    public void testGetGzipped() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get(new URI("http://en.wikipedia.org/wiki/HTTP"), emptyHeader,
                Proxy.NO_PROXY, new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(1000, TimeUnit.SECONDS));
    }

    @Test
    public void testDecodeSinaGzipped() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get(new URI("http://www.sina.com.cn/"), emptyHeader,
                Proxy.NO_PROXY, new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(4, TimeUnit.SECONDS));
    }

    @Test
    public void testAprotocolException() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        // http://blog.higher-order.net/feed/
        client.get(new URI("http://weblogs.asp.net/scottgu/rss.aspx"),
                emptyHeader, Proxy.NO_PROXY, new TextRespListener(
                        new TextHandler(cd)));
        Assert.assertTrue(cd.await(400, TimeUnit.SECONDS));
    }

    // { echo -ne "HTTP/1.0 200 OK\r\n\r\n"; cat project.clj; } | nc -l -p 8089
    @Test
    public void testDecodeNoLength() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get(new URI("http://127.0.0.1:8089"), emptyHeader,
                Proxy.NO_PROXY, new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(4, TimeUnit.SECONDS));
    }

    // {cat project.clj; } | nc -l -p 8089
    @Test
    public void testProtocolException() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        final CountDownLatch cd = new CountDownLatch(1);
        client.get(new URI("http://127.0.0.1:8089"), emptyHeader,
                Proxy.NO_PROXY, new TextRespListener(new TextHandler(cd)));
        Assert.assertTrue(cd.await(400, TimeUnit.SECONDS));
    }
}
