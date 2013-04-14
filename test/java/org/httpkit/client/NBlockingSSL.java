package org.httpkit.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Set;

public class NBlockingSSL {

    private static Logger logger = LoggerFactory.getLogger(NBlockingSSL.class);

    private static final SSLContext CLIENT_CONTEXT;

    static {
        try {
            CLIENT_CONTEXT = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new Error("Failed to initialize the client-side SSLContext", e);
        }
    }

    private static Selector selector;
    public static SSLEngine engine;
    private static boolean isHandshakeDone = false;
    private static SelectionKey key;
    private static ByteBuffer myNetData = ByteBuffer.allocate(32 * 1024);
    private static ByteBuffer peerNetData = ByteBuffer.allocate(24 * 1024);
    private static ByteBuffer peerAppData = ByteBuffer.allocate(24 * 1024);
    private static SocketChannel socketChannel;

    // private final static String HOST = "d.web2.qq.com";
    private final static String HOST = "github.com";

    public static void main(String[] args) throws IOException {
        engine = CLIENT_CONTEXT.createSSLEngine();
        engine.setUseClientMode(true);

        selector = Selector.open();
        socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        key = socketChannel.register(selector, SelectionKey.OP_CONNECT);
        socketChannel.connect(new InetSocketAddress(HOST, 443));

        int i = 0;
        // myNetData.clear();
        // peerAppData.clear();
        // peerNetData.clear();
        while (true) {
            int select = selector.select(1000);
            if (select > 0) {
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                final Iterator<SelectionKey> ite = selectionKeys.iterator();
                while (ite.hasNext()) {
                    final SelectionKey key = ite.next();
                    if (key.isConnectable()) {
                        if (socketChannel.finishConnect()) {
                            key.interestOps(SelectionKey.OP_WRITE);
                            engine.beginHandshake();
                        }
                    } else if (key.isReadable()) {
                        if (!isHandshakeDone) {
                            doHandshake();
                        } else {
                            int read = socketChannel.read(peerNetData);
                            if (read > 0) {
                                peerNetData.flip();
                                SSLEngineResult res = engine.unwrap(peerNetData, peerAppData);
                                if (res.getStatus() == SSLEngineResult.Status.OK) {
                                    peerAppData.flip();
                                    byte[] data = new byte[peerAppData.remaining()];
                                    peerAppData.get(data);
                                    i++;
                                    logger.info("get data length: " + new String(data).length());
                                    key.interestOps(SelectionKey.OP_WRITE);
                                    peerAppData.clear();

                                    if (i > 5) {
                                        return;
                                    }
                                    // peerNetData.clear();
                                }
                                logger.info("read unwrap, " + res);
                                peerNetData.compact();
                            }
                        }
                    } else if (key.isWritable()) {
                        if (!isHandshakeDone) {
                            doHandshake();
                        } else {
                            myNetData.clear();
                            ByteBuffer buffer = ByteBuffer.wrap(("GET / HTTP/1.1\r\nHost: "
                                    + HOST + "\r\n\r\n").getBytes());
                            SSLEngineResult res = engine.wrap(buffer, myNetData);
                            if (res.getStatus() == Status.OK) {
                                myNetData.flip();
                                socketChannel.write(myNetData);
                                if (!myNetData.hasRemaining()) {
                                    key.interestOps(SelectionKey.OP_READ);
                                }
                            }
                        }
                    }
                    ite.remove();
                }
            } else {
                logger.info("waiting");
            }
        }
    }

    private static void doHandshake() throws IOException {
        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        isHandshakeDone = hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                || hs == SSLEngineResult.HandshakeStatus.FINISHED;
        loop: while (!isHandshakeDone) {
            switch (hs) {
            case NEED_TASK:
                Runnable runnable;
                while ((runnable = engine.getDelegatedTask()) != null) {
                    logger.info("get task " + runnable);
                    runnable.run();
                }
                break;
            case NEED_UNWRAP:
                int read = socketChannel.read(peerNetData);
                logger.info("read {} bytes", read);
                if (read < 0) {
                    logger.info("closed");
                    // TODO closed
                } else {
                    peerNetData.flip();
                    SSLEngineResult res = engine.unwrap(peerNetData, peerAppData);
                    logger.info("hs unwrap, " + res);
                    if(res.getStatus() != Status.OK) {
                        System.out.println("--------------------------");
                    }
                    peerNetData.compact();
                    switch (res.getStatus()) {
                    case OK:
                        break;
                    case BUFFER_UNDERFLOW:
                        // need more data from peer
                        logger.info("waiting for more info");
                        break loop;
                    case BUFFER_OVERFLOW:
                        // need larger peerAppData buffer
                        break;
                    case CLOSED:
                        break;
                    }
                    if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                        key.interestOps(SelectionKey.OP_WRITE);
                        logger.info("for write");
                        break loop;
                    }
                }
                break;
            case NEED_WRAP:
                // myNetData.compact();
                SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), myNetData);
                logger.info("wrap: " + result);
                myNetData.flip();
                socketChannel.write(myNetData);

                if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                }

                if (!myNetData.hasRemaining()) {
                    // write done, so just for read
                    if ((key.interestOps() & SelectionKey.OP_READ) == 0) {
                        key.interestOps(SelectionKey.OP_READ);
                        logger.info("for read");
                    }
                    myNetData.clear();
                    // break loop;

                } else {
                    myNetData.compact();
                }
                break;
            }
            hs = engine.getHandshakeStatus();
            isHandshakeDone = hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    || hs == SSLEngineResult.HandshakeStatus.FINISHED;
            if (isHandshakeDone) {
                logger.info("handshake done");
                peerNetData.clear();
                ByteBuffer buffer = ByteBuffer
                        .wrap(("GET / HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n").getBytes());
                SSLEngineResult res = engine.wrap(buffer, myNetData);

                RandomAccessFile r = new RandomAccessFile(
                        "/home/feng/workspace/http-kit/blog.access.log", "r");
                MappedByteBuffer b = r.getChannel().map(MapMode.READ_ONLY, 0,
                        r.getChannel().size());
                ByteBuffer bf = ByteBuffer.allocate(256 * 1024);
                // even though b is big, bf is small, the two buffer just move
                // forward
                SSLEngineResult t = engine.wrap(b, bf);
                System.out.println(t);

                if (res.getStatus() == SSLEngineResult.Status.OK) {
                    myNetData.flip();
                    socketChannel.write(myNetData);
                    if (myNetData.hasRemaining()) {
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                }
            }
        }
    }
}
