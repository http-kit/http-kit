# Http kit

A fast, asynchronous HTTP server and HTTP client in pure java
nio, Only depend on JDK.

I write it for the http server and http client of
[Rssminer](http://rssminer.net)


# Design goal

* Clean compact code
* Asynchronous. Used as a library
* Simple, two interface: response = handle(request)
* Fast, I like fast. The server should handle 200k+ concurrent connection, and
  50k+ req/s if response with `hello world`.
* Memory efficient
* Handle timeout correctly
* Support Socks and HTTP proxy

# Usage

## HTTP server

### simple interface
```java

public interface IHandler {
    void handle(IHttpRequest request, IParamedRunnable callback);
}

public interface IParamedRunnable {
    public void run(IHttpResponse resp);
}

```
### provide a Ihandler, start the server

```java
class SingleThreadHandler implements IHandler {
    public static IHttpResponse resp(IHttpRequest req) {
        IHttpResponse resp = new DefaultHttpResponse(HttpResponseStatus.OK,
                HttpVersion.HTTP_1_1);
        byte[] body = "hello word".getBytes();
        resp.setContent(body);
        resp.setHeader("Content-Length", body.length + "");
        return resp;
    }
    public void handle(IHttpRequest request, IParamedRunnable callback) {
        callback.run(resp(request));
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
```

```java
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
```
