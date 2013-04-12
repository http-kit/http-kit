import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.nio.channels.SelectionKey.OP_READ;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Set;

// for result, refer ./scripts/run_nio

public class NIOPerfTest {

    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);

    final int LNEGHT = 1500;

    private final ByteBuffer output = ByteBuffer.allocate(64 * 1024);
    String header = "HTTP/1.1 200 OK\r\nConnection: Keep-Alive\r\nDate: Fri, 12 Apr 2013 05:24:18 GMT\r\nContent-Length:"
            + LNEGHT + "\r\n\r\n";
    String body = "";

    byte[] headerBytes = header.getBytes();
    byte[] bodyBytes;

    public NIOPerfTest() throws IOException {
        this.selector = Selector.open();
        for (int i = 0; i < LNEGHT / 10; i++) {
            body += "1234567890";
        }

        bodyBytes = body.getBytes();

        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", 8000);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
    }

    public void run() throws IOException {
        while (true) {
            int select = selector.select();
            if (select > 0) {
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                for (SelectionKey key : selectionKeys) {
                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        SocketChannel s = serverChannel.accept();
                        if (s != null) {
                            s.configureBlocking(false);
                            s.register(selector, OP_READ);
                        }
                    } else if (key.isReadable()) {
                        try {
                            buffer.clear();
                            SocketChannel ch = (SocketChannel) key.channel();
                            int read = ch.read(buffer);
                            if (read < 0) {
                                key.channel().close();
                                System.out.printf("closed\n");
                            } else if (read > 0) {

                                output.clear();
                                output.put(headerBytes).put(bodyBytes);
                                output.flip();
                                ch.write(output);
                                if (output.hasRemaining()) {
                                    System.out.println("=============");
                                    key.interestOps(SelectionKey.OP_WRITE);
                                }

                            } else {
                                System.out.println(key);
                            }
                        } catch (IOException e) {
                            key.channel().close();
                            e.printStackTrace();
                        }
                    } else if (key.isWritable()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        // output.flip();

                        try {
                            output.clear();
                            output.put(headerBytes).put(bodyBytes);
                            output.flip();
                            ch.write(output);

                            // if(output.hasRemaining()) {
                            // System.out.println("has remaining");
                            // }

                            // ch.write(new ByteBuffer[] {
                            // ByteBuffer.wrap(headerBytes),
                            // ByteBuffer.wrap(bodyBytes) });
                            key.interestOps(SelectionKey.OP_READ);
                        } catch (IOException e) {
                            // output.clear();
                            // output.put("HTTP/1.1 200 OK\r\nConnection: Keep-Alive\r\nDate: Fri, 12 Apr 2013 05:24:18 GMT\r\nContent-Length:10\r\n\r\n1234567890"
                            // .getBytes());
                            key.channel().close();
                            e.printStackTrace();

                        }
                    }
                }
                selectionKeys.clear();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new NIOPerfTest().run();
    }
}
