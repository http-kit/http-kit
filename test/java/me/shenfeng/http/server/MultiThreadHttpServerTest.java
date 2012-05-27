package me.shenfeng.http.server;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class MultiThreadHandler implements IHandler {
    private ExecutorService exec;

    public MultiThreadHandler() {
        int core = 6; // Runtime.getRuntime().availableProcessors();
        exec = Executors.newFixedThreadPool(core);
    }

    public void handle(final HttpRequest request, final IResponseCallback cb) {
        exec.submit(new Runnable() {
            public void run() {
                Map<String, Object> header = new TreeMap<String, Object>();
                header.put("Connection", "Keep-Alive");
                cb.run(200, header, SingleThreadHandler.body);
            }
        });
    }

    public void close() {

    }
}

public class MultiThreadHttpServerTest {

    public static void main(String[] args) throws IOException {
        HttpServer server = new HttpServer("0.0.0.0", 9091,
                new MultiThreadHandler(), 20480);
        server.start();
    }
}
