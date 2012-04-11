package me.shenfeng.http.client;

import java.io.IOException;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.codec.HttpStatus;
import me.shenfeng.http.codec.HttpVersion;

import org.junit.Before;
import org.junit.Test;

class Handler implements IEventListener {

    public int onInitialLineReceived(HttpVersion version, HttpStatus status) {
        System.out.println(status);
        return 0;
    }

    public int onHeadersReceived(Map<String, String> headers) {
        System.out.println(headers);
        return 0;
    }

    public int onBodyReceived(byte[] buf, int length) {
        System.out.println(length);
        return 0;
    }

    public void onCompleted() {
        System.out.println("conComplete");
    }

    public void onThrowable(Throwable t) {
        t.printStackTrace();
    }
}

public class HttpClientTest {

    HttpClient client;

    @Before
    public void setup() throws IOException {
        HttpClientConfig config = new HttpClientConfig();
        client = new HttpClient(config);
    }

    @Test
    public void testGetpythonServer() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        Map<String, String> headers = new TreeMap<String, String>();
        client.get("http://wiki.nginx.org/Main", headers, Proxy.NO_PROXY,
                new Handler());
        Thread.sleep(10000000);
    }

    @Test
    public void testGetGzipped() throws UnknownHostException,
            URISyntaxException, InterruptedException {
        Map<String, String> headers = new TreeMap<String, String>();
        client.get("http://en.wikipedia.org/wiki/HTTP", headers,
                Proxy.NO_PROXY, new Handler());
        Thread.sleep(10000000);
    }
}
