package org.httpkit.client;

import org.httpkit.*;
import org.httpkit.ProtocolException;
import org.httpkit.server.WSDecoder;
import org.httpkit.server.Frame;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.IPersistentMap;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer; 
import java.util.concurrent.ConcurrentLinkedQueue; 
import java.util.LinkedList;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.Random;
import java.util.HashMap;
import java.security.MessageDigest;

import static org.httpkit.HttpUtils.*;
import static java.nio.channels.SelectionKey.*;
import static javax.xml.bind.DatatypeConverter.printBase64Binary;
import static javax.xml.bind.DatatypeConverter.parseHexBinary;
import static org.httpkit.HttpUtils.SP;
import static org.httpkit.server.WSDecoder.*;
import static org.httpkit.server.Frame.TextFrame;

@SuppressWarnings({"unchecked"})
public final class WebSocketChannel implements Runnable {

    private volatile IFn closeHandler = null;
    private volatile IFn receiveHandler = null;
    private volatile IFn openHandler = null;
    private volatile IFn errorHandler = null;

    Selector selector;
    SelectionKey key;
    boolean running = false;
    String url;
    private final Queue<ByteBuffer> pending = new ConcurrentLinkedQueue<ByteBuffer>();
    private IPersistentMap defaultParameters;
    private WSDecoder decoder;

    public WebSocketChannel(String url, IPersistentMap opt) throws IOException {
        this.url = url;
        this.defaultParameters = opt.assoc(Keyword.intern("channel"), this);
        this.decoder = new WSDecoder(true);
    }

    public void startHandshake() {
        // TODO: add name to this thread?
        Thread t = new Thread(this);
        t.setDaemon(true); // TODO not sure if this line is neccessary
        t.start();
    }

    public void send(Object data) throws IOException {
        if (!running)
            throw new IOException("The channel is already closed");
        ByteBuffer bytes = null;
        if (data instanceof String) { // null is not allowed
            bytes = WsEncode(OPCODE_TEXT, ((String) data).getBytes(UTF_8), true);
        } else if (data instanceof byte[]) {
            bytes = WsEncode(OPCODE_BINARY, (byte[]) data, true);
        } else if (data instanceof InputStream) {
            DynamicBytes dbytes = readAll((InputStream) data);
            bytes = WsEncode(OPCODE_BINARY, dbytes.get(), dbytes.length(), true);
        } else if (data != null) { // ignore null
            String mesg = "send! called with data: " + data.toString() +
                "(" + data.getClass() + "), but only string, byte[], InputStream expected";
            throw new IllegalArgumentException(mesg);
        }
        pending.offer(bytes);

        key.interestOps(OP_WRITE | OP_READ);
        selector.wakeup();
    }

    public void close() {
        running = false;
        try {
            key.channel().close();
            selector.close();
        } catch (IOException bye) {
        }
    }

    public void closeNormally() {
        if (closeHandler != null)
            closeHandler.invoke();
        this.close();
    }

    private void closeForError() {
        if (errorHandler != null)
            this.errorHandler.invoke();
        this.close();
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

    private void doHandshake() throws Throwable {
        // TODO TCP might be full here. A potential bug
        // TODO should prevent this method being called multiple times 
        if (running) 
            throw new ProtocolException("Can't hand shake multiple times");
        URI uri = new URI(this.url);
        InetSocketAddress addr = getServerAddr(uri);
        SocketChannel ch = SocketChannel.open();
        // Get address and Connect to host
        ch.connect(addr); 
        if (!ch.finishConnect()) {
            throw new IOException("Can't connect to host");
        }

        /* do blocking handshake here */
        HeaderMap header = new HeaderMap();
        // This class is very convinient :D
        header.put("Host", uri.getHost());
        header.put("Upgrade", "websocket");
        header.put("Connection", "Upgrade");
        header.put("Sec-WebSocket-Version", "13");
        byte[] rands = new byte[16];
        new Random().nextBytes(rands);
        String wsKey = printBase64Binary(rands);
        header.put("Sec-WebSocket-Key", wsKey);
        DynamicBytes bytes = new DynamicBytes(196);
        bytes.append("GET").append(SP).append(HttpUtils.getPath(uri));
        bytes.append(" HTTP/1.1\r\n");
        header.encodeHeaders(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        ch.write(buffer);
        while (buffer.hasRemaining()) {
            // It wont happend too often
            // Sleep awhile 
            Thread.sleep(100);
            ch.write(buffer);
        }

        ByteBuffer responceBuffer = ByteBuffer.allocateDirect(1024 * 8);
        int read = ch.read(responceBuffer);
        if (read == -1) {
            throw new IOException("Server shutted down the connection");
        } else {
            responceBuffer.flip();   
            LineReader reader = new LineReader(4096);
            HeaderMap responseHeader = new HeaderMap();
            // Status line
            String line = reader.readLine(responceBuffer);
            if (!line.matches("HTTP/1.1 101 Switching Protocols$"))
                throw new ProtocolException("Websocket received wrong status code: " + line);

            line = reader.readLine(responceBuffer);
            while (line != null && !line.isEmpty()) {
                String[] tmp = line.split(":", 2);
                if (tmp.length != 2) 
                    throw new ProtocolException("Incorrect header format: " + line);
                responseHeader.put(tmp[0].trim(), tmp[1].trim());
                line = reader.readLine(responceBuffer);
            }
            if (!responseHeader.get("Upgrade").equals("websocket") || 
                !responseHeader.get("Connection").equals("Upgrade")) {
                throw new ProtocolException("Bad hand shake");
            }

            byte[] tmp = MessageDigest.getInstance("SHA1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());
            String testKey = printBase64Binary(tmp);
            if (testKey.trim().equals(responseHeader.get("Sec-WebSocket-Accept"))) {
                throw new ProtocolException("Websocket secure key can't match.");
            }
        }
        /* Handshake is done */

        running = true;
        ch.configureBlocking(false);
        /* invoke the open handler */
        if (openHandler != null) 
            openHandler.invoke(this.defaultParameters);
        selector = Selector.open();
        this.key = ch.register(selector, OP_READ);
    }

    public void run() {
        try {
            doHandshake();
        } catch (Throwable e) {
            HttpUtils.printError("Handshake failed", e);
            this.closeForError();
            return;
        }

        while (running) {
            try {
                int select = selector.select(2000);
                if (select == 1) {
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
        if (!running) return;
        SocketChannel ch = (SocketChannel) this.key.channel();
        try {
            // TODO: im not sure if this is a costy operation
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);
            int read = ch.read(buffer);
            if (read == -1) {
                throw new IOException("Remote peer closed connection");
            } else {
                buffer.flip();
                Frame frame = decoder.decode(buffer);
                if (frame instanceof TextFrame) {
                    IPersistentMap mp = defaultParameters.assoc(Keyword.intern("data"), ((TextFrame)frame).getText());
                    this.receiveHandler.invoke(mp);
                } else {
                }
            }
        } catch (IOException e) {
            HttpUtils.printError("Client websocket read exception", e);
            this.closeForError();
            return;
        } catch (ProtocolException e) {
            HttpUtils.printError("Client websocket read protocol exception", e);
            this.closeForError();
            return;
        }
    }

    private void doWrite() {
        // Read data from channel and invoke the open handler
        // Try to write to channel. once done clear the interestOps
        if (!running) return;
        SocketChannel ch = (SocketChannel) this.key.channel();
        ByteBuffer data = pending.peek();
        while (null != data) {
            try {
                ch.write(data);
            } catch (IOException e) {
                this.closeForError();
                return;
            }
            if (data.hasRemaining()) // TCP buffer is full, try next time
                return;
            pending.poll();
            data = pending.peek();
        }
        key.interestOps(OP_READ);
    }
}
