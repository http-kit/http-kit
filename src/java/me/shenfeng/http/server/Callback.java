package me.shenfeng.http.server;

import static me.shenfeng.http.HttpUtils.ASCII;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.UTF_8;
import static me.shenfeng.http.HttpUtils.encodeResponseHeader;
import static me.shenfeng.http.HttpUtils.readAll;
import static me.shenfeng.http.server.ClojureRing.BODY;
import static me.shenfeng.http.server.ClojureRing.HEADERS;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.shenfeng.http.DynamicBytes;
import clojure.lang.ISeq;
import clojure.lang.Seqable;

public class Callback implements IResponseCallback {
    private final SelectionKey key;
    private ConcurrentLinkedQueue<SelectionKey> pendings;

    public Callback(ConcurrentLinkedQueue<SelectionKey> pendings,
            SelectionKey key) {
        this.pendings = pendings;
        this.key = key;
    }

    // maybe in another thread :worker thread
    public void run(int status, Map<String, Object> headers, Object body) {
        ServerAtta atta = (ServerAtta) key.attachment();
        // extend ring spec to support async
        if (body instanceof IListenableFuture) {
            final int status2 = status;
            final Map<String, Object> headers2 = headers;
            final IListenableFuture future = (IListenableFuture) body;
            future.addListener(new Runnable() {
                @SuppressWarnings({ "rawtypes", "unchecked" })
                public void run() {
                    Object r = future.get();
                    // if is a ring spec response
                    if (r instanceof Map) {
                        Map resp = (Map) r;
                        Map<String, Object> headers = (Map) resp.get(HEADERS);
                        int status = ClojureRing.getStatus(resp);
                        new Callback(pendings, key).run(status, headers,
                                resp.get(BODY));
                    } else {
                        // treat it as just body
                        new Callback(pendings, key).run(status2, headers2, r);
                    }
                }
            });
            return;
        }

        if (headers != null) {
            // copy to modify
            headers = new TreeMap<String, Object>(headers);
        } else {
            headers = new TreeMap<String, Object>();
        }
        ByteBuffer bodyBuffer = null, headBuffer = null;
        try {
            if (body == null) {
                headers.put(CONTENT_LENGTH, "0");
            } else if (body instanceof String) {
                byte[] b = ((String) body).getBytes(UTF_8);
                bodyBuffer = ByteBuffer.wrap(b);
                headers.put(CONTENT_LENGTH, Integer.toString(b.length));
            } else if (body instanceof InputStream) {
                DynamicBytes b = readAll((InputStream) body);
                bodyBuffer = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
            } else if (body instanceof File) {
                File f = (File) body;
                // serving file is better be done by nginx
                byte[] b = readAll(f);
                bodyBuffer = ByteBuffer.wrap(b);
            } else if (body instanceof Seqable) {
                ISeq seq = ((Seqable) body).seq();
                DynamicBytes b = new DynamicBytes(seq.count() * 512);
                while (seq != null) {
                    b.append(seq.first().toString(), UTF_8);
                    seq = seq.next();
                }
                bodyBuffer = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
            } else {
                throw new RuntimeException(body.getClass()
                        + " is not understandable");
            }
        } catch (IOException e) {
            byte[] b = e.getMessage().getBytes(ASCII);
            status = 500;
            headers.clear();
            headers.put(CONTENT_LENGTH, Integer.toString(b.length));
            bodyBuffer = ByteBuffer.wrap(b);
        }
        DynamicBytes bytes = encodeResponseHeader(status, headers);
        headBuffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        atta.addBuffer(headBuffer, bodyBuffer);
        pendings.offer(key);
        key.selector().wakeup();
    }
}
