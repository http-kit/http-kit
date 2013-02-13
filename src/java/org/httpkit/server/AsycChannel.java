package org.httpkit.server;

import static org.httpkit.server.ClojureRing.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import org.httpkit.HttpUtils;

import clojure.lang.Keyword;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class AsycChannel {

    final SelectionKey key;
    final private HttpServer server;
    final HttpRequest req;

    private volatile boolean initalWrite = true;
    private volatile boolean finalWritted = false;

    public AsycChannel(SelectionKey key, HttpRequest req, HttpServer server) {
        this.key = key;
        this.req = req;
        this.server = server;
    }

    private static ByteBuffer chunkSize(int size) {
        String s = Integer.toHexString(size) + "\r\n";
        byte[] bytes = s.getBytes();
        return ByteBuffer.wrap(bytes);
    }

    private static byte[] finalChunkBytes = "0\r\n\r\n".getBytes();
    private static byte[] finalChunkBytes2 = "\r\n0\r\n\r\n".getBytes();
    private static byte[] newLineBytes = "\r\n".getBytes();

    public void write(Object data, boolean isFinal) throws IOException {

        if (finalWritted == true) {
            throw new IllegalStateException("final chunked has already been write");
        }

        if (isFinal == true) {
            finalWritted = true;
        }

        ByteBuffer buffers[];
        boolean isMap = (data instanceof Map);

        if (initalWrite) {
            int status = 200;
            Object body = data;
            Map<String, Object> headers = new TreeMap<String, Object>();
            if (isMap) {
                Map<Keyword, Object> resp = (Map<Keyword, Object>) data;
                headers = getHeaders(resp, req);
                status = getStatus(resp);
                body = resp.get(BODY);
            }

            if (headers.isEmpty()) { // default 200 and text/html
                headers.put("Content-Type", "text/html; charset=utf-8");
            }

            if (isFinal) { // normal response
                buffers = encode(status, headers, body);
            } else {
                headers.put("Transfer-Encoding", "chunked"); // first chunk
                ByteBuffer[] bb = encode(status, headers, body);
                if (body == null) {
                    buffers = bb;
                } else {
                    buffers = new ByteBuffer[] { bb[0], chunkSize(bb[1].remaining()), bb[1],
                            ByteBuffer.wrap(newLineBytes) };
                }
            }
        } else {
            Object body = isMap ? ((Map) data).get(BODY) : data;

            if (body != null) {
                ByteBuffer t = HttpUtils.bodyBuffer(body);
                ByteBuffer size = chunkSize(t.remaining());
                if (isFinal) {
                    buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(finalChunkBytes2) };
                } else {
                    buffers = new ByteBuffer[] { size, t, ByteBuffer.wrap(newLineBytes) };
                }

            } else {
                if (isFinal)
                    buffers = new ByteBuffer[] { ByteBuffer.wrap(finalChunkBytes) };
                else {
                    return; // strange, body is null, and not final
                }
            }
        }

        HttpServerAtta att = (HttpServerAtta) key.attachment();
        att.addBuffer(buffers);
        server.queueWrite(key);

        initalWrite = false;
    }
}
