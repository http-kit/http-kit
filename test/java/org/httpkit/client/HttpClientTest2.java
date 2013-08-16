package org.httpkit.client;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class HttpClientTest2 {

    public static void main(String[] args) throws IOException, InterruptedException {
        HttpClient httpClient = new HttpClient(
        );

        ExecutorService pool = Executors.newCachedThreadPool();

        for (int i = 0; i < 5; i++) {
            final CountDownLatch c = new CountDownLatch(1);

            IResponseHandler handler = new IResponseHandler() {

                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                    c.countDown();
                }

                public void onSuccess(int status, Map<String, Object> headers, Object body) {
                    System.out.println(status);
                    System.out.println(headers);
                    System.out.println(body);
                    c.countDown();
                }
            };

            httpClient.exec("http://wc31415.blogcn.com/articles/%E4%B8%8A%E3%80%8A%E4%B8%AD%E5%9B%BD%E9%9D%92%E5%B9%B4%E6%8A%A5%E3%80%8B%E4%BA%86%EF%BC%8C%E5%A5%BD%E5%83%8F%E6%98%AF%E4%B8%AA%E5%A4%A7%E6%8A%A5%E3%80%82.html",
                    new RequestConfig(), null, new RespListener(handler,
                    IFilter.ACCEPT_ALL, pool, 1));
            System.out.println("-----------------------");
            c.await();
        }

    }
}
