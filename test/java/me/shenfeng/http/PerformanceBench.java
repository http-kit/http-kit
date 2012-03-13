package me.shenfeng.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

class SelectAttachment {
    private static final Random r = new Random();
    private static final String[] urls = { "/d/aarp", "/d/about", "/d/zoo", "/d/throw",
            "/d/new", "/tmpls.js", "/mustache.js" };
    String uri;
    ByteBuffer request;
    int response_length = -1;
    int response_cnt = -1;

    public SelectAttachment(String uri) {
        this.uri = uri;
        request = ByteBuffer.wrap(("GET " + uri + " HTTP/1.1\r\n\r\n").getBytes());
    }

    public static SelectAttachment next() {
        return new SelectAttachment(urls[r.nextInt(urls.length)]);
    }
}

public class PerformanceBench {

    static final boolean DEBUG = false;

    static final byte CR = 13;
    static final byte LF = 10;
    static final String CL = "content-length: ";

    public static String readLine(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder(64);
        char b;
        loop: for (;;) {
            b = (char) buffer.get();
            switch (b) {
            case CR:
                if (buffer.get() == LF)
                    break loop;
                break;
            case LF:
                break loop;
            }
            sb.append(b);
        }
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {

        final int concurrency = 1024 * 1;
        long totalByteReceive = 0;
        final int total = 2000000;
        int remaining = total;

        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 9091);

        ByteBuffer readBuffer = ByteBuffer.allocateDirect(1024 * 64);
        Selector selector = Selector.open();
        SelectAttachment att;
        SocketChannel ch;

        long start = System.currentTimeMillis();

        for (int i = 0; i < concurrency; ++i) {
            ch = SocketChannel.open();
            ch.socket().setReuseAddress(true);
            ch.configureBlocking(false);
            ch.register(selector, SelectionKey.OP_CONNECT, SelectAttachment.next());
            ch.connect(addr);
        }

        loop: while (true) {
            int select = selector.select();
            if (DEBUG)
                System.out.println("selec return " + select);
            if (select > 0) {
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> it = selectedKeys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isConnectable()) {
                        ch = (SocketChannel) key.channel();
                        if (ch.finishConnect()) {
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } else if (key.isWritable()) {
                        ch = (SocketChannel) key.channel();
                        att = (SelectAttachment) key.attachment();
                        ByteBuffer buffer = att.request;
                        ch.write(buffer);
                        if (!buffer.hasRemaining()) {
                            key.interestOps(SelectionKey.OP_READ);
                        }
                    } else if (key.isReadable()) {
                        ch = (SocketChannel) key.channel();
                        att = (SelectAttachment) key.attachment();
                        readBuffer.clear();
                        int read = ch.read(readBuffer);
                        if (read == -1) {
                            ch.close();
                        } else {
                            totalByteReceive += read;
                            if (att.response_length == -1) {
                                readBuffer.flip();
                                String line = readLine(readBuffer);
                                while (line.length() > 0) {
                                    line = line.toLowerCase();
                                    if (line.startsWith(CL)) {
                                        String length = line.substring(CL.length());
                                        att.response_length = Integer.valueOf(length);
                                        att.response_cnt = att.response_length;
                                    }
                                    line = readLine(readBuffer);
                                }
                                att.response_cnt -= readBuffer.remaining();
                            } else {
                                att.response_cnt -= read;
                            }
                            if (att.response_cnt == 0) {
                                if (DEBUG)
                                    System.out.println("read all");
                                remaining--;
                                if (remaining > 0) {
                                    if (remaining % (total / 10) == 0) {
                                        System.out.println("remaining\t" + remaining);
                                    }
                                    key.attach(SelectAttachment.next());
                                    key.interestOps(SelectionKey.OP_WRITE);
                                } else {
                                    break loop;
                                }
                            }
                        }
                    }
                }
                selectedKeys.clear();
            }
        }

        long time = (System.currentTimeMillis() - start);
        long receiveM = totalByteReceive / 1024 / 1024;
        double reqs = (double) total / time * 1000;
        double ms = (double) receiveM / time * 1000;

        System.out
                .printf("concurrency %d, %d request, time: %dms; %.2f req/s; receive: %dM data; %.2f M/s\n",
                        concurrency, total, time, reqs, receiveM, ms);

    }
}
