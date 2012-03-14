# Http kit

A fast, asynchronous HTTP server and HTTP client in pure java
nio, Only depend on JDK.

I write it for the http server and http client of
[Rssminer](http://rssminer.net)


# Design goal

* Intended to be used as a library
* Simple, single interface: response = handle(request)
* Fast, I like fast. Should handle 200k+ concurrent connection, and
  40k+ req/s if `hello world` response.
* Memory efficient.

# Usage

## HTTP server

### simple interface
```java

interface IHandler {
    void handle(IHttpRequest request, IParamedRunnable callback);
}

interface IParamedRunnable {
    void run(ByteBuffer resp);
}

```
### provide a Ihandler, start the server

```java
// single thread
class SingleThreadHandler implements IHandler {
    public void handle(IHttpRequest request, IParamedRunnable callback) {
        ByteBuffer buffer = ByteBuffer
                .wrap("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789"
                        .getBytes());

        callback.run(buffer);
    }
}

public class HttpServerTest {
    @Test
    public void testSingleThreadServer() throws IOException {
        // concurrency 1024, 2000000 request, time: 14294ms; 139918.85 req/s;
        // receive: 93M data; 6.51 M/s
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

public class HttpServerTest {
    @Test
    public void testMultiThreadServer() throws IOException {
        // concurrency 1024, 2000000 request, time: 16609ms; 120416.64 req/s;
        // receive: 93M data; 5.60 M/s
        HttpServer server = new HttpServer("0.0.0.0", 9091, new MultiThreadHandler());
        server.start();
    }
```
