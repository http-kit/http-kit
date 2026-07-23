package org.httpkit.server;

import org.httpkit.HeaderMap;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.httpkit.HttpUtils.HttpEncode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpServerProtocolTest {

    private static int count(String value, String needle) {
        int found = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            found++;
            offset += needle.length();
        }
        return found;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        while ((read = input.read(bytes)) != -1) {
            response.write(bytes, 0, read);
        }
        return response.toByteArray();
    }

    private static final IHandler NOOP_HANDLER = new IHandler() {
        @Override
        public void handle(HttpRequest request, RespCallback callback) {
        }

        @Override
        public void handle(AsyncChannel channel, Frame frame) {
        }

        @Override
        public void clientClose(AsyncChannel channel, int status) {
        }

        @Override
        public void clientClose(AsyncChannel channel, int status, String reason) {
        }

        @Override
        public void close(int timeoutMs) {
        }
    };

    @Test
    public void drainsProtocolErrorResponseBeforeClosing() throws Exception {
        int lineLength = 2 * 1024 * 1024;
        HttpServer server = new HttpServer("127.0.0.1", 0, NOOP_HANDLER,
            1024, lineLength + 1024, 1024, ProxyProtocolOption.ENABLED);
        server.start();

        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(10000);
            try {
                OutputStream output = socket.getOutputStream();
                output.write("PROXY ".getBytes());
                byte[] block = new byte[8192];
                for (int i = 0; i < block.length; i++) {
                    block[i] = 'x';
                }
                int remaining = lineLength;
                while (remaining > 0) {
                    int write = Math.min(remaining, block.length);
                    output.write(block, 0, write);
                    remaining -= write;
                }
                output.write("\r\n".getBytes());
                output.flush();

                ByteArrayOutputStream response = new ByteArrayOutputStream();
                InputStream input = socket.getInputStream();
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    response.write(bytes, 0, read);
                }

                String responseHead = new String(response.toByteArray(), 0,
                    Math.min(response.size(), 1024)).toLowerCase();
                int headerEnd = responseHead.indexOf("\r\n\r\n") + 4;
                int lengthStart = responseHead.indexOf("content-length: ") + "content-length: ".length();
                int lengthEnd = responseHead.indexOf("\r\n", lengthStart);
                int contentLength = Integer.parseInt(responseHead.substring(lengthStart, lengthEnd));

                assertTrue(responseHead.startsWith("http/1.1 400"));
                assertTrue(headerEnd > 3);
                assertEquals(contentLength, response.size() - headerEnd);
            } finally {
                socket.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) {
                server.stop(1000);
            }
            server.join();
        }
    }

    @Test
    public void serializesPipelinedRequestsThroughAsyncStreamCompletion() throws Exception {
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch thirdStarted = new CountDownLatch(1);
        final AtomicReference<AsyncChannel> firstChannel = new AtomicReference<AsyncChannel>();

        IHandler handler = new IHandler() {
            @Override
            public void handle(HttpRequest request, RespCallback callback) {
                if ("/first".equals(request.uri)) {
                    firstChannel.set(request.channel);
                    try {
                        request.channel.send("first", false);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    firstStarted.countDown();
                } else if ("/second".equals(request.uri)) {
                    secondStarted.countDown();
                    callback.run(HttpEncode(200, new HeaderMap(), "second"));
                } else {
                    thirdStarted.countDown();
                    HeaderMap headers = new HeaderMap();
                    headers.put("Connection", "Close");
                    callback.run(HttpEncode(200, headers, "third"));
                }
            }

            @Override
            public void handle(AsyncChannel channel, Frame frame) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status, String reason) {
            }

            @Override
            public void close(int timeoutMs) {
            }
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();

        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(10000);
            try {
                OutputStream output = socket.getOutputStream();
                output.write(
                    "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                output.flush();

                assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
                output.write((
                    "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /third HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                output.flush();

                assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));
                assertTrue(firstChannel.get().send("done", true));
                assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
                assertTrue(thirdStarted.await(2, TimeUnit.SECONDS));

                String response = new String(readAll(socket.getInputStream()), StandardCharsets.US_ASCII);
                assertTrue(response.contains("Transfer-Encoding: chunked"));
                assertTrue(response.contains(
                    "5\r\nfirst\r\n4\r\ndone\r\n0\r\n\r\nHTTP/1.1 200"));
                assertTrue(response.contains("\r\n\r\nsecondHTTP/1.1 200"));
                assertTrue(response.endsWith("third"));
            } finally {
                socket.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) {
                server.stop(1000);
            }
            server.join();
        }
    }

    @Test
    public void streamsHttp10WithCloseDelimitedBody() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<AsyncChannel> channel = new AtomicReference<AsyncChannel>();

        IHandler handler = new IHandler() {
            @Override
            public void handle(HttpRequest request, RespCallback callback) {
                channel.set(request.channel);
                try {
                    request.channel.send("alpha", false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                started.countDown();
            }

            @Override
            public void handle(AsyncChannel channel, Frame frame) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status, String reason) {
            }

            @Override
            public void close(int timeoutMs) {
            }
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();

        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(10000);
            try {
                OutputStream output = socket.getOutputStream();
                output.write((
                    "GET /stream HTTP/1.0\r\nConnection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                output.flush();

                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(channel.get().send("beta", true));

                String response = new String(readAll(socket.getInputStream()), StandardCharsets.US_ASCII);
                int bodyStart = response.indexOf("\r\n\r\n") + 4;
                String headers = response.substring(0, bodyStart).toLowerCase();
                assertFalse(headers.contains("transfer-encoding:"));
                assertFalse(headers.contains("content-length:"));
                assertTrue(headers.contains("connection: close"));
                assertEquals("alphabeta", response.substring(bodyStart));
            } finally {
                socket.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) {
                server.stop(1000);
            }
            server.join();
        }
    }

    @Test
    public void preservesFrameCoalescedWithWebSocketUpgrade() throws Exception {
        final CountDownLatch frameReceived = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<String>();

        IHandler handler = new IHandler() {
            @Override
            public void handle(HttpRequest request, RespCallback callback) {
                Map<String, Object> headers = new HashMap<String, Object>();
                headers.put("Upgrade", "websocket");
                headers.put("Connection", "Upgrade");
                headers.put("Sec-WebSocket-Accept", "test");
                request.channel.sendHandshake(headers);
            }

            @Override
            public void handle(AsyncChannel channel, Frame frame) {
                text.set(((Frame.TextFrame) frame).getText());
                frameReceived.countDown();
            }

            @Override
            public void clientClose(AsyncChannel channel, int status) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status, String reason) {
            }

            @Override
            public void close(int timeoutMs) {
            }
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();

        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(10000);
            try {
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                request.write((
                    "GET /ws HTTP/1.1\r\nHost: localhost\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                request.write(new byte[]{(byte) 0x81, (byte) 0x81, 1, 2, 3, 4, (byte) ('x' ^ 1)});
                socket.getOutputStream().write(request.toByteArray());
                socket.getOutputStream().flush();

                assertTrue(frameReceived.await(2, TimeUnit.SECONDS));
                assertEquals("x", text.get());
            } finally {
                socket.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) {
                server.stop(1000);
            }
            server.join();
        }
    }

    @Test
    public void drainsRejectedWebSocketUpgradeAndCloses() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        final byte[] body = new byte[2 * 1024 * 1024];

        IHandler handler = new IHandler() {
            @Override
            public void handle(HttpRequest request, RespCallback callback) {
                requests.incrementAndGet();
                HeaderMap headers = new HeaderMap();
                headers.put("Connection", request.isKeepAlive ? "Keep-Alive" : "Close");
                callback.run(HttpEncode(400, headers, body));
            }

            @Override
            public void handle(AsyncChannel channel, Frame frame) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status, String reason) {
            }

            @Override
            public void close(int timeoutMs) {
            }
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();

        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(10000);
            try {
                socket.getOutputStream().write((
                    "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n\r\n" +
                    "GET /must-not-run HTTP/1.1\r\nHost: localhost\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                byte[] response = readAll(socket.getInputStream());
                String head = new String(response, 0, 256, StandardCharsets.US_ASCII).toLowerCase();
                int headerEnd = head.indexOf("\r\n\r\n") + 4;

                assertTrue(head.startsWith("http/1.1 400"));
                assertTrue(head.contains("connection: close"));
                assertEquals(body.length, response.length - headerEnd);
                assertEquals(1, requests.get());
            } finally {
                socket.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) {
                server.stop(1000);
            }
            server.join();
        }
    }

    @Test
    public void detectsClientCloseDuringAsyncResponse() throws Exception {
        final CountDownLatch requestStarted = new CountDownLatch(1);
        final CountDownLatch clientClosed = new CountDownLatch(1);

        IHandler handler = new IHandler() {
            @Override
            public void handle(HttpRequest request, RespCallback callback) {
                requestStarted.countDown();
            }

            @Override
            public void handle(AsyncChannel channel, Frame frame) {
            }

            @Override
            public void clientClose(AsyncChannel channel, int status) {
                clientClosed.countDown();
            }

            @Override
            public void clientClose(AsyncChannel channel, int status, String reason) {
                clientClosed.countDown();
            }

            @Override
            public void close(int timeoutMs) {
            }
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();

        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.getOutputStream().write(
                "GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));

            socket.close();
            assertTrue(clientClosed.await(2, TimeUnit.SECONDS));
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) {
                server.stop(1000);
            }
            server.join();
        }
    }

    @Test
    public void concurrentStreamingWritesEmitOneResponseHead() throws Exception {
        IHandler handler = new IHandler() {
            @Override
            public void handle(final HttpRequest request, RespCallback callback) {
                Thread[] writers = new Thread[8];
                for (int i = 0; i < writers.length; i++) {
                    writers[i] = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                request.channel.send("x", false);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                    writers[i].start();
                }
                for (Thread writer : writers) {
                    try {
                        writer.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                try {
                    request.channel.send(null, true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override public void handle(AsyncChannel channel, Frame frame) {}
            @Override public void clientClose(AsyncChannel channel, int status) {}
            @Override public void clientClose(AsyncChannel channel, int status, String reason) {}
            @Override public void close(int timeoutMs) {}
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();
        try {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(10000);
            try {
                socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                String response = new String(readAll(socket.getInputStream()), StandardCharsets.US_ASCII);
                assertEquals(1, count(response, "HTTP/1.1 200"));
                assertEquals(8, count(response, "1\r\nx\r\n"));
            } finally {
                socket.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) server.stop(1000);
            server.join();
        }
    }

    @Test
    public void serverLoopRecoversFromRequestRuntimeException() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        IHandler handler = new IHandler() {
            @Override
            public void handle(HttpRequest request, RespCallback callback) {
                if (requests.incrementAndGet() == 1) {
                    throw new RuntimeException("first request failed");
                }
                callback.run(HttpEncode(200, new HeaderMap(), "ok"));
            }

            @Override public void handle(AsyncChannel channel, Frame frame) {}
            @Override public void clientClose(AsyncChannel channel, int status) {}
            @Override public void clientClose(AsyncChannel channel, int status, String reason) {}
            @Override public void close(int timeoutMs) {}
        };

        HttpServer server = new HttpServer("127.0.0.1", 0, handler,
            1024, 1024, 1024, ProxyProtocolOption.DISABLED);
        server.start();
        try {
            Socket first = new Socket("127.0.0.1", server.getPort());
            first.getOutputStream().write(
                "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            first.getOutputStream().flush();
            Thread.sleep(100);
            first.close();

            Socket second = new Socket("127.0.0.1", server.getPort());
            second.setSoTimeout(2000);
            try {
                second.getOutputStream().write(
                    "GET /second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                String response = new String(readAll(second.getInputStream()), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200"));
                assertEquals(HttpServer.Status.RUNNING, server.getStatus());
            } finally {
                second.close();
            }
        } finally {
            if (server.getStatus() == HttpServer.Status.RUNNING) server.stop(1000);
            server.join();
        }
    }
}
