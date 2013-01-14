package me.shenfeng.http.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

import me.shenfeng.http.HttpMethod;

import org.junit.Before;
import org.junit.Test;

public class TextClientTest {
    HttpClient client;

    @Before
    public void setup() throws IOException {
        client = new HttpClient(new HttpClientConfig(40000, "user-agent", 40000));
    }

    @Test
    public void testAbort() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        String[] urls = new String[] { "http://cdn-smooth.ms-studiosmedia.com/news/mp4_mq/06182012_Surface_750k.mp4", };
        runIt(urls);
    }

    @Test
    public void testDecode() throws IOException, URISyntaxException, InterruptedException {
        String[] urls = new String[] { "http://feed.feedsky.com/amaze",
                "http://macorz.cn/feed", "http://www.ourlinux.net/feed",
                "http://blog.jjgod.org/feed/", "http://www.lostleon.com/blog/feed/",
                "http://feed.feedsky.com/hellodb" };

        // urls = new String[] {
        // "http://finance.sina.com.cn/stock/jsy/20120709/183612517402.shtml" };

        runIt(urls);

    }

    private void runIt(String[] urls) throws URISyntaxException, UnknownHostException,
            InterruptedException {
        CountDownLatch latch = new CountDownLatch(urls.length);
        for (String url : urls) {
            TreeMap<String, String> header = new TreeMap<String, String>();
            URI uri = new URI(url);
            client.exec(uri, HttpMethod.GET, header, null, -1, new RespListener(
                    new IResponseHandler() {

                        public void onThrowable(Throwable t) {
                            t.printStackTrace();
                        }

                        public void onSuccess(int status, Map<String, String> headers,
                                Object body) {
                            System.out.println(body);
                        }
                    }));
        }

        latch.await();
    }
}
