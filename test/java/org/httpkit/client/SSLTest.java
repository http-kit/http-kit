package org.httpkit.client;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Created with IntelliJ IDEA.
 * User: feng
 * Date: 4/11/13
 * Time: 2:09 PM
 * To change this template use File | Settings | File Templates.
 */
public class SSLTest {

    private static final SSLContext CLIENT_CONTEXT;

    static {
        SSLContext clientContext = null;
        try {
//            SSLContext.getInstance()
            clientContext = SSLContext.getDefault();
//            clientContext = SSLContext.getInstance("TLS");
//            clientContext.init(null, TrustManagerFactory.getTrustManagers(),
//                    null);

        } catch (Exception e) {
            throw new Error(
                    "Failed to initialize the client-side SSLContext", e);
        }
        CLIENT_CONTEXT = clientContext;
    }

    public static void main(String[] args) throws Exception {
        SSLEngine engine = CLIENT_CONTEXT.createSSLEngine();
        engine.setUseClientMode(true);

        // Create a nonblocking socket channel
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(true);
        socketChannel.connect(new InetSocketAddress("google.com", 443));

        // Complete connection
//        int i = 0;
        while (!socketChannel.finishConnect()) {
//            System.out.println("----------" + i++);
            Thread.sleep(50);
            // do something until connect completed
        }

        // Create byte buffers to use for holding application and encoded data
        SSLSession session = engine.getSession();
        ByteBuffer myNetData = ByteBuffer.allocate(session.getPacketBufferSize());
        ByteBuffer peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
        ByteBuffer peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());
        peerNetData.limit(0);

        ByteBuffer myAppData = ByteBuffer.wrap(("GET / HTTP/1.1\r\nHost: \r\n\r\n").getBytes());

        engine.beginHandshake();
        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED
                && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            System.out.println("hs status: " + hs);
            switch (hs) {
                case NEED_TASK:
                    Runnable runnable;
                    while ((runnable = engine.getDelegatedTask()) != null) {
                        System.out.println("get task " + runnable);
                        runnable.run();
                    }
                    break;
                case NEED_UNWRAP:
                    if (!peerNetData.hasRemaining()) {
                        peerNetData.clear();
                        int read = socketChannel.read(peerNetData);
                        System.out.println("read: " + read + "\t" + peerNetData);
                        peerNetData.flip();
                    }
                    SSLEngineResult status = engine.unwrap(peerNetData, peerAppData);
                    //  peerNetData.compact();
                    System.out.println("unwrap: " + status);
                    switch (status.getStatus()) {
                        case BUFFER_UNDERFLOW:
                            peerNetData.compact();
//                            peerNetData.flip();
                            int read = socketChannel.read(peerNetData);
                            System.out.println("flip read: " + read + "\t" +
                                    peerNetData);
                            peerNetData.flip();
                            break;
                    }

                    break;
                case NEED_WRAP:
                    myNetData.clear();
                    SSLEngineResult wrapStatus = engine.wrap(myAppData,
                            myNetData);
                    System.out.println("wrap: " + wrapStatus);
                    myNetData.flip();
                    while (myNetData.hasRemaining()) {
                        socketChannel.write(myNetData);
                    }
                    break;
            }
            hs = engine.getHandshakeStatus();
        }
// https://raw.github.com/http-kit/scale-clojure-web-app/master/results/600k/heap_usage.png
        for (int i = 0; i < 5; i++) {
            myNetData.clear();
            peerAppData.clear();
            myAppData = ByteBuffer.wrap(("GET / HTTP/1.1\r\nHost: www.google.co.jp\r\n\r\n").getBytes());

            SSLEngineResult wrapStatus = engine.wrap(myAppData,
                    myNetData);
//            System.out.println("---------wrap: " + wrapStatus);
            myNetData.flip();
            while (myNetData.hasRemaining()) {
                socketChannel.write(myNetData);
            }

            peerNetData.clear();
            int read = socketChannel.read(peerNetData);
//            System.out.println("-------read: " + read + "\t" + peerNetData);
            peerNetData.flip();
            // 	Exception in thread "main" javax.net.ssl.SSLException: bad record MAC
            SSLEngineResult status = engine.unwrap(peerNetData, peerAppData);
            while (status.getStatus() != SSLEngineResult.Status.OK) {
//                System.out.println("-------unwrap: " + status);
                peerNetData.compact();
                read = socketChannel.read(peerNetData);
                System.out.println("-------read: " + read + "\t" + peerNetData);
                peerNetData.flip();
                status = engine.unwrap(peerNetData, peerAppData);
            }
            peerAppData.flip();
            System.out.println(peerAppData);
            byte[] data = new byte[peerAppData.remaining()];
            peerAppData.get(data);
            System.out.println(new String(data));
            //  peerNetData.compact();
        }


// Do initial handshake
//        doHandleShake2(socketChannel, engine, myNetData, peerNetData);


    }
}
