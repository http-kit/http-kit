package org.httpkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;

public class SpecialHttpClient {

    public static void main(String[] args) throws Exception {
        System.out.println(http10("http://127.0.0.1:9090/"));
    }

    // request + request sent to server, wait for 2 server responses
    public static String get2(String url) throws URISyntaxException, IOException {
        URI uri = new URI(url);
        InetSocketAddress addr = HttpUtils.getServerAddr(uri);

        Socket s = new Socket();
        s.connect(addr);
        OutputStream os = s.getOutputStream();

        String request = "GET " + HttpUtils.getPath(uri)
                + " HTTP/1.1\r\nHost: localhost\r\n\r\n";

        os.write((request + request).getBytes());
        os.flush();

        InputStream is = s.getInputStream();

        byte[] buffer = new byte[8096];
        int read = is.read(buffer);
        s.close();
        return new String(buffer, 0, read);
    }

    public static String http10(String url) throws Exception {
        URI uri = new URI(url);
        InetSocketAddress addr = HttpUtils.getServerAddr(uri);

        Socket s = new Socket();
        s.connect(addr);
        OutputStream os = s.getOutputStream();

        String request = "GET " + HttpUtils.getPath(uri)
                + " HTTP/1.0\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";
        os.write((request + request).getBytes());
        os.flush();

        InputStream is = s.getInputStream();

        byte[] buffer = new byte[8096];
        int read = is.read(buffer);
        s.close();
        return new String(buffer, 0, read);
    }

    // sent request one byte at a time
    public static String slowGet(String url) throws URISyntaxException,
            IOException, InterruptedException {

        URI uri = new URI(url);
        InetSocketAddress addr = HttpUtils.getServerAddr(uri);
        Socket s = new Socket();
        s.setTcpNoDelay(false);
        s.connect(addr);

        String request = "GET " + HttpUtils.getPath(uri) + " HTTP/1.1\r\n";
        request += "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n";
        request += "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_0) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1312.52 Safari/537.17\r\n";
        request += "Connection: close\n";
        request += "\r\n";

        byte[] bytes = request.getBytes();
        InputStream is = s.getInputStream();
        OutputStream os = s.getOutputStream();

        for (byte b : bytes) {
            os.write(b);
            if (Math.random() > 0.6) {
                Thread.sleep(1);
            }
            os.flush();
        }

        byte[] buffer = new byte[8096];
        int read = is.read(buffer);
        s.close();
        return new String(buffer, 0, read);
    }

    public static String getPartial(String url) {
        try {
            URI uri = new URI(url);
            InetSocketAddress addr = HttpUtils.getServerAddr(uri);

            Socket s = new Socket();
            s.connect(addr);
            s.setSoTimeout(100);
            OutputStream os = s.getOutputStream();

            String request = "GET " + HttpUtils.getPath(uri)
                    + " HTTP/1.1\r\nHost: localhost\r\n\r\n";

            os.write(request.getBytes());
            os.flush();

            InputStream is = s.getInputStream();

            byte[] buffer = new byte[8096];
            int read = is.read(buffer);
            s.close();
            return new String(buffer, 0, read);
        } catch (Exception e) {
            return null;
        }
    }
}
