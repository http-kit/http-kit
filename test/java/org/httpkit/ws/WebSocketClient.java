package org.httpkit.ws;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.codec.http.websocketx.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.*;

public class WebSocketClient {

    private final URI uri;
    ClientBootstrap bootstrap;
    Channel ch = null;
    private BlockingQueue<WebSocketFrame> queue = new ArrayBlockingQueue<WebSocketFrame>(10);

    public static void main(String[] args) throws Exception {
        WebSocketClient client = new WebSocketClient("ws://localhost:9090/ws2/ws");
        client.sendMessage("{:length 3145728, :times 1}");
        for (int i = 0; i < 1; i++) {
            Object message = client.getMessage();
            if (message instanceof String) {
                System.out.println(((String) message).length());
            }
        }
    }

    public WebSocketClient(String url) throws Exception {
        bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(
                Executors.newFixedThreadPool(1), Executors.newFixedThreadPool(1)));
        this.uri = new URI(url);
        HashMap<String, String> customHeaders = new HashMap<String, String>();
        final WebSocketClientHandshaker handshaker = new WebSocketClientHandshakerFactory()
                .newHandshaker(uri, WebSocketVersion.V13, null, false, customHeaders);

        final CountDownLatch latch = new CountDownLatch(1);

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = Channels.pipeline();

                pipeline.addLast("decoder", new HttpResponseDecoder());
                pipeline.addLast("encoder", new HttpRequestEncoder());
                pipeline.addLast("ws-handler", new org.httpkit.ws.WebSocketClientHandler(handshaker, queue,
                        latch));
                return pipeline;
            }
        });
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(uri.getHost(), uri
                .getPort()));
        future.syncUninterruptibly();

        ch = future.getChannel();
        handshaker.handshake(ch).syncUninterruptibly();
        latch.await(); // wait for handleshake complete
    }

    public void sendMessage(String message) {
        ch.write(new TextWebSocketFrame(message));
    }

    public void sendFragmentedMesg(String message, int frameCount) {
        int length = message.length();
        int frame = length / frameCount;
        int i;
        for (i = 0; i < length - frame; i += frame) {
            ch.write(new TextWebSocketFrame(false, 0, message.substring(i, i + frame)));
        }
        ch.write(new TextWebSocketFrame(message.substring(i)));
    }


    public void sendFragmentedMesg(String message) {
        int length = message.length();
        int perframe = Math.min(4000, new Random().nextInt(length / 2) + 40);
        int i;
        for (i = 0; i < length - perframe; i += perframe) {
            ch.write(new TextWebSocketFrame(false, 0, message.substring(i, i + perframe)));
        }
        ch.write(new TextWebSocketFrame(message.substring(i)));
    }

    public void sendBinaryData(byte[] data) {
        ch.write(new BinaryWebSocketFrame(ChannelBuffers.wrappedBuffer(data)));
    }

    public Object getMessage() throws InterruptedException {
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof TextWebSocketFrame) {
            return ((TextWebSocketFrame) frame).getText();
        } else if (frame instanceof BinaryWebSocketFrame) {
            return frame.getBinaryData().array();
        }
        return frame;
    }

    public String pong(String data) throws Exception {
        byte[] bytes = data.getBytes();
        ch.write(new PongWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)));
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof PingWebSocketFrame) {
            ChannelBuffer d = frame.getBinaryData();
            return new String(d.array(), 0, d.readableBytes());
        } else {
            throw new Exception("pong frame expected, instead of " + frame);
        }
    }

    public String ping(String data) throws Exception {
        byte[] bytes = data.getBytes();
        ch.write(new PingWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)));
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof PongWebSocketFrame) {
            ChannelBuffer d = frame.getBinaryData();
            return new String(d.array(), 0, d.readableBytes());
        } else {
            throw new Exception("pong frame expected, instead of " + frame);
        }
    }

    public void close() throws Exception {
        ch.write(new CloseWebSocketFrame());
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof CloseWebSocketFrame) {
            ch.close();
            ch.getCloseFuture().awaitUninterruptibly();
            bootstrap.releaseExternalResources();
        } else {
            throw new Exception("CloseWebSocketFrame excepted");
        }
    }
}
