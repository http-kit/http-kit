package me.shenfeng.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import me.shenfeng.http.codec.State;

public class Adapter {

    static Selector bind(String ip, int port) throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        return selector;
    }

    public static void accept(SelectionKey key, Selector selector)
            throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s = ch.accept();
        // System.out.println(s);
        s.configureBlocking(false);
        s.register(selector, SelectionKey.OP_READ, new EpollAttachmement());
    }

    public static void start() throws IOException {
        Selector selector = bind("0.0.0.0", 9091);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(1024 * 64);
        while (true) {
            int select = selector.select(1000 * 4);
            // System.out.println("select " + select);
            if (select > 0) {
                int time = (int) System.currentTimeMillis();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> ite = selectedKeys.iterator();
                while (ite.hasNext()) {
                    SelectionKey key = ite.next();
                    if (key.isValid()) {
                        EpollAttachmement atta = (EpollAttachmement) key
                                .attachment();
                        if (key.isAcceptable()) {
                            accept(key, selector);
                        } else if (key.isReadable()) {
                            atta.touch(time);
                            read(key, readBuffer, atta);
                        } else if (key.isWritable()) {
                            write(key, atta);
                        }
                    }
                }
                selectedKeys.clear();
            }
        }
    }

    private static void write(SelectionKey key, EpollAttachmement atta)
            throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer resp = atta.response;
        ch.write(resp);
        if (resp.remaining() == 0) {
            // atta.request.resetState();
            ch.close();
            // key.interestOps(SelectionKey.OP_READ);
        }

    }

    private static void read(SelectionKey key, ByteBuffer buffer,
            EpollAttachmement atta) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        buffer.clear();
        int read = ch.read(buffer);
        if (read > 0) {
            buffer.flip();
            State s = atta.request.decode(buffer);
            // System.out.println(ch + ", " + s);
            if (s == State.PROTOCOL_ERROR) {

            } else if (s == State.ALL_READ) {
                atta.response = ByteBuffer
                        .wrap("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789"
                                .getBytes());

                key.interestOps(SelectionKey.OP_WRITE);
            }
        } else if (read == -1) { // remote clean down
            ch.close();
        }
    }

    public static void main(String[] args) throws IOException {
        start();
    }

}
