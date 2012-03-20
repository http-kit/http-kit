package me.shenfeng.http.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.codec.DefaultHttpResponse;
import me.shenfeng.http.codec.HttpMessageDecoder.State;
import me.shenfeng.http.codec.HttpReqeustDecoder;
import me.shenfeng.http.codec.HttpUtils;
import me.shenfeng.http.codec.IHttpResponse;
import me.shenfeng.http.codec.LineTooLargeException;
import me.shenfeng.http.codec.ProtocolException;

public class HttpServer {

    static void print(String str) {
        System.out.println(System.currentTimeMillis() / 100 + " " + str);
    }

    private static void write(SelectionKey key, SelectAtta atta) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer header = atta.respHeader;
        ByteBuffer body = atta.respBody;

        if (header.hasRemaining()) {
            ch.write(new ByteBuffer[] { header, body });
        } else {
            ch.write(body);
        }
        // This is much slower than abover: 11w req/s VS 2.5w req/s
        // ch.write(header);
        // ch.write(body);
        if (!body.hasRemaining()) {
            if (atta.resp.isKeepAlive()) {
                atta.decoder.reset();
                key.interestOps(SelectionKey.OP_READ);
            } else {
                ch.close();
            }
        }
    }

    private IHandler handler;
    private int port;
    private String ip;
    private Selector selector;

    ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();

    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    public HttpServer(String ip, int port, IHandler handler) {
        this.handler = handler;
        this.ip = ip;
        this.port = port;
    }

    void accept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s = ch.accept();
        s.configureBlocking(false);
        s.register(selector, SelectionKey.OP_READ, new SelectAtta());
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

    private void read(final SelectionKey key, final SelectAtta atta) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear(); // clear for read
            int read = ch.read(buffer);
            if (read == -1) {
                // System.out.println("close " + ch);
                ch.close(); // remote entity shut the socket down cleanly.
            } else if (read > 0) {
                buffer.flip(); // flip for read
                HttpReqeustDecoder decoder = atta.decoder;
                State s = decoder.decode(buffer);
                if (s == State.ALL_READ) {
                    handler.handle(decoder.getMessage(), new IParamedRunnable() {
                        public void run(IHttpResponse resp) {
                            atta.resp = resp;
                            // in worker thread
                            atta.respHeader = HttpUtils.encodeResponseHeader(resp);
                            atta.respBody = ByteBuffer.wrap(resp.getContent());
                            pendings.offer(key);
                            selector.wakeup();
                        }
                    });
                }
            }
        } catch (IOException e) {
            ch.close(); // the remote forcibly closed the connection
        } catch (ProtocolException e) {
            ch.close();
        } catch (LineTooLargeException e) {
            DefaultHttpResponse resp = DefaultHttpResponse.BAD_REQUEST;
            atta.resp = resp;
            atta.respHeader = HttpUtils.encodeResponseHeader(resp);
            atta.respBody = ByteBuffer.wrap(resp.getContent());
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    public void start() throws IOException {
        bind(ip, port);
        SelectionKey key;
        while (true) {
            while ((key = pendings.poll()) != null) {
                key.interestOps(SelectionKey.OP_WRITE);
            }
            int select = selector.select(4000);
            if (select <= 0) {
                continue;
            }
            int time = (int) System.currentTimeMillis();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> ite = selectedKeys.iterator();
            while (ite.hasNext()) {
                key = ite.next();
                if (key.isValid()) {
                    SelectAtta atta = (SelectAtta) key.attachment();
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
