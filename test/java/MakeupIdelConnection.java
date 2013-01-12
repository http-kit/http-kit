
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

// for i in `seq 21 37`; do sudo ifconfig eth0:$i 192.168.1.$i up ; done

// /etc/security/limits.conf
// * - nofile 999999

// sudo sysctl -w net.ipv4.ip_local_port_range="1025 65535"

// cat /proc/net/sockstat

public class MakeupIdelConnection {

    final static int STEPS = 10;
    final static int connectionPerIP = 5000;

    public static void main(String[] args) throws IOException {

        final Selector selector = Selector.open();

        InetSocketAddress locals[] = { new InetSocketAddress("127.0.0.1", 4348),
        // new InetSocketAddress("192.168.1.114", 9090),
        // new InetSocketAddress("192.168.1.21", 9090),
        // new InetSocketAddress("192.168.1.22", 9090),
        // new InetSocketAddress("192.168.1.23", 9090),
        // new InetSocketAddress("192.168.1.24", 9090),
        // new InetSocketAddress("192.168.1.25", 9090),
        // new InetSocketAddress("192.168.1.26", 9090),
        // new InetSocketAddress("192.168.1.27", 9090),
        // new InetSocketAddress("192.168.1.28", 9090),
        // new InetSocketAddress("192.168.1.29", 9090),
        // new InetSocketAddress("192.168.1.30", 9090),
        // new InetSocketAddress("192.168.1.31", 9090),
        // new InetSocketAddress("192.168.1.32", 9090),
        // new InetSocketAddress("192.168.1.33", 9090),
        // new InetSocketAddress("192.168.1.34", 9090),
        // new InetSocketAddress("192.168.1.35", 9090),
        // new InetSocketAddress("192.168.1.36", 9090),
        // new InetSocketAddress("192.168.1.37", 9090),

        };

        // InetSocketAddress remote = new InetSocketAddress("192.168.1.114",
        // 9090);

        long start = System.currentTimeMillis();
        int connected = 0;
        int currentConnectionPerIP = 0;
        while (true) {
            if (System.currentTimeMillis() - start > 1000 * 60 * 10) {
                break;
            }

            for (int i = 0; i < connectionPerIP / STEPS
                    && currentConnectionPerIP < connectionPerIP; ++i, ++currentConnectionPerIP) {
                for (InetSocketAddress addr : locals) {
                    SocketChannel ch = SocketChannel.open();
                    ch.configureBlocking(false);

                    Socket s = ch.socket();
                    s.setReuseAddress(true);
                    // s.bind(addr);

                    ch.register(selector, SelectionKey.OP_CONNECT);
                    ch.connect(addr);
                }
            }

            int select = selector.select(1000 * 10); // 10s
            if (select > 0) {
                System.out.println("select return: " + select
                        + " events ; current connection per ip: " + currentConnectionPerIP);
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> it = selectedKeys.iterator();

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isConnectable()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        if (ch.finishConnect()) {
                            ++connected;
                            if (connected % (connectionPerIP * locals.length / 10) == 0) {
                                System.out.println("connected: " + connected);
                            }
                            key.interestOps(SelectionKey.OP_READ);
                        }
                    }
                }
                selectedKeys.clear();
            }
        }
    }
}
