package me.shenfeng.http.ws;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketVersion;

public class WebSocketClient {

    private final URI uri;
    ClientBootstrap bootstrap;
    Channel ch = null;
    private BlockingQueue<WebSocketFrame> queue = new ArrayBlockingQueue<WebSocketFrame>(10);

    public WebSocketClient(String url) throws Exception {
        bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(), Executors.newCachedThreadPool()));
        this.uri = new URI(url);
        HashMap<String, String> customHeaders = new HashMap<String, String>();
        final WebSocketClientHandshaker handshaker = new WebSocketClientHandshakerFactory()
                .newHandshaker(uri, WebSocketVersion.V13, null, false, customHeaders);

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = Channels.pipeline();

                pipeline.addLast("decoder", new HttpResponseDecoder());
                pipeline.addLast("encoder", new HttpRequestEncoder());
                pipeline.addLast("ws-handler", new WebSocketClientHandler(handshaker, queue));
                return pipeline;
            }
        });
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(uri.getHost(), uri
                .getPort()));
        future.syncUninterruptibly();

        ch = future.getChannel();
        handshaker.handshake(ch).syncUninterruptibly();
    }

    public void sendMessage(String message) {
        ch.write(new TextWebSocketFrame(message));
    }

    public Object getMessage() throws InterruptedException {
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof TextWebSocketFrame) {
            return ((TextWebSocketFrame) frame).getText();
        }
        return frame;
    }

    public String ping(String data) throws Exception {
        byte[] bytes = data.getBytes();
        ch.write(new PingWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)));
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof PongWebSocketFrame) {
            ChannelBuffer d = frame.getBinaryData();
            return new String(d.array(), 0, d.readableBytes());
        } else {
            throw new Exception("socket pong frame is expected");
        }
    }

    public void close() throws Exception {
        ch.write(new CloseWebSocketFrame());
        WebSocketFrame frame = queue.poll(5, TimeUnit.SECONDS);
        if (frame instanceof CloseWebSocketFrame) {
            ch.close();
            ch.getCloseFuture().awaitUninterruptibly();
        } else {
            throw new Exception("CloseWebSocketFrame excepted");
        }
    }
}
