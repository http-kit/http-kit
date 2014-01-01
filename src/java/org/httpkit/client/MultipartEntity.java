package org.httpkit.client;

import org.httpkit.DynamicBytes;
import org.httpkit.HttpUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * @author feng <shenedu@gmail.com>
 *         2014/1/1
 */
public class MultipartEntity {
    private String name;
    private String filename;
    private Object content;

    public MultipartEntity(String name, Object content, String filename) {
        this.name = name;
        this.filename = filename;
        this.content = content;
    }

    public static String genBoundary(List<MultipartEntity> entities) {
        return "----HttpKitFormBoundary" + System.currentTimeMillis();
    }

    public static ByteBuffer encode(String boundary, List<MultipartEntity> entities) throws IOException {
        DynamicBytes bytes = new DynamicBytes(entities.size() * 1024);
        for (MultipartEntity e : entities) {
            bytes.append("--").append(boundary).append(HttpUtils.CR, HttpUtils.LF);
            bytes.append("Content-Disposition: form-data; name=\"");
            bytes.append(e.name, HttpUtils.UTF_8);
            if (e.filename != null) {
                bytes.append("\"; filename=\"").append(e.filename).append("\"\r\n");
            } else {
                bytes.append("\"\r\n");
            }
            if (e.content instanceof File || e.content instanceof InputStream) {
                // TODO configurable
                bytes.append("Content-Type: application/octet-stream\r\n\r\n");
            } else {
                bytes.append("\r\n");
            }

            if (e.content instanceof String) {
                bytes.append((String) e.content, HttpUtils.UTF_8);
            } else if (e.content instanceof InputStream) {
                DynamicBytes b = HttpUtils.readAll((InputStream) e.content);
                bytes.append(b.get(), b.length());
            } else if (e.content instanceof File) {
                byte[] b = HttpUtils.readContent((File) e.content, (int) ((File) e.content).length());
                bytes.append(b, b.length);
            } else if (e.content instanceof ByteBuffer) {
                while (((ByteBuffer) e.content).hasRemaining()) {
                    bytes.append(((ByteBuffer) e.content).get()); // copy
                }
            }
            bytes.append(HttpUtils.CR, HttpUtils.LF);
        }

        bytes.append("--").append(boundary).append("--\r\n");
        return ByteBuffer.wrap(bytes.get(), 0, bytes.length());
    }
}
