

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

class Connnection implements Comparable<Connnection> {

    public Connnection(SelectionKey key, long idleTs) {
        this.key = key;
        this.idelUtilTs = idleTs;
    }

    public boolean hasIdelEnoughTime(long now) {
        return now > idelUtilTs;
    }

    public final SelectionKey key;
    public final long idelUtilTs;

    public int compareTo(Connnection o) {
        return (int) (idelUtilTs - o.idelUtilTs);
    }
}

class Attachment {

    int responseLength = -1;
    int bytesNeedRead = -1;

}

public class ConcurrencyBench {

    final static int PER_IP = 20000;
    final static InetSocketAddress ADDRS[] = new InetSocketAddress[20];
    final static int CONCURENCY = PER_IP * ADDRS.length;

    static {
        final int PORT = 8000;
        final int IP_START = 200;
        for (int i = 0; i < ADDRS.length; i++) {
            ADDRS[i] = new InetSocketAddress("192.168.1." + (i + IP_START), PORT);
        }
    }

    final static Random r = new Random();

    public static ByteBuffer randRequest() {
        int length = r.nextInt(10240); // 1 ~~ 10k
        String uri = "/?length=" + length;
        return ByteBuffer.wrap(("GET " + uri + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes());
    }

    public static int randidelTime() {
        int seconds = 10 + r.nextInt(90);
        return seconds * 1000;
    }

    final static PriorityQueue<Connnection> connections = new PriorityQueue<Connnection>(
            CONCURENCY);

    // status
    static int opened = 0;
    static int connected = 0;
    static int requestsSent = 0;
    static long bytesReceived = 0;
    static long startTime = System.currentTimeMillis();

    // helper
    final static ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);
    static Selector selector;

    public static void activeIdelConnection(long now) {
        Connnection c;
        while ((c = connections.peek()) != null) {
            if (c.hasIdelEnoughTime(now)) {
                c.key.attach(new Attachment());
                c.key.interestOps(SelectionKey.OP_WRITE);
                connections.poll();
            } else {
                break;
            }
        }
    }

    static long lastReportTime = 0;

    static void reportPerSeconds(long now) {
        if (now - lastReportTime > 1000) {

            long time = now - startTime;
            double thoughput = ((double) bytesReceived / time) * 1000 / 1024 / 1024;
            double rps = ((double) requestsSent / time) * 1000;

            System.out
                    .printf("time %ds, concurrency: %d, total requests: %d, thoughput: %.2fM/s, %.2f requests/seconds\n",
                            time / 1000, connected, requestsSent, thoughput, rps);

            lastReportTime = now;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        selector = Selector.open();

        while (true) {
            long now = System.currentTimeMillis();

            // connect to server, 100 at a time
            for (int i = 0; i < 100 && opened < CONCURENCY; i++) {
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                ch.socket().setReuseAddress(true);
                ch.register(selector, SelectionKey.OP_CONNECT, new Attachment());
                ch.connect(ADDRS[opened % ADDRS.length]);
                opened++;
            }

            int select = selector.select(2000); // 2s

            if (select > 0) {
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> it = selectedKeys.iterator();

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isConnectable()) {
                        finishConnect(key);
                    } else if (key.isWritable()) {
                        writeRequest(key);
                    } else if (key.isReadable()) {
                        readResponse(key, now);
                    }
                }
                selectedKeys.clear();
            }

            activeIdelConnection(now);
            reportPerSeconds(now);

            if (opened < CONCURENCY) {
                Thread.sleep(20); // open 5000 per seconds most
            }
        }
    }

    private static void readResponse(SelectionKey key, long now) {
        SocketChannel ch = (SocketChannel) key.channel();
        buffer.clear();
        try {
            int read = ch.read(buffer);
            if (read == -1) {
                System.out.println("remote closed cleanly");
                close(ch); // remote closed cleanly
            } else if (read > 0) {
                bytesReceived += read;
                buffer.flip();

                Attachment att = (Attachment) key.attachment();
                if (att.responseLength == -1) {
                    String line = readLine(buffer);
                    while (line.length() > 0) {
                        line = line.toLowerCase();
                        if (line.startsWith(CL)) {
                            String length = line.substring(CL.length());
                            att.responseLength = Integer.valueOf(length);
                            att.bytesNeedRead = att.responseLength;
                        }
                        line = readLine(buffer);
                    }
                    att.bytesNeedRead -= buffer.remaining();
                } else {
                    att.bytesNeedRead -= read;
                }

                if (att.bytesNeedRead == 0) { // all read
                    connections.add(new Connnection(key, now + randidelTime()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            close(ch);
        }
    }

    static final byte CR = 13;
    static final byte LF = 10;
    static final String CL = "content-length: ";

    // need to be more robust, but works fine on Linux
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

    private static void writeRequest(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ch.write(randRequest());
        requestsSent += 1;
        key.interestOps(SelectionKey.OP_READ);
    }

    private static void finishConnect(SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            if (ch.finishConnect()) {
                ++connected;
                key.interestOps(SelectionKey.OP_WRITE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void close(SelectableChannel ch) {
        connected--;
        try {
            ch.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
