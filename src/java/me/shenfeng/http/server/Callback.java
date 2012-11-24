package me.shenfeng.http.server;

import clojure.lang.ISeq;
import clojure.lang.Seqable;
import me.shenfeng.http.DynamicBytes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.server.ClojureRing.BODY;
import static me.shenfeng.http.server.ClojureRing.HEADERS;

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
                @SuppressWarnings({"rawtypes", "unchecked"})
                public void run() {
                    Object r = future.get();
                    // if is a ring spec response
                    if (r instanceof Map) {
                        Map resp = (Map) r;
                        Map<String, Object> headers = (Map) resp.get(HEADERS);
                        int status = ClojureRing.getStatus(resp);
                        new Callback(pendings, key).run(status, headers, resp.get(BODY));
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
        try {
            if (body == null) {
                atta.respBody = null;
                headers.put(CONTENT_LENGTH, "0");
            } else if (body instanceof String) {
                byte[] b = ((String) body).getBytes(UTF_8);
                atta.respBody = ByteBuffer.wrap(b);
                headers.put(CONTENT_LENGTH, Integer.toString(b.length));
            } else if (body instanceof InputStream) {
                DynamicBytes b = readAll((InputStream) body);
                atta.respBody = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
            } else if (body instanceof File) {
                File f = (File) body;
                // serving file is better be done by nginx
                byte[] b = readAll(f);
                atta.respBody = ByteBuffer.wrap(b);
            } else if (body instanceof Seqable) {
                ISeq seq = ((Seqable) body).seq();
                DynamicBytes b = new DynamicBytes(seq.count() * 512);
                while (seq != null) {
                    b.append(seq.first().toString(), UTF_8);
                    seq = seq.next();
                }
                atta.respBody = ByteBuffer.wrap(b.get(), 0, b.length());
                headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
            } else {
                throw new RuntimeException(body.getClass() + " is not understandable");
            }
        } catch (IOException e) {
            byte[] b = e.getMessage().getBytes(ASCII);
            status = 500;
            headers.clear();
            headers.put(CONTENT_LENGTH, Integer.toString(b.length));
            atta.respBody = ByteBuffer.wrap(b);
        }
        DynamicBytes bytes = encodeResponseHeader(status, headers);
        atta.respHeader = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
        pendings.offer(key);
        key.selector().wakeup();
    }
}
