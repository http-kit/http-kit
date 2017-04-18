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
    private final String name;
    private final String filename;
    private final Object content;
    private final String contentType;

    public MultipartEntity(String name, Object content, String filename, String contentType) {
        this.name = name;
        this.filename = filename;
        this.content = content;
        this.contentType = contentType;
    }

    public MultipartEntity(String name, Object content, String filename) {
        this(name, content, filename, null);
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

            if (e.contentType != null) {
                bytes.append("Content-Type: ").append(e.contentType).append("\r\n\r\n");
            } else if (e.content instanceof File || e.content instanceof InputStream) {
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
            } else if (e.content instanceof byte[]) {
                byte[] contentBytes = (byte[])e.content;
                bytes.append(contentBytes, contentBytes.length);
            } else if (e.content instanceof Number) {
                bytes.append(e.toString(), HttpUtils.UTF_8);
            } else
                throw new IllegalArgumentException("Unknown parameter type " + e.content.getClass().getName() + " of parameter " + e.name + ". Try to pass a string.");
            bytes.append(HttpUtils.CR, HttpUtils.LF);
        }

        bytes.append("--").append(boundary).append("--\r\n");
        return ByteBuffer.wrap(bytes.get(), 0, bytes.length());
    }
}
