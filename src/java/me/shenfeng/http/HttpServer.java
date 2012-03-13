package me.shenfeng.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import me.shenfeng.http.codec.State;

public class HttpServer {

    private static byte[] BAD_REQUEST = "HTTP/1.1 400 Bad Request\r\nContent-Length: 15\r\n\r\n400 bad request"
            .getBytes();

    private static void write(SelectionKey key, EpollAttachmement atta)
            throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer resp = atta.response;
        ch.write(resp);
        if (resp.remaining() == 0) {
            atta.request.resetState();
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private IHandler handler;
    private int port;
    private String ip;
    private Selector selector;
    List<SelectionKey> pendingForWrite = new ArrayList<SelectionKey>(64);
    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    public HttpServer(String ip, int port, IHandler handler) {
        this.handler = handler;
        this.ip = ip;
        this.port = port;
    }

    public void accept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s = ch.accept();
        s.configureBlocking(false);
        s.register(selector, SelectionKey.OP_READ, new EpollAttachmement());
    }

    void bind(String ip, int port) throws IOException {
        selector = Selector.open();
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("start server " + ip + "@" + port);
    }

    static void print(String str) {
        System.out.println(System.currentTimeMillis() / 100 + " " + str);
    }

    private void read(final SelectionKey key, final EpollAttachmement atta)
            throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        buffer.clear();
        int read = 0;
        try {
            read = ch.read(buffer);
        } catch (IOException e) {
            // the remote forcibly closed the connection
            ch.close();
        }
        if (read == -1) {
            // remote entity shut the socket down cleanly.
            ch.close();
        } else if (read > 0) {
            buffer.flip();
            State s = atta.request.decode(buffer);
            if (s == State.PROTOCOL_ERROR) {
                atta.response = ByteBuffer.wrap(BAD_REQUEST);
                key.interestOps(SelectionKey.OP_WRITE);
                ch.close();
            } else if (s == State.ALL_READ) {
                handler.handle(atta.request, new IParamedRunnable() {
                    public void run(ByteBuffer resp) {
                        // System.out.println("ok");
                        atta.response = resp;
                        // key.interestOps(SelectionKey.OP_WRITE);
                        synchronized (pendingForWrite) {
                            // print("for write " +
                            // key.channel());
                            pendingForWrite.add(key);
                            selector.wakeup();
                        }

                    }
                });
            }
        }
    }

    public void start() throws IOException {
        bind(ip, port);
        while (true) {
            synchronized (pendingForWrite) {
                for (SelectionKey key : pendingForWrite) {
                    key.interestOps(SelectionKey.OP_WRITE);
                }
                pendingForWrite.clear();
            }

            int select = selector.select(1000);
            // System.out.println("select " + select);
            if (select <= 0) {
                continue;
            }
            int time = (int) System.currentTimeMillis();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> ite = selectedKeys.iterator();
            while (ite.hasNext()) {
                final SelectionKey key = ite.next();
                if (key.isValid()) {
                    EpollAttachmement atta = (EpollAttachmement) key.attachment();
                    if (key.isAcceptable()) {
                        accept(key, selector);
                    } else if (key.isReadable()) {
                        atta.touch(time);
                        read(key, atta);
                    } else if (key.isWritable()) {
                        write(key, atta);
                    }
                }
            }
            selectedKeys.clear();
        }
    }

    public void stop() throws IOException {
        if (selector != null) {
            selector.close();
        }
    }
}
