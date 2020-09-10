package org.httpkit.server;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;
import org.httpkit.RequestTooLargeException;
import org.httpkit.server.MockClojureHandler;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;

public class RingHandlerTest {

    private HttpDecoder httpDecoder = new HttpDecoder(8388608, 4096, ProxyProtocolOption.DISABLED);

    @Test
    public void shouldUseExternalThreadPoolForExecution() throws InterruptedException, ProtocolException, LineTooLargeException, RequestTooLargeException {
        Vector<String> assertionItems = new Vector<String>();

        ExecutorService testWorkerPool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(2));
        RingHandler ringHandler = new RingHandler(new MockClojureHandler(aDummyResponse()), testWorkerPool);
        ringHandler.handle(aDummyRequest(), new MockRespCallback(assertionItems));

        Thread.sleep(50);

        assertDummyRequestReceived(assertionItems);
    }

    @Test
    public void shouldUseInternalThreadPoolForExecution() throws InterruptedException, ProtocolException, LineTooLargeException, RequestTooLargeException {
        Vector<String> assertionItems = new Vector<String>();

        RingHandler ringHandler = new RingHandler(1, new MockClojureHandler(aDummyResponse()), "some-prefix", 2, "http-kit");
        ringHandler.handle(aDummyRequest(), new MockRespCallback(assertionItems));

        Thread.sleep(50);

        assertDummyRequestReceived(assertionItems);
    }


    public class MockRespCallback extends RespCallback {
        private Vector<String> storage;

        public MockRespCallback(Vector<String> storage) {
            super(null, null);
            this.storage = storage;
        }

        @Override
        public void run(ByteBuffer... buffers) {
            StringBuilder builder = new StringBuilder();
            for (ByteBuffer buffer : buffers) {
                builder.append(new String(buffer.array()));
            }
            storage.add(builder.toString());
        }
    }

    private HttpRequest asHttpRequest(String... requestLines) throws ProtocolException, LineTooLargeException, RequestTooLargeException {
        httpDecoder.reset();
        
        // String joinedRequest = String.join("\n", requestLines); 
        // String.join is was only released in 1.8
        
        String joinedRequest = "";
        int requestLength = requestLines.length;

        if (requestLines != null && requestLength > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < requestLength; i++) {

                sb.append(requestLines[i]);

                if (i != requestLength - 1) {
                    sb.append("\n");
                }

            }
            joinedRequest = sb.toString();
        }
        return httpDecoder.decode(ByteBuffer.wrap((joinedRequest + "\n\n").getBytes()));
    }

    private IPersistentMap aDummyResponse() {
        Map<Object, Object> m = new TreeMap<Object, Object>();
        m.put(ClojureRing.BODY, "DUMMY_RESPONSE");
        m.put(ClojureRing.STATUS, 301);
        return PersistentArrayMap.create(m);
    }

    private HttpRequest aDummyRequest() throws ProtocolException, LineTooLargeException, RequestTooLargeException {
        return asHttpRequest(
                "GET /foo/bar?query=baz HTTP/1.0",
                "Host: github.com/http-kit/http-kit",
                "Content-Type: text/html",
                "x-forwarded-for: 0.0.0.0:80");
    }

    private void assertDummyRequestReceived(Vector<String> assertionItems) {
        Assert.assertThat("should return only one single response", assertionItems.size(), is(1));
        String element = assertionItems.firstElement();
        Assert.assertThat("should write dummy-response as a side-effect", element, containsString("DUMMY_RESPONSE"));
        Assert.assertThat("should write dummy-response with correct status code", element, containsString("HTTP/1.1 301 Moved Permanently"));
    }
}
