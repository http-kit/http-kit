package org.httpkit.server;

import org.httpkit.HeaderMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.httpkit.HttpUtils.HttpEncode;

class MultiThreadHandler implements IHandler {
    private ExecutorService exec;

    public MultiThreadHandler() {
        int core = 6; // Runtime.getRuntime().availableProcessors();
        exec = Executors.newFixedThreadPool(core);
    }

    public void close(int timeoutMs) {

    }

    @Override
    public void handle(AsyncChannel channel, Frame frame) {

    }

    public void handle(HttpRequest request, final RespCallback callback) {
        exec.submit(new Runnable() {
            public void run() {
                HeaderMap header = new HeaderMap();
                header.put("Connection", "Keep-Alive");
                ByteBuffer[] bytes = HttpEncode(200, header, SingleThreadHandler.body);
                callback.run(bytes);
            }
        });
    }

    public void handle(AsyncChannel channel, Frame.TextFrame frame) {
    }

    public void clientClose(AsyncChannel channel, int status) {
    }
}

public class MultiThreadHttpServerTest {
    public static void main(String[] args) throws IOException {
        HttpServer server = new HttpServer("0.0.0.0", 9091, new MultiThreadHandler(), 20480,
                2048, 1024 * 1024 * 4);
        server.start();
        System.out.println("Server started on :9091");
    }
}
