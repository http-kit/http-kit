package org.httpkit;

import org.httpkit.server.WSDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class SpecialHttpClient {

    public static void main(String[] args) throws Exception {
//        System.out.println(http10("http://127.0.0.1:9090/"));

        slowWebSocketClient("ws://localhost:9090/ws2/echo");
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

    public static byte[] encodeWsRequest(String mesg) {
        byte[] payload = mesg.getBytes();

        ByteBuffer buffer = ByteBuffer.allocate(payload.length + 1024);

        byte b0 = 0;
        b0 |= 1 << 7;  // is final
        b0 |= WSDecoder.OPCODE_TEXT;
        buffer.put(b0);

        byte b1 = 0;
        b1 |= 1 << 7; // masked
        if (payload.length <= 125) {
            b1 |= payload.length;
            buffer.put(b1);
        } else if (payload.length <= 0xffff) {
            b1 |= 126;
            buffer.put(b1);
            buffer.putShort((short) payload.length);
        } else {
            b1 |= 127;
            buffer.put(b1);
            buffer.putLong(payload.length);
        }

        // masking key
        int random = (int) (Math.random() * Integer.MAX_VALUE);
        buffer.putInt(random);

        byte[] mask = ByteBuffer.allocate(4).putInt(random).array();
        int counter = 0;
        for (int i = 0; i < payload.length; i++) {
            // mast the data
            buffer.put((byte) (payload[i] ^ mask[counter++ % 4]));
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    // a special websocket client to test the robustness of the Decoder
    // sent a byte at a time
    public static boolean slowWebSocketClient(String url) {
        try {
            URI uri = new URI(url);
            InetSocketAddress addr = HttpUtils.getServerAddr(uri);

            Socket s = new Socket();
            s.connect(addr);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            byte[] buffer = new byte[8096];
            String request = "GET " + HttpUtils.getPath(uri)
                    + " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nSec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n";
            os.write(request.getBytes());
            int read = is.read(buffer);
            if (!new String(buffer, 0, read).contains("websocket")) {
                return false;
            }

            String msg = "this is a test; this is a test; this is a test";
            for (int i = 0; i < 2; i++) {
                wsRequest(os, is, msg);
                msg += (msg + msg + msg + msg);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }

    private static void wsRequest(OutputStream os, InputStream is, String msg) throws Exception {
        byte[] request = encodeWsRequest(msg);

        // sent one byte at a time
        for (byte b : request) {
            os.write(b);
            if (Math.random() > 0.6) {
                Thread.sleep(1);
            }
            os.flush();
        }
//        os.write(request);

        byte[] buffer = new byte[16 * 1024];
        int read;
        read = is.read(buffer);
        ByteBuffer wrap = ByteBuffer.wrap(buffer, 0, read);
        wrap.get(); // opcode is ignored
        byte b2 = wrap.get();
        int idx = 2;
        int resultLength = b2 & 0x7f;
        if (resultLength == 126) {
            idx += 2;
            resultLength = wrap.getShort();
        } else if (resultLength == 127) {
            idx += 8;
            resultLength = (int) wrap.getLong();
        }
        String response = new String(buffer, idx, resultLength);
        if (!response.equals(msg)) {
            throw new Exception("should equal");
        }
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
