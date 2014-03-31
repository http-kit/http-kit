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
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer; 
import java.util.concurrent.ConcurrentLinkedQueue; 
import java.util.concurrent.ExecutorService; 
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
public final class WebSocketChannel {
/*
 * TODO: seperate WS decoding logic
 */

    public enum State {
        HANDSHAKE_START, READING_STATUS, READING_HEADER, ALL_READ 
    }

    private final class AsyncOrderedTask implements Runnable {
        IFn fn; 
        IPersistentMap parameters = null;
        public AsyncOrderedTask(IFn fn) {
            this.fn = fn;
        }
        public AsyncOrderedTask(IFn fn, IPersistentMap parameters) {
            this.fn = fn;
            this.parameters = parameters;
        }
        public void run() {
            if (parameters != null) 
                fn.invoke(parameters);
            else
                fn.invoke();
            if (!pendingTask.isEmpty())
                pendingTask.poll().run();
        }

        public void exec() {
            if (fn == null) return;
            synchronized(pendingTask) {
                // TODO: not sure if this is right
                if (pendingTask.isEmpty()) {
                    execs.submit(this);
                } else {
                    pendingTask.offer(this);
                }
            }
        }
    }

    private State state = State.HANDSHAKE_START;

    public SelectionKey key;
    public InetSocketAddress addr;
    private URI uri;

    private volatile IFn closeHandler = null;
    private volatile IFn receiveHandler = null;
    private volatile IFn openHandler = null;
    private volatile IFn errorHandler = null;

    private final Queue<ByteBuffer> pendingData = new ConcurrentLinkedQueue<ByteBuffer>();
    private final Queue<ByteBuffer> pendingDataB4Handshake = new ConcurrentLinkedQueue<ByteBuffer>();
    private final Queue<AsyncOrderedTask> pendingTask = new ConcurrentLinkedQueue<AsyncOrderedTask>();
    private IPersistentMap defaultParameters;
    private WSDecoder decoder;
    private ExecutorService execs; 
    private LineReader reader;
    private HeaderMap responseHeader; 
    private String wsKey;

    public WebSocketChannel(ExecutorService execs, String url, IPersistentMap opt) throws IOException {
        this.defaultParameters = opt.assoc(Keyword.intern("channel"), this);
        this.key = key;
        this.decoder = new WSDecoder(true);
        this.execs = execs;
        
        try {
            this.uri = new URI(url);
        } catch (Throwable e) {
            throw new IOException("Wrong url");
        }
        this.addr = getServerAddr(uri);
        this.reader = new LineReader(4096);
    }

    public void send(Object data) throws IOException {
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
        if (state == State.HANDSHAKE_START)
            pendingDataB4Handshake.offer(bytes);
        else
            pendingData.offer(bytes);

        key.interestOps(OP_WRITE | OP_READ);
    }

    private void transferPendingData() {
        ByteBuffer tmp;
        while ((tmp = pendingDataB4Handshake.poll()) != null) {
            pendingData.offer(tmp);
        }
    }

    public void close() {
        try {
            this.key.channel().close();
        } catch (IOException bye) {
        }
    }

    public void closeNormally() {
        (new AsyncOrderedTask(closeHandler, defaultParameters)).exec();
        this.close();
    }

    public void closeForError() {
        (new AsyncOrderedTask(errorHandler, defaultParameters)).exec();
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

    public void doHandshake() throws Throwable {

        HeaderMap header = new HeaderMap();
        // This class is very convinient :D
        header.put("Host", uri.getHost());
        header.put("Upgrade", "websocket");
        header.put("Connection", "Upgrade");
        header.put("Sec-WebSocket-Version", "13");
        byte[] rands = new byte[16];
        new Random().nextBytes(rands);
        this.wsKey = printBase64Binary(rands);
        header.put("Sec-WebSocket-Key", wsKey);
        DynamicBytes bytes = new DynamicBytes(196);
        bytes.append("GET").append(SP).append(HttpUtils.getPath(uri));
        bytes.append(" HTTP/1.1\r\n");
        header.encodeHeaders(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        pendingData.offer(buffer);
        transferPendingData();
        state = State.READING_STATUS;
    }

    private void continueHandshake(ByteBuffer buffer) throws Throwable {
        String line;
        while (buffer.hasRemaining()) {
            if (state == State.READING_STATUS) {
                line = reader.readLine(buffer);
                if (line != null && !line.isEmpty()) {
                    if (!line.matches("HTTP/1.1 101 Switching Protocols$"))
                        throw new ProtocolException("Websocket received wrong status code: " + line);
                    else {
                        responseHeader = new HeaderMap();
                        state = State.READING_HEADER;
                    }
                }
            } else if (state == State.READING_HEADER) {

                line = reader.readLine(buffer);
                while (line != null && !line.isEmpty()) {
                    // TODO:This is a little bit unsafe
                    String[] tmp = line.split(":", 2);
                    if (tmp.length != 2) 
                        throw new ProtocolException("Incorrect header format: " + line);
                    responseHeader.put(tmp[0].trim(), tmp[1].trim());
                    line = reader.readLine(buffer);
                }

                if (line == null)// Header must end with a empty line
                    return;                 
                // TODO: case might be problem
                if (!responseHeader.get("Upgrade").equals("websocket") || 
                    !responseHeader.get("Connection").equals("Upgrade")) {
                    throw new ProtocolException("Bad hand shake");
                }

                byte[] tmp = MessageDigest.getInstance("SHA1").digest(
                        (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());
                String testKey = printBase64Binary(tmp);
                if (!testKey.trim().equals(
                            responseHeader.get("Sec-Websocket-Accept"))) {
                    throw new ProtocolException("Websocket secure key can't match.");
                }

                state = State.ALL_READ;
            }
        }
    }

    public void doRead() {
        SocketChannel ch = (SocketChannel) this.key.channel();
        try {
            // TODO: im not sure if this is a costy operation
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);
            int read = ch.read(buffer);
            if (read == -1) {
                throw new IOException("Remote peer closed connection");
            } else {
                buffer.flip();
                if (state != State.ALL_READ) {
                    continueHandshake(buffer);
                    return;
                }
                // TODO: it cant handle fragment message yet
                Frame frame = decoder.decode(buffer);
                if (frame instanceof TextFrame) {
                    IPersistentMap mp = defaultParameters.assoc(Keyword.intern("data"), ((TextFrame)frame).getText());
                    (new AsyncOrderedTask(receiveHandler, mp)).exec();
                    decoder.reset();
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
        } catch (Throwable e) {
            HttpUtils.printError("Handshake failed", e);
            this.closeForError();
            return;       
        }
    }

    public void doWrite() {
        SocketChannel ch = (SocketChannel) this.key.channel();
        ByteBuffer data = pendingData.peek();
        while (null != data) {
            try {
                ch.write(data);
            } catch (IOException e) {
                this.closeForError();
                return;
            }
            if (data.hasRemaining()) // TCP buffer is full, try next time
                return;
            pendingData.poll();
            data = pendingData.peek();
        }
        key.interestOps(OP_READ);
    }
}
