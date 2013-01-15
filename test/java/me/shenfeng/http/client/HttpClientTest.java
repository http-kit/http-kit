package me.shenfeng.http.client;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;
import me.shenfeng.http.HttpMethod;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

class ResponseHandler implements IResponseHandler {

    private final CountDownLatch cd;

    public ResponseHandler(CountDownLatch cd) {
        this.cd = cd;
    }

    public void onSuccess(int status, Map<String, String> headers, Object body) {
        try {
            if (body instanceof String) {
                // String
                String b = (String) body;
                IOUtils.write((String) b, new FileOutputStream("/tmp/file"));
                System.out.println("status: " + status + "; body str length: " + b.length());
            } else {
                // Binary
                System.out.println("status: " + status + "; body: " + body);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onThrowable(Throwable t) {
        t.printStackTrace();
        cd.countDown();
    }

}

public class HttpClientTest {

    public HttpClientTest() throws IOException {
        String userAgent = "Mozilla/5.0 (compatible; Rssminer/1.0; +http://rssminer.net)";
        HttpClientConfig cfg = new HttpClientConfig(45000, userAgent, 100000);

        client = new HttpClient(cfg);
    }

    HttpClient client;
    Map<String, String> emptyHeader;
    CountDownLatch cd;
    Proxy socksProxy = new Proxy(Type.SOCKS, new InetSocketAddress("127.0.0.1", 3128));
    private RespListener listener;

    @Before
    public void setup() throws IOException {
        emptyHeader = new TreeMap<String, String>();
        cd = new CountDownLatch(1);
        listener = new RespListener(new ResponseHandler(cd));
    }

    @After
    public void tearDown() throws InterruptedException {
        Assert.assertTrue(cd.await(4000, TimeUnit.SECONDS));
    }

    public void get(String url) throws URISyntaxException {
        client.exec(url, HttpMethod.GET, emptyHeader, null, -1, listener);
    }

    @Test
    public void testGetpythonServer() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://wiki.nginx.org/Main");
    }

    @Test
    public void testSocksProxy() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://feeds2.feedburner.com/dwahlin");
    }

    @Test
    public void testGetGzipped() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://en.wikipedia.org/wiki/HTTP");
    }

    @Test
    public void testDecodeSinaGzipped() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://www.sina.com.cn/");
    }

    @Test
    public void testAprotocolException() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://weblogs.asp.net/scottgu/rss.aspx");
        // http://blog.higher-order.net/feed/
    }

    // { echo -ne "HTTP/1.0 200 OK\r\n\r\n"; cat project.clj; } | nc -l -p 8089
    @Test
    public void testDecodeNoLength() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://127.0.0.1:8089");
    }

    // {cat project.clj; } | nc -l -p 8089
    @Test
    public void testProtocolException() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://127.0.0.1:8089");
    }
}
