package org.httpkit.client;

import org.httpkit.BytesInputStream;
import org.httpkit.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created with IntelliJ IDEA.
 * User: feng
 * Date: 4/14/13
 * Time: 10:22 AM
 * To change this template use File | Settings | File Templates.
 */
public class HttpsClientTest {

    public static final String AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_0) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.65 Safari/537.31";
    private static Logger logger = LoggerFactory.getLogger(HttpsClientTest.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        HttpClient client = new HttpClient();

        String[] urls = new String[]{
                "https://localhost:9898/spec",
//                "https://localhost:9898/file?l=121021"
//                "https://github.com/http-kit/http-kit",
//                "https://github.com/shenfeng/FrameworkBenchmarks"
        };

        ExecutorService pool = Executors.newCachedThreadPool();
        for (String url : urls) {
            final CountDownLatch cd = new CountDownLatch(1);
            SSLEngine engine = SslContextFactory.getClientContext().createSSLEngine();
//            engine = null;
            RequestConfig cfg = new RequestConfig(HttpMethod.POST, null, null, 40000, -1);
            TreeMap<String, Object> headers = new TreeMap<String, Object>();
            for (int i = 0; i < 33; i++) {
                headers.put("X-long-header" + i, AGENT + AGENT + AGENT + AGENT);
            }
            headers.put("User-Agent", AGENT);
            StringBuilder body = new StringBuilder(16 * 1024);
            for (int i = 0; i < 16 * 1024; ++i) {
                body.append(i);
            }
            client.exec(url, cfg, null, new RespListener(new IResponseHandler() {
                public void onSuccess(int status, Map<String, Object> headers, Object body) {
                    int length = body instanceof String ? ((String) body).length() :
                            ((BytesInputStream) body).available();
                    System.out.println(body);
                    logger.info("{}, {}, {}", status, headers, length);
                    cd.countDown();
                }

                public void onThrowable(Throwable t) {
                    logger.error("error", t);
                    cd.countDown();
                }
            }, IFilter.ACCEPT_ALL, pool, 1));
            cd.await();
        }
    }
}
