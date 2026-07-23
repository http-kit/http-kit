package org.httpkit.server;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import org.httpkit.LineTooLargeException;
import org.httpkit.ProtocolException;
import org.httpkit.RequestTooLargeException;
import org.httpkit.logger.ContextLogger;
import org.httpkit.logger.EventLogger;
import org.httpkit.logger.EventNames;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RingResponseTest {

    private static class CapturingCallback extends RespCallback {
        private byte[] bytes;
        private int responses;

        CapturingCallback() {
            super(null, null);
        }

        @Override
        public void run(ByteBuffer... buffers) {
            responses++;
            int length = 0;
            for (ByteBuffer buffer : buffers) {
                length += buffer.remaining();
            }
            bytes = new byte[length];
            int offset = 0;
            for (ByteBuffer buffer : buffers) {
                ByteBuffer copy = buffer.duplicate();
                int remaining = copy.remaining();
                copy.get(bytes, offset, remaining);
                offset += remaining;
            }
        }

        String response() {
            return new String(bytes);
        }

        int responses() {
            return responses;
        }
    }

    private HttpRequest request(String method)
            throws LineTooLargeException, ProtocolException, RequestTooLargeException {
        HttpDecoder decoder = new HttpDecoder(1024, 1024, ProxyProtocolOption.DISABLED, false);
        return decoder.decode(ByteBuffer.wrap((method + " / HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes()));
    }

    private String respond(HttpRequest request, final int status, final Object body, String serverHeader) {
        return respond(request, status, null, body, serverHeader);
    }

    private String respond(HttpRequest request, final int status,
                           final Map<String, Object> headers, final Object body,
                           String serverHeader) {
        return respond(request, status, headers, body, serverHeader, true);
    }

    private String respond(HttpRequest request, final int status,
                           final Map<String, Object> headers, final Object body,
                           String serverHeader, boolean legacyContentLength) {
        final Map<Keyword, Object> response = new HashMap<Keyword, Object>();
        response.put(ClojureRing.STATUS, status);
        response.put(ClojureRing.HEADERS, headers);
        response.put(ClojureRing.BODY, body);
        IFn handler = new AFn() {
            @Override
            public Object invoke(Object ignored) {
                return response;
            }
        };
        CapturingCallback callback = new CapturingCallback();
        new HttpHandler(request, callback, handler, false,
            ContextLogger.ERROR_PRINTER, EventLogger.NOP, EventNames.DEFAULT,
            serverHeader, legacyContentLength).run();
        return callback.response();
    }

    private int headerEnd(String response) {
        return response.indexOf("\r\n\r\n") + 4;
    }

    @Test
    public void headReportsLengthWithoutSendingBody() throws Exception {
        String response = respond(request("HEAD"), 200, "hello", "test-server");

        assertTrue(response.toLowerCase().contains("content-length: 5\r\n"));
        assertEquals(response.length(), headerEnd(response));
    }

    @Test
    public void bodyForbiddenStatusesNeverSendBody() throws Exception {
        int[] statuses = new int[]{101, 204, 205, 304};
        for (int status : statuses) {
            String response = respond(request("GET"), status, "hello", "test-server");
            assertEquals("status " + status, response.length(), headerEnd(response));
            assertFalse("status " + status, response.endsWith("hello"));
        }
    }

    @Test
    public void resetContentHasExplicitZeroLength() throws Exception {
        String response = respond(request("GET"), 205, "hello", "test-server");
        assertTrue(response.toLowerCase().contains("content-length: 0\r\n"));
    }

    @Test
    public void resetContentReplacesChunkedFraming() throws Exception {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("Transfer-Encoding", "chunked");
        headers.put("Content-Length", "5");

        String response = respond(request("GET"), 205, headers, "hello", "test-server");

        assertFalse(response.toLowerCase().contains("transfer-encoding:"));
        assertTrue(response.toLowerCase().contains("content-length: 0\r\n"));
        assertEquals(response.length(), headerEnd(response));
    }

    @Test
    public void bufferedResponsesUseActualBodyFraming() throws Exception {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("Transfer-Encoding", "chunked");
        headers.put("Content-Length", "999");

        String response = respond(request("GET"), 200, headers, "hello",
            "test-server", false);
        String responseHeaders = response.substring(0, headerEnd(response)).toLowerCase();

        assertFalse(responseHeaders.contains("transfer-encoding:"));
        assertTrue(responseHeaders.contains("content-length: 5\r\n"));
        assertEquals("hello", response.substring(headerEnd(response)));
    }

    @Test
    public void headMayReportExplicitRepresentationLength() throws Exception {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("Content-Length", "999");

        String response = respond(request("HEAD"), 200, headers, null,
            "test-server", false);

        assertTrue(response.toLowerCase().contains("content-length: 999\r\n"));
        assertEquals(response.length(), headerEnd(response));
    }

    @Test
    public void notModifiedMayReportRepresentationLength() throws Exception {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("Content-Length", "999");
        headers.put("Transfer-Encoding", "chunked");

        String response = respond(request("GET"), 304, headers, "ignored",
            "test-server", false);

        assertTrue(response.toLowerCase().contains("content-length: 999\r\n"));
        assertFalse(response.toLowerCase().contains("transfer-encoding:"));
        assertEquals(response.length(), headerEnd(response));
    }

    @Test
    public void errorHeadersAreIsolatedBetweenServers() throws Exception {
        IFn failingHandler = new AFn() {
            @Override
            public Object invoke(Object ignored) {
                throw new IllegalStateException("failure");
            }
        };
        ContextLogger<String, Throwable> logger = new ContextLogger<String, Throwable>() {
            @Override
            public void log(String message, Throwable error) {
            }
        };

        CapturingCallback first = new CapturingCallback();
        new HttpHandler(request("GET"), first, failingHandler, false, logger,
            EventLogger.NOP, EventNames.DEFAULT, "server-one", true).run();

        CapturingCallback second = new CapturingCallback();
        new HttpHandler(request("GET"), second, failingHandler, false, logger,
            EventLogger.NOP, EventNames.DEFAULT, "server-two", true).run();

        assertTrue(first.response().contains("Server: server-one\r\n"));
        assertTrue(second.response().contains("Server: server-two\r\n"));
        assertFalse(second.response().contains("Server: server-one\r\n"));
    }

    @Test
    public void headErrorReportsLengthWithoutSendingBody() throws Exception {
        IFn failingHandler = new AFn() {
            @Override
            public Object invoke(Object ignored) {
                throw new IllegalStateException("failure");
            }
        };
        ContextLogger<String, Throwable> logger = new ContextLogger<String, Throwable>() {
            @Override
            public void log(String message, Throwable error) {
            }
        };
        CapturingCallback callback = new CapturingCallback();

        new HttpHandler(request("HEAD"), callback, failingHandler, false, logger,
            EventLogger.NOP, EventNames.DEFAULT, "test-server", true).run();

        String response = callback.response();
        assertTrue(response.toLowerCase().contains("content-length: 7\r\n"));
        assertEquals(response.length(), headerEnd(response));
    }

    @Test
    public void nonKeepAliveErrorDeclaresConnectionClose() throws Exception {
        IFn failingHandler = new AFn() {
            @Override
            public Object invoke(Object ignored) {
                throw new IllegalStateException("failure");
            }
        };
        ContextLogger<String, Throwable> logger = new ContextLogger<String, Throwable>() {
            @Override
            public void log(String message, Throwable error) {
            }
        };
        HttpRequest request = request("GET");
        request.isKeepAlive = false;
        CapturingCallback callback = new CapturingCallback();

        new HttpHandler(request, callback, failingHandler, false, logger,
            EventLogger.NOP, EventNames.DEFAULT, "test-server", true).run();

        assertTrue(callback.response().contains("Connection: Close\r\n"));
    }

    @Test
    public void asyncHandlersCompleteOnlyOnce() throws Exception {
        final Map<Keyword, Object> response = new HashMap<Keyword, Object>();
        response.put(ClojureRing.STATUS, 200);
        response.put(ClojureRing.BODY, "first");
        IFn handler = new AFn() {
            @Override
            public Object invoke(Object ignored, Object respond, Object raise) {
                ((IFn) respond).invoke(response);
                ((IFn) respond).invoke(response);
                ((IFn) raise).invoke(new IllegalStateException("late error"));
                throw new IllegalStateException("late throw");
            }
        };
        CapturingCallback callback = new CapturingCallback();

        new HttpHandler(request("GET"), callback, handler, true,
            ContextLogger.ERROR_PRINTER, EventLogger.NOP, EventNames.DEFAULT,
            "test-server", true).run();

        assertEquals(1, callback.responses());
        assertTrue(callback.response().startsWith("HTTP/1.1 200 "));
        assertTrue(callback.response().endsWith("first"));
    }
}
