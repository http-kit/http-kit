package org.httpkit.ws;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.util.CharsetUtil;

public class WebSocketClientHandler extends SimpleChannelUpstreamHandler {

    private final WebSocketClientHandshaker handshaker;
    private BlockingQueue<WebSocketFrame> queue;
    private CountDownLatch latch;

    public WebSocketClientHandler(WebSocketClientHandshaker handshaker,
            BlockingQueue<WebSocketFrame> queue, CountDownLatch latch) {
        this.handshaker = handshaker;
        this.queue = queue;
        this.latch = latch;
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Channel ch = ctx.getChannel();
        if (!handshaker.isHandshakeComplete()) {
            handshaker.finishHandshake(ch, (HttpResponse) e.getMessage());
            latch.countDown();
            return;
        }

        if (e.getMessage() instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) e.getMessage();
            throw new Exception("Unexpected HttpResponse (status=" + response.getStatus()
                    + ", content=" + response.getContent().toString(CharsetUtil.UTF_8) + ')');
        }

        WebSocketFrame frame = (WebSocketFrame) e.getMessage();
        if (frame != null)
            queue.offer(frame);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        final Throwable t = e.getCause();
        t.printStackTrace();
        e.getChannel().close();
    }
}
