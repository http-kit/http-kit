package org.httpkit.client;

import junit.framework.Assert;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.Proxy.Type;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class ResponseHandler implements IResponseHandler {

    private final CountDownLatch cd;

    public ResponseHandler(CountDownLatch cd) {
        this.cd = cd;
    }

    public void onSuccess(int status, Map<String, Object> headers, Object body) {
        cd.countDown();
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

class A {

}

class B extends A {

}


public class HttpClientTest {

    public HttpClientTest() throws IOException {
        String userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.19 Safari/537.31";
        client = new HttpClient();
    }

    HttpClient client;
    Map<String, Object> emptyHeader;
    CountDownLatch cd;
    Proxy socksProxy = new Proxy(Type.SOCKS, new InetSocketAddress("127.0.0.1", 3128));
    private RespListener listener;

    @Before
    public void setup() throws IOException {
        cd = new CountDownLatch(1);
        listener = new RespListener(new ResponseHandler(cd), IFilter.ACCEPT_ALL,
                Executors.newCachedThreadPool(), 0);
    }

    @After
    public void tearDown() throws InterruptedException {
        Assert.assertTrue(cd.await(4000, TimeUnit.SECONDS));
    }

    public void get(String url) throws URISyntaxException {
        client.exec(url, new RequestConfig(), null, listener);
    }

    @Test
    public void testGetpythonServer() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        get("http://wiki.nginx.org/Main");
    }


    public static void main(String[] args) throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {
        KeyStore ks = KeyStore.getInstance("JKS");
        char[] passphrase = "123456".toCharArray();
        ks.load(new FileInputStream("/tmp/testkeys"), passphrase);

    }

    @Test
    public void testHttpS() throws URISyntaxException, InterruptedException {
//        get("https://d.web2.qq.com");
        get("https://github.com/http-kit/http-kit");

        cd.await();
        get("https://github.com/shenfeng/FrameworkBenchmarks");


//        get("https://google.com");
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
