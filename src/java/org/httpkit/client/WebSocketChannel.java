package org.httpkit.client;

import org.httpkit.*;
import org.httpkit.ProtocolException;
import clojure.lang.IFn;
import clojure.lang.Keyword;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer; // Currently not using it
import java.util.concurrent.ConcurrentLinkedQueue; // Currently not using this
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;

import static java.nio.channels.SelectionKey.*;

public final class WebSocketChannel implements Runnable {

    private volatile IFn closeHandler = null;
    private volatile IFn receiveHandler = null;
    private volatile IFn openHandler = null;
    private volatile IFn errorHandler = null;

    Selector selector;
    SelectionKey key;
    boolean running = false;
    String url;
    private final Queue<Object> pending = new ConcurrentLinkedQueue<Object>();

    public WebSocketChannel(String url) throws IOException {
        this.url = url;
    }

    public void startHandshake() {
        running = true;
        // TODO: add name to this thread
        Thread t = new Thread(this);
        t.setDaemon(true); // TODO not sure if this line is neccessary
        t.start();
    }

    public void send(Object data) {
        pending.offer(data);
        key.interestOps(OP_WRITE);
        selector.wakeup();
    }

    public void close() {
        try {
            // TODO: invoke the close handler
            selector.close();
            key.channel().close();
            //selector.wakeup();
        } catch (IOException bye) {
        }
        running = false;
    }

    public void setReceiveHandler(IFn f) {
        receiveHandler = f;
    }

    public void setOpenHandler(IFn f) {
        openHandler = f;
    }

    public void setErrorHandler(IFn f) {
        errorHandler = f;
    }

    public void setCloseHandler(IFn f) {
        closeHandler = f;
    }

    public void run() {
        try {
            SocketChannel ch = SocketChannel.open();
            // Get address and Connect to host
            //ch.connect(); 
            ch.finishConnect();

            /* Suppose to do blocking handshake here */

            /* Handshake is done */

            ch.configureBlocking(false);
            this.key = ch.register(selector, OP_READ);
            selector.open();
        } catch (IOException e) {
            HttpUtils.printError("Handshake failed", e);
            return;
        }

        while (running) {
            try {
                int select = selector.select(2000);
                if (select == 0) {
                    // select must equal 1 here
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> ite = selectedKeys.iterator();
                    while (ite.hasNext()) {
                        SelectionKey key = ite.next();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key != this.key) {
                            // It will never happen
                            throw new IOException();
                        }

                        // Might be both readable and writeable at the same time?
                        if (key.isReadable()) {
                            doRead();
                        } 
                        if (key.isWritable()) {
                            doWrite();
                        }
                        ite.remove();
                    }                 
                } else if (select > 1) {
                    throw new IOException("OMG! Multiple keys were selected! WEIRD!");
                }

            } catch (Throwable e) {
                HttpUtils.printError("Client websocket select exception", e);
            }
        }
    }

    private void doRead() {
        // Read data from channel and invoke the open handler
    }

    private void doWrite() {
        // Try to write to channel. once done clear the interestOps
    }
}
