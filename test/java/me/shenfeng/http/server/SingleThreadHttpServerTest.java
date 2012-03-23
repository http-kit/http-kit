package me.shenfeng.http.server;

import java.io.IOException;

import me.shenfeng.http.codec.DefaultHttpResponse;
import me.shenfeng.http.codec.HttpResponseStatus;
import me.shenfeng.http.codec.HttpVersion;
import me.shenfeng.http.codec.IHttpRequest;
import me.shenfeng.http.codec.IHttpResponse;
import me.shenfeng.http.server.HttpServer;

class SingleThreadHandler implements IHandler {
    public static IHttpResponse resp(IHttpRequest req) {
        IHttpResponse resp = new DefaultHttpResponse(HttpResponseStatus.OK,
                HttpVersion.HTTP_1_1);
        byte[] body = "hello word".getBytes();
        resp.setContent(body);
        resp.setHeader("Content-Length", body.length + "");
        return resp;
    }

    public void handle(IHttpRequest request, IParamedRunnable cb) {
        cb.run(resp(request));
    }
}

public class SingleThreadHttpServerTest {
    public static void main(String[] args) throws IOException {
        // concurrency 1024, 2000000 request, time: 16545ms; 120882.44 req/s;
        // receive: 93M data; 5.62 M/s
        HttpServer server = new HttpServer("0.0.0.0", 9091, new SingleThreadHandler());
        server.start();
    }
}
