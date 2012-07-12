package me.shenfeng.http.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.client.TextRespListener.IFilter;

import org.junit.Before;
import org.junit.Test;

class TextHandler2 implements ITextHandler {

    private CountDownLatch latch;

    public TextHandler2(CountDownLatch latch) {
        this.latch = latch;
    }

    public void onSuccess(int status, Map<String, String> headers, String body) {
        System.out.println(body.length());
        latch.countDown();
    }

    public void onThrowable(Throwable t) {
        t.printStackTrace();
        latch.countDown();
    }

}

public class TextClientTest {
    HttpClient client;

    IFilter filter = new IFilter() {
        public boolean accept(Map<String, String> headers) {
            String ct = headers.get(HttpUtils.CONTENT_TYPE);
            if (ct != null) {
                ct = ct.toLowerCase();
                return ct.indexOf("text") != -1 || ct.indexOf("xml") != -1;
            }
            return false;
        }

        public boolean accept(DynamicBytes partialBody) {
            if (partialBody.length() > 1024 * 1024) {
                return false;
            }
            return true;
        }
    };

    @Before
    public void setup() throws IOException {
        client = new HttpClient(new HttpClientConfig());
    }

    @Test
    public void testAbort() throws UnknownHostException, URISyntaxException,
            InterruptedException {
        String[] urls = new String[] { "http://cdn-smooth.ms-studiosmedia.com/news/mp4_mq/06182012_Surface_750k.mp4", };
        runIt(urls);
    }

    @Test
    public void testDecode() throws IOException, URISyntaxException,
            InterruptedException {
        String[] urls = new String[] { "http://feed.feedsky.com/amaze",
                "http://macorz.cn/feed", "http://www.ourlinux.net/feed",
                "http://blog.jjgod.org/feed/",
                "http://www.lostleon.com/blog/feed/",
                "http://feed.feedsky.com/hellodb" };

        // urls = new String[] {
        // "http://finance.sina.com.cn/stock/jsy/20120709/183612517402.shtml" };

        runIt(urls);

    }

    private void runIt(String[] urls) throws URISyntaxException,
            UnknownHostException, InterruptedException {
        CountDownLatch latch = new CountDownLatch(urls.length);
        for (String url : urls) {
            TreeMap<String, String> header = new TreeMap<String, String>();
            URI uri = new URI(url);
            TextRespListener listener = new TextRespListener(
                    new TextHandler2(latch), filter);
            client.get(uri, header, listener);
        }

        latch.await();
    }
}
