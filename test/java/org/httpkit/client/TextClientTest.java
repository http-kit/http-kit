package org.httpkit.client;

import org.httpkit.HttpMethod;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TextClientTest {
    HttpClient client;

    @Before
    public void setup() throws IOException {
        client = new HttpClient();
    }

    @Test
    public void testAbort() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        String[] urls = new String[]{"http://cdn-smooth.ms-studiosmedia.com/news/mp4_mq/06182012_Surface_750k.mp4",};
        runIt(urls);
    }

    @Test
    public void testDecode() throws IOException, URISyntaxException, InterruptedException {
        String[] urls = new String[]{"http://feed.feedsky.com/amaze",
                "http://macorz.cn/feed", "http://www.ourlinux.net/feed",
                "http://blog.jjgod.org/feed/", "http://www.lostleon.com/blog/feed/",
                "http://feed.feedsky.com/hellodb"};

        runIt(urls);

    }

    private void runIt(String[] urls) throws URISyntaxException, UnknownHostException,
            InterruptedException {
        ExecutorService pool = Executors.newCachedThreadPool();
        for (String url : urls) {
            IResponseHandler handler = new IResponseHandler() {

                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }

                public void onSuccess(int status, Map<String, Object> headers, Object body) {
                    System.out.println(body);
                }
            };
            client.exec(url, new RequestConfig(), null, new RespListener(handler,
                    IFilter.ACCEPT_ALL, pool, 1));
        }

    }
}
