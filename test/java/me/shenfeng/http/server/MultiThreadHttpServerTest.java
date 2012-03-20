package me.shenfeng.http.server;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.shenfeng.http.codec.IHttpRequest;
import me.shenfeng.http.server.HttpServer;
import me.shenfeng.http.server.IHandler;
import me.shenfeng.http.server.IParamedRunnable;

class MultiThreadHandler implements IHandler {
    private ExecutorService exec;

    public MultiThreadHandler() {
        int core = 4; // Runtime.getRuntime().availableProcessors();
        exec = Executors.newFixedThreadPool(core);
    }

    public void handle(final IHttpRequest request, final IParamedRunnable callback) {
        exec.submit(new Runnable() {
            public void run() {
                callback.run(SingleThreadHandler.resp(request));
            }
        });
    }
}

public class MultiThreadHttpServerTest {

    public static void main(String[] args) throws IOException {
        // concurrency 1024, 2000000 request, time: 17814ms; 112271.25 req/s;
        // receive: 93M data; 5.22 M/s
        HttpServer server = new HttpServer("0.0.0.0", 9091, new MultiThreadHandler());
        server.start();
    }
}
