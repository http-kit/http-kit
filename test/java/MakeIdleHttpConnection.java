import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

// makeup idle keep-alive HTTP connection, and periodly close and re-open the connection

class PendingClose implements Comparable<PendingClose> {
    public final SelectionKey key;
    public final long closeTime;

    public PendingClose(SelectionKey key, long closeTime) {
        this.key = key;
        this.closeTime = closeTime;
    }

    public int compareTo(PendingClose o) {
        return (int) (closeTime - o.closeTime);
    }

    public String toString() {
        return "PendingClose [key=" + key + ", closeTime=" + closeTime + "]";
    }

}

public class MakeIdleHttpConnection {
    // config
    static int CONCURENCY = 4000;
    final static int PORT = 4348;
    // local test, a computer can have many ips
    final static InetSocketAddress ADDRS[] = { new InetSocketAddress("127.0.0.1", PORT), };
    final static byte[] REQUEST = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes();

    // status
    static int opened = 0;
    static int connected = 0;

    public static int randIdleTime() {
        return r.nextInt(1000 * 40) + 1000 * 8;
    }

    // helper
    final static Random r = new Random();
    final static ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);
    static Selector selector;

    // close and reopen the connection after a time
    final static PriorityQueue<PendingClose> pendingClose = new PriorityQueue<PendingClose>();

    public static void handlePendingClose() throws IOException {
        long now = System.currentTimeMillis();
        PendingClose p;
        while ((p = pendingClose.peek()) != null) {
            if (p.closeTime < now) {
                close(p.key.channel());
                pendingClose.poll();
            } else {
                break;
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 0) {
            CONCURENCY = Integer.parseInt(args[0]);
        }
        selector = Selector.open();

        long start = System.currentTimeMillis();
        long lastPrintTime = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (now - start > 1000 * 60 * 20) {
                // exits after 20 minutes
                break;
            }

            for (int i = 0; i < 20 && connected < CONCURENCY; ++i) {
                for (InetSocketAddress addr : ADDRS) {
                    connect(addr);
                }
            }
            int select = selector.select(2000); // 2s

            handlePendingClose();
            if (select <= 0) {
                continue;
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();

            while (it.hasNext()) {
                SelectionKey key = it.next();
                SocketChannel ch = (SocketChannel) key.channel();
                if (key.isConnectable()) {
                    try {
                        if (ch.finishConnect()) {
                            ++connected;
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } catch (Exception e) {
                        ch.close();
                    }
                } else if (key.isWritable()) {
                    // should write all, TCP buffer
                    ch.write(ByteBuffer.wrap(REQUEST));
                    key.interestOps(SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    buffer.clear();
                    try {
                        int read = ch.read(buffer);
                        if (read == -1) {
                            close(ch); // remote closed cleanly
                        } else {
                            key.cancel(); // not selectalbe
                            pendingClose.add(new PendingClose(key, now + randIdleTime()));
                        }
                    } catch (Exception e) {
                        close(ch);
                    }
                }
            }
            selectedKeys.clear();
            Thread.sleep(20);
            if (now - lastPrintTime >= 1000) {
                lastPrintTime = now;
                System.out
                        .println("connection opened: " + opened + "; connected: " + connected);
            }
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

    private static void connect(InetSocketAddress addr) throws IOException, SocketException,
            ClosedChannelException {
        opened++;
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ch.socket().setReuseAddress(true);
        ch.register(selector, SelectionKey.OP_CONNECT);
        ch.connect(addr);
    }
}
