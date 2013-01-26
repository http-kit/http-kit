package org.httpkit.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.httpkit.server.ClojureRing;
import org.httpkit.server.HttpRequest;
import org.httpkit.server.HttpServer;
import org.httpkit.server.IHandler;
import org.httpkit.server.ResponseCallback;
import org.httpkit.ws.WSFrame;
import org.httpkit.ws.WsCon;


class MultiThreadHandler implements IHandler {
    private ExecutorService exec;

    public MultiThreadHandler() {
        int core = 6; // Runtime.getRuntime().availableProcessors();
        exec = Executors.newFixedThreadPool(core);
    }

    public void close() {

    }

    public void handle(HttpRequest request, final ResponseCallback callback) {
        exec.submit(new Runnable() {
            public void run() {
                Map<String, Object> header = new TreeMap<String, Object>();
                header.put("Connection", "Keep-Alive");
                ByteBuffer[] bytes = ClojureRing.encode(200, header, SingleThreadHandler.body);
                callback.run(bytes);
            }
        });
    }

    public void handle(WsCon con, WSFrame frame) {
    }
}

public class MultiThreadHttpServerTest {
    public static void main(String[] args) throws IOException {
        HttpServer server = new HttpServer("0.0.0.0", 9091, new MultiThreadHandler(), 20480,
                2048);
        server.start();
        System.out.println("Server started on :9091");
    }
}
