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

    private static void validateHeaderValue(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Multipart " + field + " must not be null");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0
                    || (c < 32 && c != '\t') || c == 127) {
                throw new IllegalArgumentException(
                        "Invalid character in multipart " + field);
            }
        }
    }

    private static String quoted(String value, String field) {
        validateHeaderValue(value, field);
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                result.append('\\');
            }
            result.append(c);
        }
        return result.toString();
    }

    public static ByteBuffer encode(String boundary, List<MultipartEntity> entities, Boolean multipartMixed) throws IOException {
        DynamicBytes bytes = new DynamicBytes(entities.size() * 1024);
        for (MultipartEntity e : entities) {
            bytes.append("--").append(boundary).append(HttpUtils.CR, HttpUtils.LF);
            if (multipartMixed == null || !multipartMixed) {
                bytes.append("Content-Disposition: form-data; name=\"");
                bytes.append(quoted(e.name, "name"), HttpUtils.UTF_8);
                if (e.filename != null) {
                    bytes.append("\"; filename=\"")
                            .append(quoted(e.filename, "filename"), HttpUtils.UTF_8)
                            .append("\"\r\n");
                } else {
                    bytes.append("\"\r\n");
                }
            }

            if (e.contentType != null) {
                validateHeaderValue(e.contentType, "content type");
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
                bytes.append(HttpUtils.readAll((File) e.content));
            } else if (e.content instanceof ByteBuffer) {
                bytes.append((ByteBuffer) e.content);
            } else if (e.content instanceof byte[]) {
                byte[] contentBytes = (byte[])e.content;
                bytes.append(contentBytes, contentBytes.length);
            } else if (e.content instanceof Number) {
                bytes.append(e.content.toString(), HttpUtils.UTF_8);
            } else
                throw new IllegalArgumentException("Unknown parameter type " + e.content.getClass().getName() + " of parameter " + e.name + ". Try to pass a string.");
            bytes.append(HttpUtils.CR, HttpUtils.LF);
        }

        bytes.append("--").append(boundary).append("--\r\n");
        return ByteBuffer.wrap(bytes.get(), 0, bytes.length());
    }
}
