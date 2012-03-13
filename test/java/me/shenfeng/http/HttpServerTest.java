package me.shenfeng.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.shenfeng.http.codec.IHttpRequest;

import org.junit.Test;

public class HttpServerTest {

    @Test
    public void testMultiThreadServer() throws IOException {
        // concurrency 1024, 2000000 request, time: 16609ms; 120416.64 req/s;
        // receive: 93M data; 5.60 M/s
        HttpServer server = new HttpServer("0.0.0.0", 9091, new MultiThreadHandler());
        server.start();
    }

    @Test
    public void testSingleThreadServer() throws IOException {
        // concurrency 1024, 2000000 request, time: 14294ms; 139918.85 req/s;
        // receive: 93M data; 6.51 M/s
        HttpServer server = new HttpServer("0.0.0.0", 9091, new SingleThreadHandler());
        server.start();
    }
}

class MultiThreadHandler implements IHandler {
    private ExecutorService exec;

    public MultiThreadHandler() {
        int core = 4; // Runtime.getRuntime().availableProcessors();
        exec = Executors.newFixedThreadPool(core);
    }

    public void handle(IHttpRequest request, final IParamedRunnable callback) {
        exec.submit(new Runnable() {
            public void run() {
                ByteBuffer buffer = ByteBuffer
                        .wrap("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789"
                                .getBytes());

                callback.run(buffer);
            }
        });
    }
}

class SingleThreadHandler implements IHandler {
    public void handle(IHttpRequest request, IParamedRunnable callback) {
        ByteBuffer buffer = ByteBuffer
                .wrap("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789"
                        .getBytes());

        callback.run(buffer);
    }
}
