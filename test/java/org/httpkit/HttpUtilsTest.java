package org.httpkit;

import org.junit.Test;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.httpkit.HttpUtils.HttpEncode;
import static org.junit.Assert.assertTrue;

public class HttpUtilsTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInjectedResponseHeaders() {
        HeaderMap headers = new HeaderMap();
        headers.put("X-Test", "safe\r\nInjected: yes");
        HttpEncode(200, headers, "body");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidResponseHeaderNames() {
        HeaderMap headers = new HeaderMap();
        headers.put("Bad Header", "value");
        HttpEncode(200, headers, "body");
    }

    @Test(expected = EOFException.class)
    public void reportsFileTruncationInsteadOfLooping() throws Exception {
        File file = File.createTempFile("http-kit-short-file", ".txt");
        try {
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(1);
            } finally {
                output.close();
            }
            HttpUtils.readContent(file, 2);
        } finally {
            file.delete();
        }
    }

    @Test(expected = ContentTooLargeException.class)
    public void rejectsFilesTooLargeToBuffer() throws Exception {
        File file = File.createTempFile("http-kit-large-file", ".bin");
        try {
            RandomAccessFile sparse = new RandomAccessFile(file, "rw");
            try {
                sparse.setLength((long) Integer.MAX_VALUE + 1L);
            } finally {
                sparse.close();
            }
            HttpUtils.readAll(file);
        } finally {
            file.delete();
        }
    }

    @Test
    public void encodesIoErrorsWithoutMessages() {
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException();
            }
        };

        ByteBuffer[] encoded = HttpEncode(200, new HeaderMap(), body);
        StringBuilder response = new StringBuilder();
        for (ByteBuffer buffer : encoded) {
            response.append(StandardCharsets.US_ASCII.decode(buffer.duplicate()));
        }
        assertTrue(response.toString().startsWith("HTTP/1.1 500 "));
        assertTrue(response.toString().endsWith("I/O error encoding response"));
    }
}
