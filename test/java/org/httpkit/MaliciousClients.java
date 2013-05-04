package org.httpkit;

import static org.httpkit.HttpUtils.SP;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

interface ReqGenerators {
    public byte[] generate(HttpMethod method, String url);
}

class RandomGenerator implements ReqGenerators {
    private static final Random r = new Random();

    public byte[] generate(HttpMethod method, String url) {
        byte[] bytes = new byte[r.nextInt(16 * 1024) + 1024];
        r.nextBytes(bytes);
        return bytes;
    }
}

class GoodGenerator implements ReqGenerators {
    private static final Random r = new Random();

    public byte[] generate(HttpMethod method, String url) {
        HeaderMap headers = new HeaderMap();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Charset", "UTF-8,*;q=0.5");
        headers.put(
                "Cookie",
                "BAIDUID=30CC53A6109D411534188A2FA0AF9DB5:FG=1; BDREFER=%7Burl%3A%22http%3A//internet.baidu.com/%22%2Cword%3A%22%22%7D; BDSVRTM=16; H_PS_PSSID=1423_2133_1944_2202_1788_2249_2288");

        int length = 0;
        if (method == HttpMethod.POST) {
            length = r.nextInt(4096) + 1024;
            headers.put("Content-Length", Integer.toString(length));
        }

        DynamicBytes bytes = new DynamicBytes(196);
        bytes.append(method.toString()).append(SP).append(url);
        bytes.append(" HTTP/1.1\r\n");
        headers.encodeHeaders(bytes);
        if (length > 0) {
            byte[] body = new byte[length];
            r.nextBytes(body);
            bytes.append(body, body.length);
        }
        return Arrays.copyOf(bytes.get(), bytes.length());
    }

}

class MonkeyGenerator implements ReqGenerators {
    private static final Random r = new Random();

    public byte[] generate(HttpMethod method, String url) {
        byte[] buffer = new GoodGenerator().generate(method, url);
        int b = r.nextInt(8) + 2;
        int start = r.nextInt(buffer.length - b - 1);
        for (int i = 0; i < b; i++) {
            buffer[start + 1] = (byte) r.nextInt();
        }

        return buffer;
    }
}

class Monkey2Generator implements ReqGenerators {
    private static final Random r = new Random();

    public byte[] generate(HttpMethod method, String url) {
        byte[] buffer = new GoodGenerator().generate(method, url);
        int b = r.nextInt(8) + 2;
        for (int i = 0; i < b; i++) {
            buffer[r.nextInt(32)] = (byte) r.nextInt();
        }

        return buffer;
    }
}

class Monkey3Generator implements ReqGenerators {
    private static final Random r = new Random();

    public byte[] generate(HttpMethod method, String url) {
        byte[] buffer = new GoodGenerator().generate(method, url);
        return Arrays.copyOf(buffer, r.nextInt(buffer.length));
    }
}

class Runner implements Runnable {
    private final Random r = new Random();
    private int total;
    private int remaining;
    private int success = 0;
    private final InetSocketAddress addr;

    private static final ReqGenerators[] generators = new ReqGenerators[] {
            new RandomGenerator(), new MonkeyGenerator(), new Monkey2Generator(),
            new GoodGenerator(), new Monkey3Generator() };

    private static final HttpMethod[] methods = new HttpMethod[] { HttpMethod.GET,
            HttpMethod.DELETE, HttpMethod.POST, HttpMethod.HEAD, HttpMethod.OPTIONS,
            HttpMethod.PATCH, HttpMethod.PUT, HttpMethod.TRACE };

    private static final String[] urls = new String[] { "/", "//////////////////",
            "/-------------------------", "/ssss/sdfsdf" };

    public Runner(int port, int count) {
        this.total = count;
        this.remaining = total;
        this.addr = new InetSocketAddress("192.168.1.101", port);
    }

    private static Logger logger = LoggerFactory.getLogger(MaliciousClients.class);

    public void run() {
        while (remaining-- > 0) {
            Socket s = new Socket();
            try {
                s.setReuseAddress(true);
                s.setSoTimeout(100);
                s.connect(addr);
                ReqGenerators gen = generators[r.nextInt(generators.length)];
                OutputStream os = s.getOutputStream();
                os.write(gen.generate(methods[r.nextInt(methods.length)],
                        urls[r.nextInt(urls.length)]));
                InputStream is = s.getInputStream();
                byte[] buffer = new byte[8096];

                int read = is.read(buffer);
                // System.out.println(new String(buffer, 0, read));
                is.close();
                os.close();
                if (read > 0)
                    success += 1;
            } catch (NoRouteToHostException e) {
                // Cannot assign requested address
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e1) {
                }
                // e.printStackTrace();
            } catch (Exception e) {
            } finally {
                try {
                    s.close();
                } catch (IOException e) {
                }
            }
            if (remaining % (total / 10) == 0) {
                logger.info("total: {}, remaining: {} success: {}", total, remaining, success);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }
        logger.info("finished. total: {}, success: {}", total, success);

    }
}

public class MaliciousClients {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaliciousClients.class);

    public static void main(String[] args) throws InterruptedException {
        int port = 9090;
        int count = 100000;
        int THREADS = 32;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        if (args.length > 1) {
            count = Integer.parseInt(args[1]);
        }

        if (args.length > 2) {
            THREADS = Integer.parseInt(args[2]);
        }

        LOGGER.info("port: {}, per thread count: {}, threads: {}", new Object[] { port, count,
                THREADS });

        ExecutorService executors = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            executors.submit(new Runner(port, count));
        }
        executors.shutdown();
        executors.awaitTermination(120, TimeUnit.MINUTES);
    }
}
